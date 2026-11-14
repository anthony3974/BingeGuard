package com.tony3974.bingeguard.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tony3974.bingeguard.apps.AppCatalog
import com.tony3974.bingeguard.data.TrackedApp
import com.tony3974.bingeguard.db
import com.tony3974.bingeguard.enforce.blockReasonOf
import com.tony3974.bingeguard.enforce.shortName
import com.tony3974.bingeguard.enforce.toPolicy
import com.tony3974.bingeguard.policy.Interval
import com.tony3974.bingeguard.policy.Policy
import com.tony3974.bingeguard.policy.PolicyEngine
import com.tony3974.bingeguard.policy.Session
import com.tony3974.bingeguard.ui.common.AppIcon
import com.tony3974.bingeguard.ui.common.BackButton
import com.tony3974.bingeguard.ui.formatDate
import com.tony3974.bingeguard.ui.formatDuration
import com.tony3974.bingeguard.ui.formatTime
import com.tony3974.bingeguard.ui.startOfTodayMs

private const val DAYS = 14
private val TABS = listOf("Sessions", "Overrides", "Blocks")

private data class SessionRow(val packageName: String, val session: Session, val policy: Policy)

/** The last two weeks: sessions (rebuilt from the recorded intervals), overrides and blocks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val since = remember { startOfTodayMs() - (DAYS - 1) * 24 * 60 * 60_000L }
    val db = context.db
    val apps by remember { db.trackedApps().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val intervals by remember { db.usage().observeSince(since) }.collectAsStateWithLifecycle(emptyList())
    val overrides by remember { db.events().observeOverrides(since) }.collectAsStateWithLifecycle(emptyList())
    val blocks by remember { db.events().observeBlocks(since) }.collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val byPackage = apps.associateBy(TrackedApp::packageName)
    val labels = remember(apps, intervals, overrides, blocks) {
        (intervals.map { it.packageName } + overrides.map { it.packageName } + blocks.map { it.packageName })
            .distinct()
            .associateWith { byPackage[it]?.label ?: AppCatalog.label(context, it) }
    }
    val sessions = remember(intervals, apps) {
        intervals.groupBy { it.packageName }.flatMap { (pkg, list) ->
            val policy = byPackage[pkg]?.toPolicy() ?: Policy()
            PolicyEngine.sessions(list.map { Interval(it.startMs, it.endMs) }, policy.sessionGapMs)
                .map { SessionRow(pkg, it, policy) }
        }.sortedByDescending { it.session.startMs }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("History") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                when (tab) {
                    0 -> byDay(sessions, { it.session.startMs }, "No sessions in the last $DAYS days.") { row ->
                        val s = row.session
                        val cooldown = row.policy.cooldownFor(s.activeMs)
                        val note = when {
                            s.activeMs >= row.policy.sessionCapMs -> "hit the cap · ${formatDuration(cooldown)} cooldown"
                            cooldown > 0 -> "${formatDuration(cooldown)} cooldown"
                            else -> "free hit"
                        }
                        EventRow(
                            row.packageName,
                            labels[row.packageName] ?: row.packageName,
                            "${formatTime(s.startMs)}–${formatTime(s.endMs)} · $note",
                            formatDuration(s.activeMs),
                        )
                    }
                    1 -> byDay(overrides, { it.atMs }, "No overrides. Nice.") { o ->
                        val reason = blockReasonOf(o.blockReason)?.shortName() ?: o.blockReason
                        EventRow(
                            o.packageName,
                            labels[o.packageName] ?: o.packageName,
                            "${formatTime(o.atMs)} · overrode $reason · waited ${o.waitedSec} s · " +
                                "until ${formatTime(o.grantedUntilMs)}",
                            null,
                        )
                    }
                    else -> byDay(blocks, { it.atMs }, "No blocks in the last $DAYS days.") { b ->
                        val reason = blockReasonOf(b.reason)?.shortName() ?: b.reason
                        EventRow(
                            b.packageName,
                            labels[b.packageName] ?: b.packageName,
                            "${formatTime(b.atMs)} · $reason · until ${formatTime(b.untilMs)}",
                            null,
                        )
                    }
                }
            }
        }
    }
}

/** Rows under a header for each day, newest first. */
private fun <T> LazyListScope.byDay(
    rows: List<T>,
    timeOf: (T) -> Long,
    empty: String,
    row: @Composable (T) -> Unit,
) {
    if (rows.isEmpty()) {
        item { Text(empty, Modifier.padding(16.dp)) }
        return
    }
    rows.groupBy { formatDate(timeOf(it)) }.forEach { (day, dayRows) ->
        item(key = "day-$day") {
            Text(
                day,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        items(dayRows) { r ->
            row(r)
            HorizontalDivider(Modifier.padding(start = 68.dp))
        }
    }
}

@Composable
private fun EventRow(packageName: String, label: String, detail: String, trailing: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(packageName, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleMedium)
    }
}
