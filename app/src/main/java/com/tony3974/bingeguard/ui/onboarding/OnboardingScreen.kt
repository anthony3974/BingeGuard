package com.tony3974.bingeguard.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tony3974.bingeguard.permissions.PermissionIntents
import com.tony3974.bingeguard.permissions.PermissionStatus

private data class Step(
    val title: String,
    val why: String,
    val granted: Boolean,
    val statusText: String,
    val actionLabel: String?,
    val action: (() -> Unit)?,
    val note: String? = null,
)

/**
 * One card per permission: what it's for, whether it's granted, and a button to the right
 * Settings screen. Statuses re-read every time the app comes back from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++
    }
    val status = remember(refresh) { PermissionStatus.read(context) }

    val steps = listOf(
        Step(
            title = "Usage Access",
            why = "Lets Binge Guard see which app is on screen (UsageStatsManager resume/pause events). " +
                "Nothing leaves the phone.",
            granted = status.usageAccess,
            statusText = if (status.usageAccess) "Granted" else "Not granted",
            actionLabel = "Open Usage Access",
            action = { PermissionIntents.openUsageAccess(context) },
            note = "If the switch is greyed out as a restricted setting: App info, ⋮ menu, " +
                "\"Allow restricted settings\", then try again.",
        ),
        Step(
            title = "Display over other apps",
            why = "Draws the block screen on top of a blocked app, and lets the monitor send you back to the home screen.",
            granted = status.overlay,
            statusText = if (status.overlay) "Granted" else "Not granted",
            actionLabel = "Open setting",
            action = { PermissionIntents.openOverlay(context) },
            note = "Android opens the list of apps: pick Binge Guard there and turn it on.",
        ),
        Step(
            title = "Notifications",
            why = "Shows the monitor's ongoing notification (Android requires one for a foreground service) and the " +
                "6-minute nudge.",
            granted = status.notifications,
            statusText = if (status.notifications) "Granted" else "Not granted",
            actionLabel = "Allow",
            action = {
                if (askedNotifications) {
                    PermissionIntents.openNotificationSettings(context)
                } else {
                    askedNotifications = true
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        ),
        Step(
            title = "Foreground service (special use)",
            why = "The monitor runs as a foreground service of type specialUse. Android 17 has no type for watching " +
                "app usage; dataSync would be cut off after 6 hours a day and can't start at boot, while specialUse " +
                "has no time limit, needs no runtime prerequisite, and may start from BOOT_COMPLETED.",
            granted = status.foregroundService,
            statusText = if (status.foregroundService) "Granted at install" else "Missing (reinstall)",
            actionLabel = null,
            action = null,
        ),
        Step(
            title = "See all installed apps",
            why = "QUERY_ALL_PACKAGES, so the app picker lists everything, including modded builds installed " +
                "under their own package name.",
            granted = status.queryAllPackages,
            statusText = if (status.queryAllPackages) {
                "Granted at install · ${status.visibleLaunchableApps} launchable apps visible"
            } else {
                "Missing (reinstall)"
            },
            actionLabel = null,
            action = null,
        ),
        Step(
            title = "Unrestricted battery",
            why = "Keeps Android from stopping the monitor, and lets it restart itself after its process is killed " +
                "(Android 17 adds memory limits that can kill long-running apps).",
            granted = status.batteryUnrestricted,
            statusText = if (status.batteryUnrestricted) "Unrestricted" else "Optimized",
            actionLabel = "Allow",
            action = { PermissionIntents.requestBatteryExemption(context) },
        ),
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("Set up Binge Guard") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Binge Guard needs these to watch your chosen apps and block long sessions. " +
                        "Each button opens the right Settings page; come back here when done.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(steps, key = { it.title }) { step -> StepCard(step) }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { PermissionIntents.openAppDetails(context) }) { Text("App info") }
                        Button(onClick = onDone) {
                            Text(if (status.allGranted) "Continue" else "Continue anyway")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(step: Step) {
    val colors = if (step.granted) {
        CardDefaults.cardColors()
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(step.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    (if (step.granted) "✓ " else "✗ ") + step.statusText,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(step.why, style = MaterialTheme.typography.bodyMedium)
            if (!step.granted && step.note != null) {
                Text(step.note, style = MaterialTheme.typography.bodySmall)
            }
            if (!step.granted && step.action != null && step.actionLabel != null) {
                FilledTonalButton(onClick = step.action) { Text(step.actionLabel) }
            }
        }
    }
}
