package com.tony3974.bingeguard.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tony3974.bingeguard.permissions.PermissionStatus

/**
 * Restarts the monitor after a reboot and after an update (installing over the top kills the
 * running process). Both broadcasts are exemptions that allow starting a foreground service
 * from the background, and specialUse isn't one of the types barred from BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (PermissionStatus.hasUsageAccess(context)) MonitorService.start(context)
            }
        }
    }
}
