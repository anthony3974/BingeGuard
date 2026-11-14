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
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tony3974.bingeguard.BingeGuardApp
import com.tony3974.bingeguard.MainActivity
import com.tony3974.bingeguard.R
import com.tony3974.bingeguard.data.TrackedApp
import com.tony3974.bingeguard.db
import com.tony3974.bingeguard.permissions.PermissionStatus
import com.tony3974.bingeguard.ui.formatDuration
import com.tony3974.bingeguard.ui.startOfTodayMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The monitor: a specialUse foreground service that reads UsageEvents about once a second while
 * the screen is on, works out which app is in front, and records time spent in tracked apps.
 */
class MonitorService : LifecycleService() {
    private val tracker = ForegroundTracker()
    private lateinit var recorder: IntervalRecorder
    private lateinit var usageStats: UsageStatsManager
    private lateinit var power: PowerManager
    private lateinit var keyguard: KeyguardManager
    private val tracked = MutableStateFlow<Map<String, TrackedApp>>(emptyMap())
    private var loop: Job? = null
    private var cursorMs = 0L
    private var lastStatus: String? = null

    override fun onCreate() {
        super.onCreate()
        recorder = IntervalRecorder(db.usage())
        usageStats = getSystemService(UsageStatsManager::class.java)
        power = getSystemService(PowerManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        lifecycleScope.launch {
            db.trackedApps().observeAll().collect { list -> tracked.value = list.associateBy { it.packageName } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!goForeground(statusText = null)) return START_NOT_STICKY
        if (!PermissionStatus.hasUsageAccess(this)) {
            Log.w(TAG, "No Usage Access; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) loop = lifecycleScope.launch(Dispatchers.Default) { run() }
        // Sticky: if Android kills the process, it recreates the service (allowed because the
        // app is exempt from battery optimization).
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        super.onDestroy()
    }

    private suspend fun run() {
        val now = System.currentTimeMillis()
        // Rebuild "what's on screen" from recent history, so a restart mid-session picks it up.
        cursorMs = now - REPLAY_MS
        db.usage().deleteBefore(now - KEEP_HISTORY_MS)
        _running.value = true
        try {
            while (currentCoroutineContext().isActive) {
                tick(System.currentTimeMillis())
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
        recorder.update(app?.packageName, nowMs)
        updateStatus(app, nowMs)
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

    private suspend fun updateStatus(app: TrackedApp?, nowMs: Long) {
        val status = if (app == null) {
            null
        } else {
            val dayStart = startOfTodayMs()
            val today = db.usage().intervalsFor(app.packageName, dayStart).sumOf { i ->
                val end = if (i.id == recorder.open?.id) nowMs else i.endMs
                (end - maxOf(i.startMs, dayStart)).coerceAtLeast(0)
            }
            "${app.label} · ${formatDuration(today, roundToMinutes = true)} today"
        }
        if (status != lastStatus) {
            lastStatus = status
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
        }
    }

    private fun goForeground(statusText: String?): Boolean = try {
        startForeground(NOTIFICATION_ID, buildNotification(statusText), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
