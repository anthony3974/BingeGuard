package com.tony3974.bingeguard.monitor

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tony3974.bingeguard.BingeGuardApp
import com.tony3974.bingeguard.MainActivity
import com.tony3974.bingeguard.R
import com.tony3974.bingeguard.data.BlockEvent
import com.tony3974.bingeguard.data.OverrideEvent
import com.tony3974.bingeguard.data.TrackedApp
import com.tony3974.bingeguard.db
import com.tony3974.bingeguard.enforce.BlockOverlay
import com.tony3974.bingeguard.enforce.BlockUi
import com.tony3974.bingeguard.enforce.decide
import com.tony3974.bingeguard.enforce.shortName
import com.tony3974.bingeguard.enforce.toPolicy
import com.tony3974.bingeguard.permissions.PermissionStatus
import com.tony3974.bingeguard.policy.Block
import com.tony3974.bingeguard.policy.Decision
import com.tony3974.bingeguard.policy.MINUTE_MS
import com.tony3974.bingeguard.policy.Verdict
import com.tony3974.bingeguard.ui.formatDuration
import com.tony3974.bingeguard.ui.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The monitor: a specialUse foreground service that reads UsageEvents about once a second while
 * the screen is on, works out which app is in front, records time in tracked apps, and enforces
 * each app's policy: a nudge notification, or the block screen and a trip to the home screen.
 *
 * Surviving: START_STICKY brings it back after its process is killed (allowed because the app is
 * exempt from battery optimization), BootReceiver after a reboot or update. Nothing it needs is
 * held only in memory: usage is in Room and the policy engine is stateless.
 */
class MonitorService : LifecycleService() {
    private val tracker = ForegroundTracker()
    private lateinit var recorder: IntervalRecorder
    private lateinit var overlay: BlockOverlay
    private lateinit var usageStats: UsageStatsManager
    private lateinit var power: PowerManager
    private lateinit var keyguard: KeyguardManager
    private val tracked = MutableStateFlow<Map<String, TrackedApp>>(emptyMap())
    private var loop: Job? = null
    private var cursorMs = 0L
    private var lastStatus: String? = null
    private var lastHomeAtMs = 0L
    /** Package -> start of the session already nudged, so each session nudges once. */
    private val nudged = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        recorder = IntervalRecorder(db.usage())
        overlay = BlockOverlay(this)
        usageStats = getSystemService(UsageStatsManager::class.java)
        power = getSystemService(PowerManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        lifecycleScope.launch {
            db.trackedApps().observeAll().collect { list -> tracked.value = list.associateBy { it.packageName } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!goForeground()) return START_NOT_STICKY
        if (!PermissionStatus.hasUsageAccess(this)) {
            Log.w(TAG, "No Usage Access; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) loop = lifecycleScope.launch(Dispatchers.Default) { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        overlay.hide()
        _running.value = false
        super.onDestroy()
    }

    private suspend fun run() {
        val now = System.currentTimeMillis()
        // Rebuild "what's on screen" from recent history, so a restart mid-session picks it up.
        cursorMs = now - REPLAY_MS
        db.usage().deleteBefore(now - KEEP_HISTORY_MS)
        db.events().deleteBlocksBefore(now - KEEP_HISTORY_MS)
        db.events().deleteOverridesBefore(now - KEEP_HISTORY_MS)
        _running.value = true
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    tick(System.currentTimeMillis())
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Tick failed", e)
                }
                delay(if (power.isInteractive) TICK_MS else IDLE_TICK_MS)
            }
        } finally {
            withContext(NonCancellable) { recorder.close(System.currentTimeMillis()) }
            _running.value = false
        }
    }

    private suspend fun tick(nowMs: Long) {
        readEvents(nowMs)
        val onScreen = power.isInteractive && !keyguard.isKeyguardLocked
        val foreground = if (onScreen) tracker.foregroundPackage else null
        _foreground.value = foreground
        val app = foreground?.let { tracked.value[it] }?.takeIf { it.enabled }
        if (app == null) {
            recorder.update(null, nowMs)
            updateStatus(null, null)
            return
        }

        val openId = recorder.open?.takeIf { it.packageName == app.packageName }?.id
        val decision = decide(db, app, nowMs, openId)
        when (decision.verdict) {
            Verdict.BLOCK -> {
                // Stop recording first: time bouncing off the block screen isn't use.
                recorder.update(null, nowMs)
                enforceBlock(app, decision, nowMs)
            }
            Verdict.NUDGE -> {
                recorder.update(app.packageName, nowMs)
                maybeNudge(app, decision, nowMs)
            }
            Verdict.ALLOW -> recorder.update(app.packageName, nowMs)
        }
        updateStatus(app, decision)
    }

    private fun readEvents(nowMs: Long) {
        val events = usageStats.queryEvents(cursorMs - OVERLAP_MS, nowMs) ?: return
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> TrackerEvent.ACTIVITY_RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> TrackerEvent.ACTIVITY_PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> TrackerEvent.ACTIVITY_STOPPED
                UsageEvents.Event.SCREEN_INTERACTIVE -> TrackerEvent.SCREEN_INTERACTIVE
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> TrackerEvent.SCREEN_NON_INTERACTIVE
                UsageEvents.Event.KEYGUARD_SHOWN -> TrackerEvent.KEYGUARD_SHOWN
                UsageEvents.Event.KEYGUARD_HIDDEN -> TrackerEvent.KEYGUARD_HIDDEN
                UsageEvents.Event.DEVICE_SHUTDOWN -> TrackerEvent.DEVICE_SHUTDOWN
                UsageEvents.Event.DEVICE_STARTUP -> TrackerEvent.DEVICE_STARTUP
                else -> null
            } ?: continue
            tracker.onEvent(type, event.packageName, event.className)
        }
        cursorMs = nowMs
    }

    // ---- Enforcement ----

    private suspend fun enforceBlock(app: TrackedApp, decision: Decision, nowMs: Long) {
        val block = decision.block ?: return
        val alreadyShown = withContext(Dispatchers.Main) { overlay.shownFor == app.packageName }
        if (!alreadyShown) {
            db.events().insertBlock(
                BlockEvent(packageName = app.packageName, atMs = nowMs, reason = block.reason.name, untilMs = block.untilMs),
            )
            if (Settings.canDrawOverlays(this)) {
                withContext(Dispatchers.Main) { overlay.show(blockUi(app, decision, block)) }
            } else {
                notifyBlockedWithoutOverlay(app, block)
            }
        }
        // Home, then again every couple of seconds while the app is still in front.
        if (nowMs - lastHomeAtMs >= HOME_RETRY_MS) {
            lastHomeAtMs = nowMs
            goHome()
        }
    }

    private fun blockUi(app: TrackedApp, decision: Decision, block: Block) = BlockUi(
        app = app,
        reason = block.reason,
        untilMs = block.untilMs,
        sessionMs = decision.lastSession?.activeMs,
        todayMs = decision.todayUsedMs,
        rollingMs = decision.rollingUsedMs,
        onClose = { overlay.hide() },
        onOverride = { waitedSec, phrase -> lifecycleScope.launch { grantOverride(app, block, waitedSec, phrase) } },
    )

    /** Runs on the main thread (from the block screen). */
    private suspend fun grantOverride(app: TrackedApp, block: Block, waitedSec: Int, phrase: String) {
        val now = System.currentTimeMillis()
        db.events().insertOverride(
            OverrideEvent(
                packageName = app.packageName,
                atMs = now,
                blockReason = block.reason.name,
                waitedSec = waitedSec,
                phrase = phrase,
                grantedUntilMs = now + app.overrideGrantSec * 1000L,
            ),
        )
        packageManager.getLaunchIntentForPackage(app.packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        overlay.hide()
    }

    /** Allowed from the background because the app holds Display over other apps. */
    private fun goHome() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't go to the home screen", e)
        }
    }

    private fun maybeNudge(app: TrackedApp, decision: Decision, nowMs: Long) {
        val session = decision.session ?: return
        if (nudged[app.packageName] == session.startMs) return
        nudged[app.packageName] = session.startMs
        val policy = app.toPolicy()
        val left = (policy.sessionCapMs - session.activeMs).coerceAtLeast(0)
        val cooldown = policy.cooldownFor(session.activeMs)
        val notification = Notification.Builder(this, BingeGuardApp.CHANNEL_NUDGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${app.label}: ${formatDuration(left, roundToMinutes = left >= MINUTE_MS)} left")
            .setContentText(
                "Blocks at ${formatTime(nowMs + left)}. Leave now and the cooldown is ${formatDuration(cooldown)}.",
            )
            .setAutoCancel(true)
            .setTimeoutAfter(left.coerceAtLeast(10_000))
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        getSystemService(NotificationManager::class.java).notify(nudgeId(app.packageName), notification)
    }

    /** Without the overlay permission all that's left is a heads-up notification. */
    private fun notifyBlockedWithoutOverlay(app: TrackedApp, block: Block) {
        val notification = Notification.Builder(this, BingeGuardApp.CHANNEL_NUDGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${app.label} is blocked (${block.reason.shortName()})")
            .setContentText("Until ${formatTime(block.untilMs)}. Grant Display over other apps so Binge Guard can enforce it.")
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(nudgeId(app.packageName), notification)
    }

    private fun nudgeId(packageName: String) = 1000 + (packageName.hashCode() and 0xFFFF)

    // ---- Ongoing notification ----

    private fun updateStatus(app: TrackedApp?, decision: Decision?) {
        val status = if (app == null || decision == null) null else statusLine(app, decision)
        if (status != lastStatus) {
            lastStatus = status
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
        }
    }

    private fun statusLine(app: TrackedApp, d: Decision): String {
        val block = d.block
        val today = "${formatDuration(d.todayUsedMs, roundToMinutes = true)} today"
        return when {
            block != null && d.verdict == Verdict.BLOCK ->
                "${app.label} blocked (${block.reason.shortName()}) until ${formatTime(block.untilMs)}"
            d.isOverridden -> "${app.label} · override until ${formatTime(d.overrideUntilMs!!)} · $today"
            d.session != null ->
                "${app.label} · session ${d.session!!.activeMs / MINUTE_MS} of ${app.sessionCapSec / 60} min · $today"
            else -> "${app.label} · $today"
        }
    }

    private fun goForeground(): Boolean = try {
        startForeground(NOTIFICATION_ID, buildNotification(lastStatus), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (an IllegalStateException) or a SecurityException.
        Log.e(TAG, "Couldn't start in the foreground", e)
        stopSelf()
        false
    }

    private fun buildNotification(statusText: String?): Notification {
        val count = tracked.value.values.count { it.enabled }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, BingeGuardApp.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (count == 1) "Watching 1 app" else "Watching $count apps")
            .setContentText(statusText ?: "Nothing tracked on screen")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "BingeGuard"
        private const val NOTIFICATION_ID = 1
        const val TICK_MS = 1_000L
        const val IDLE_TICK_MS = 5_000L
        private const val OVERLAP_MS = 3_000L
        private const val REPLAY_MS = 30 * 60_000L
        private const val HOME_RETRY_MS = 2_000L
        private const val KEEP_HISTORY_MS = 90L * 24 * 60 * 60_000L

        private val _running = MutableStateFlow(false)
        /** Whether the monitor loop is running in this process. */
        val running: StateFlow<Boolean> = _running

        private val _foreground = MutableStateFlow<String?>(null)
        /** The package on screen at the last tick (any app, tracked or not). */
        val foreground: StateFlow<String?> = _foreground

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, MonitorService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Not allowed to start the monitor now", e)
            }
        }
    }
}
