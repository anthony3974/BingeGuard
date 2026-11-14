package com.tony3974.bingeguard.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tony3974.bingeguard.data.TrackedApp
import com.tony3974.bingeguard.db
import com.tony3974.bingeguard.enforce.decide
import com.tony3974.bingeguard.enforce.shortName
import com.tony3974.bingeguard.monitor.MonitorService
import com.tony3974.bingeguard.permissions.PermissionStatus
import com.tony3974.bingeguard.policy.Decision
import com.tony3974.bingeguard.policy.MINUTE_MS
import com.tony3974.bingeguard.policy.Verdict
import com.tony3974.bingeguard.ui.common.AppIcon
import com.tony3974.bingeguard.ui.formatClock
import com.tony3974.bingeguard.ui.formatDuration
import com.tony3974.bingeguard.ui.formatTime
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddApps: () -> Unit,
    onOpenApp: (String) -> Unit,
    onHistory: () -> Unit,
    onPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val apps by remember { context.db.trackedApps().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val running by MonitorService.running.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val permissions = remember(refresh) { PermissionStatus.read(context) }

    // Each app's verdict "if you opened it now", re-run every second for the countdowns.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var decisions by remember { mutableStateOf<Map<String, Decision>>(emptyMap()) }
    LaunchedEffect(apps) {
        while (true) {
            now = System.currentTimeMillis()
            decisions = apps.associate { it.packageName to decide(context.db, it, now) }
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Binge Guard") },
                actions = {
                    TextButton(onClick = onHistory) { Text("History") }
                    TextButton(onClick = onPermissions) { Text("Permissions") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val problem = when {
                !permissions.canTrack -> "Not tracking: Usage Access is missing."
                !running -> "The monitor isn't running. Opening this screen starts it; if it stays off, check Permissions."
                !permissions.overlay -> "Tracking, but blocks can't be enforced: Display over other apps is off."
                !permissions.batteryUnrestricted -> "Battery is optimized: Android may stop the monitor."
                !permissions.notifications -> "Notifications are off: no nudges."
                else -> null
            }
            if (problem != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(problem, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = onPermissions) { Text("Fix") }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Today", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = onAddApps) { Text("Choose apps") }
                }
            }
            if (apps.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No apps tracked yet.")
                        Button(onClick = onAddApps) { Text("Pick apps to watch") }
                    }
                }
            }
            // Blocked apps first, so current cooldowns are at the top.
            val sorted = apps.sortedByDescending { decisions[it.packageName]?.verdict == Verdict.BLOCK }
            items(sorted, key = { it.packageName }) { app ->
                AppCard(app, decisions[app.packageName], now, onClick = { onOpenApp(app.packageName) })
            }
        }
    }
}

@Composable
private fun AppCard(app: TrackedApp, decision: Decision?, now: Long, onClick: () -> Unit) {
    val blocked = decision?.verdict == Verdict.BLOCK && app.enabled
    Card(
        colors = if (blocked) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else
            CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(app.packageName)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                Text(
                    formatDuration(decision?.todayUsedMs ?: 0, roundToMinutes = true),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (decision == null) return@Column
            Text(statusText(app, decision, now), style = MaterialTheme.typography.bodyMedium)
            Meter("Today", decision.todayUsedMs, app.dailyCeilingSec * 1000L)
            Meter("Last ${formatDuration(app.rollingWindowSec * 1000L)}", decision.rollingUsedMs, app.rollingBudgetSec * 1000L)
        }
    }
}

private fun statusText(app: TrackedApp, d: Decision, now: Long): String {
    if (!app.enabled) return "Paused: not enforced"
    val block = d.block
    return when {
        d.verdict == Verdict.BLOCK && block != null ->
            "Blocked (${block.reason.shortName()}) · ${formatClock(block.untilMs - now)} left · until ${formatTime(block.untilMs)}"
        d.isOverridden -> "Override until ${formatTime(d.overrideUntilMs!!)}"
        d.session != null -> {
            val s = d.session!!
            val resumeBy = s.endMs + app.sessionGapSec * 1000L - now
            "Session ${s.activeMs / MINUTE_MS} of ${app.sessionCapSec / 60} min; reopen within ${formatClock(resumeBy)} " +
                "to continue it"
        }
        else -> "Available: a check-in up to ${formatDuration(app.freeHitSec * 1000L)} is free"
    }
}

@Composable
private fun Meter(label: String, usedMs: Long, limitMs: Long) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(
                "${formatDuration(usedMs, roundToMinutes = true)} / ${formatDuration(limitMs, roundToMinutes = true)}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        LinearProgressIndicator(
            progress = { if (limitMs <= 0) 1f else (usedMs.toFloat() / limitMs).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
