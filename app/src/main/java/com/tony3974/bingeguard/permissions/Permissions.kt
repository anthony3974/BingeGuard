package com.tony3974.bingeguard.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/** A snapshot of every permission Binge Guard needs. Re-read it whenever the app resumes. */
data class PermissionStatus(
    val usageAccess: Boolean,
    val overlay: Boolean,
    val notifications: Boolean,
    val foregroundService: Boolean,
    val queryAllPackages: Boolean,
    /** How many launchable packages the app can see; a handful means package visibility is filtered. */
    val visibleLaunchableApps: Int,
    val batteryUnrestricted: Boolean,
) {
    /** Enough to track usage: the monitor can run without the rest, but can't block. */
    val canTrack: Boolean get() = usageAccess && foregroundService

    val allGranted: Boolean
        get() = usageAccess && overlay && notifications && foregroundService && queryAllPackages && batteryUnrestricted

    companion object {
        fun read(context: Context) = PermissionStatus(
            usageAccess = hasUsageAccess(context),
            overlay = Settings.canDrawOverlays(context),
            notifications = granted(context, Manifest.permission.POST_NOTIFICATIONS),
            foregroundService = granted(context, Manifest.permission.FOREGROUND_SERVICE) &&
                granted(context, Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE),
            queryAllPackages = granted(context, Manifest.permission.QUERY_ALL_PACKAGES),
            visibleLaunchableApps = launchableAppCount(context),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
        )

        private fun granted(context: Context, permission: String) =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        /** Usage Access is an app op, not a runtime permission. MODE_DEFAULT falls back to the permission. */
        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return if (mode == AppOpsManager.MODE_DEFAULT) {
                granted(context, Manifest.permission.PACKAGE_USAGE_STATS)
            } else {
                mode == AppOpsManager.MODE_ALLOWED
            }
        }

        private fun launchableAppCount(context: Context): Int {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return context.packageManager
                .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .map { it.activityInfo.packageName }
                .distinct()
                .size
        }
    }
}

/** The Settings screens where each permission is granted. */
object PermissionIntents {
    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    /** Opens Binge Guard's own Usage Access page, falling back to the list if this build lacks it. */
    fun openUsageAccess(context: Context) {
        val specific = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(packageUri(context))
        startOr(context, specific, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    /** Since Android 11 this opens the list of apps, not Binge Guard's page: the user picks it there. */
    fun openOverlay(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(Uri.parse("package:${context.packageName}"))
        startOr(context, intent, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        startOr(context, intent, appDetails(context))
    }

    /** The system's "Let app always run in background?" dialog. */
    fun requestBatteryExemption(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        startOr(context, intent, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun openAppDetails(context: Context) = startOr(context, appDetails(context), Intent(Settings.ACTION_SETTINGS))

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri(context))

    private fun startOr(context: Context, intent: Intent, fallback: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
