package com.tony3974.bingeguard.ui.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tony3974.bingeguard.apps.AppCatalog
import com.tony3974.bingeguard.apps.InstalledApp
import com.tony3974.bingeguard.data.TrackedApp
import com.tony3974.bingeguard.db
import com.tony3974.bingeguard.ui.common.AppIcon
import com.tony3974.bingeguard.ui.common.BackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every launchable app with its icon, label and package name (always shown, since a stock and
 * a modded build can share a name). Ticking one starts tracking it with the default limits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val installed by produceState<List<InstalledApp>?>(null) {
        value = withContext(Dispatchers.IO) { AppCatalog.launchableApps(context) }
    }
    val trackedFlow = remember { context.db.trackedApps().observeAll() }
    val tracked by trackedFlow.collectAsStateWithLifecycle(emptyList())
    val trackedPackages = tracked.map { it.packageName }.toSet()
    var query by rememberSaveable { mutableStateOf("") }
    // A detected app with no launcher icon still gets a row.
    var detectedExtra by rememberSaveable { mutableStateOf<String?>(null) }

    val apps = installed
    val rows = remember(apps, query, detectedExtra, tracked) {
        val base = apps.orEmpty().toMutableList()
        val extraPackages = (tracked.map { it.packageName } + listOfNotNull(detectedExtra))
            .filter { pkg -> base.none { it.packageName == pkg } }
            .distinct()
        extraPackages.forEach { pkg ->
            val label = tracked.firstOrNull { it.packageName == pkg }?.label ?: AppCatalog.label(context, pkg)
            base += InstalledApp(pkg, label, versionName = null, isSystem = false)
        }
        val q = query.trim()
        if (q.isEmpty()) {
            base
        } else {
            base.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose apps") },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search name or package") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val pkg = withContext(Dispatchers.IO) { AppCatalog.lastUsedApp(context) }
                            if (pkg == null) {
                                snackbar.showSnackbar("No recent app found. Open the app, come back here, and tap Detect.")
                            } else {
                                detectedExtra = pkg
                                query = pkg
                                snackbar.showSnackbar("Detected $pkg")
                            }
                        }
                    }) { Text("Detect current app") }
                    Text(
                        "Open the app, come back, tap Detect.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (apps == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(rows, key = { it.packageName }) { app ->
                        val checked = app.packageName in trackedPackages
                        AppRow(app, checked) {
                            scope.launch {
                                if (checked) {
                                    context.db.trackedApps().delete(app.packageName)
                                } else {
                                    context.db.trackedApps().upsert(
                                        TrackedApp(app.packageName, app.label, addedAtMs = System.currentTimeMillis()),
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app.packageName)
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium)
            Text(
                app.packageName + (app.versionName?.let { " · v$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}
