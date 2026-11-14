package com.tony3974.bingeguard.ui.home

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tony3974.bingeguard.data.UsageInterval
import com.tony3974.bingeguard.db
import com.tony3974.bingeguard.monitor.MonitorService
import com.tony3974.bingeguard.ui.common.AppIcon
import com.tony3974.bingeguard.ui.formatDuration
import com.tony3974.bingeguard.ui.startOfTodayMs
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onAddApps: () -> Unit, onPermissions: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val dayStart = startOfTodayMs()
    val apps by remember { context.db.trackedApps().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val intervals by remember(dayStart) { context.db.usage().observeSince(dayStart) }
        .collectAsStateWithLifecycle(emptyList())
    val running by MonitorService.running.collectAsStateWithLifecycle()
    val foreground by MonitorService.foreground.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Binge Guard") },
                actions = {
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
            item {
                Card(
                    colors = if (running) CardDefaults.cardColors() else
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (running) "Monitor running" else "Monitor not running",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (running) "Last app on screen: ${foreground ?: "none"}" else
                                "It needs Usage Access and the foreground service permission. Check Permissions.",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (running) FontFamily.Monospace else null,
                        )
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Today", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(onClick = onAddApps) { Text("Choose apps") }
                }
            }
            if (apps.isEmpty()) {
                item { Text("No apps tracked yet. Tap Choose apps to pick the ones to watch.") }
            }
            items(apps, key = { it.packageName }) { app ->
                val today = usageMs(intervals.filter { it.packageName == app.packageName }, dayStart, now)
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppIcon(app.packageName)
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        Text(formatDuration(today), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** Time inside [fromMs, toMs] covered by the intervals. */
fun usageMs(intervals: List<UsageInterval>, fromMs: Long, toMs: Long): Long =
    intervals.sumOf { (minOf(it.endMs, toMs) - maxOf(it.startMs, fromMs)).coerceAtLeast(0) }
