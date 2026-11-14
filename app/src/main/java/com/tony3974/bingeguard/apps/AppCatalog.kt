package com.tony3974.bingeguard.apps

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val isSystem: Boolean,
)

/** Installed apps for the picker, their icons, and "which app did I just use". */
object AppCatalog {
    private val iconCache = LruCache<String, ImageBitmap>(200)

    /** Every package with a launcher activity, except Binge Guard itself. Needs QUERY_ALL_PACKAGES. */
    fun launchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { pkg ->
                try {
                    val info = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                    val appInfo = info.applicationInfo ?: return@mapNotNull null
                    InstalledApp(
                        packageName = pkg,
                        label = appInfo.loadLabel(pm).toString(),
                        versionName = info.versionName,
                        isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, InstalledApp::label).thenBy { it.packageName })
    }

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /** Loads off the main thread; cached. Null if the app is gone. */
    fun icon(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        iconCache.get(packageName)?.let { return it }
        return try {
            val bitmap = context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap()
            iconCache.put(packageName, bitmap)
            bitmap
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * The most recently used app other than Binge Guard, the launcher and System UI, from the
     * last [lookbackMs] of usage events: "the app I was just in". Needs Usage Access.
     */
    fun lastUsedApp(context: Context, lookbackMs: Long = 15 * 60_000L): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - lookbackMs, now) ?: return null
        val skip = setOf(context.packageName, "com.android.systemui") + launcherPackages(context)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.packageName !in skip) {
                last = event.packageName
            }
        }
        return last
    }

    fun launcherPackages(context: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName }
            .toSet()
    }
}
