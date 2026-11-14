package com.tony3974.bingeguard.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusEvent
import kotlinx.coroutines.delay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tony3974.bingeguard.data.TrackedApp
import com.tony3974.bingeguard.db
import com.tony3974.bingeguard.enforce.toPolicy
import com.tony3974.bingeguard.policy.MINUTE_MS
import com.tony3974.bingeguard.ui.common.AppIcon
import com.tony3974.bingeguard.ui.common.BackButton
import com.tony3974.bingeguard.ui.formatDuration
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One editable number. Minutes are stored as seconds; the multiplier as-is. */
private enum class Field(val label: String, val unit: String, val help: String, val allowZero: Boolean = false) {
    SESSION_GAP("Session gap", "min", "A break shorter than this keeps the same session going."),
    FREE_HIT("Free hit", "min", "Sessions up to this long trigger nothing.", allowZero = true),
    NUDGE("Nudge at", "min", "A notification this far into a session."),
    CAP("Session cap", "min", "Blocks at this point in a session."),
    MULTIPLIER("Cooldown", "× session", "Blocked for this many times the session's length after it ends.", allowZero = true),
    ROLLING_BUDGET("Rolling budget", "min", "Most use allowed inside the rolling window."),
    ROLLING_WINDOW("Rolling window", "min", "The window the rolling budget counts over."),
    DAILY("Daily ceiling", "min", "Most use allowed per day; lifts at midnight."),
    OVERRIDE_WAIT("Override wait", "s", "How long the block screen makes you wait before typing the phrase.", allowZero = true),
    OVERRIDE_GRANT("Override grant", "min", "How long an override lets you back in."),
    ;

    fun read(a: TrackedApp): Double = when (this) {
        SESSION_GAP -> a.sessionGapSec / 60.0
        FREE_HIT -> a.freeHitSec / 60.0
        NUDGE -> a.nudgeAtSec / 60.0
        CAP -> a.sessionCapSec / 60.0
        MULTIPLIER -> a.cooldownMultiplier
        ROLLING_BUDGET -> a.rollingBudgetSec / 60.0
        ROLLING_WINDOW -> a.rollingWindowSec / 60.0
        DAILY -> a.dailyCeilingSec / 60.0
        OVERRIDE_WAIT -> a.overrideWaitSec.toDouble()
        OVERRIDE_GRANT -> a.overrideGrantSec / 60.0
    }

    fun write(a: TrackedApp, v: Double): TrackedApp {
        val sec = (v * 60).roundToInt()
        return when (this) {
            SESSION_GAP -> a.copy(sessionGapSec = sec)
            FREE_HIT -> a.copy(freeHitSec = sec)
            NUDGE -> a.copy(nudgeAtSec = sec)
            CAP -> a.copy(sessionCapSec = sec)
            MULTIPLIER -> a.copy(cooldownMultiplier = v)
            ROLLING_BUDGET -> a.copy(rollingBudgetSec = sec)
            ROLLING_WINDOW -> a.copy(rollingWindowSec = sec)
            DAILY -> a.copy(dailyCeilingSec = sec)
            OVERRIDE_WAIT -> a.copy(overrideWaitSec = v.roundToInt())
            OVERRIDE_GRANT -> a.copy(overrideGrantSec = sec)
        }
    }
}

private fun format(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

private fun valid(field: Field, text: String): Boolean {
    val v = parse(text) ?: return false
    return if (field.allowZero) v >= 0 else v > 0
}

/** Warnings for settings that are allowed but probably not meant. */
private fun warnings(a: TrackedApp): List<String> = buildList {
    if (a.nudgeAtSec >= a.sessionCapSec) add("The nudge comes at or after the cap, so it never shows.")
    if (a.freeHitSec >= a.sessionCapSec) {
        add("The free hit is as long as the cap, so a capped session is only blocked for the session gap.")
    }
    if (a.rollingBudgetSec >= a.rollingWindowSec) add("The rolling budget fills the whole window, so it never blocks.")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun AppSettingsScreen(packageName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val app by remember(packageName) { context.db.trackedApps().observe(packageName) }
        .collectAsStateWithLifecycle(initialValue = null)
    var form by remember { mutableStateOf<Map<Field, String>?>(null) }
    var enabled by remember { mutableStateOf(true) }
    var confirmRemove by remember { mutableStateOf(false) }
    var focusedField by remember { mutableStateOf<BringIntoViewRequester?>(null) }

    val loaded = app
    LaunchedEffect(loaded != null) {
        if (loaded != null && form == null) {
            form = Field.entries.associateWith { format(it.read(loaded)) }
            enabled = loaded.enabled
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.label ?: "App") },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val current = form
        if (loaded == null || current == null) return@Scaffold
        // What the form would save, if every field is valid.
        val edited = if (Field.entries.all { valid(it, current.getValue(it)) }) {
            Field.entries.fold(loaded.copy(enabled = enabled)) { a, f -> f.write(a, parse(current.getValue(f))!!) }
        } else {
            null
        }

        // With edge-to-edge the window no longer shrinks for the keyboard: pad for it here, and
        // scroll the focused field back into view once the keyboard is up.
        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(imeVisible, focusedField) {
            if (imeVisible) {
                delay(250)
                focusedField?.bringIntoView()
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(packageName, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(loaded.label, style = MaterialTheme.typography.titleLarge)
                    Text(packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enforce limits", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (enabled) "On: tracked, and blocked by the limits below." else
                            "Off: still listed, but not tracked or blocked.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Field.entries.forEach { field ->
                val text = current.getValue(field)
                val ok = valid(field, text)
                val requester = remember { BringIntoViewRequester() }
                OutlinedTextField(
                    value = text,
                    onValueChange = { form = current + (field to it) },
                    label = { Text(field.label) },
                    suffix = { Text(field.unit) },
                    supportingText = { Text(if (ok) field.help else "Enter a number${if (field.allowZero) "" else " above 0"}") },
                    isError = !ok,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(requester)
                        .onFocusEvent { if (it.isFocused) focusedField = requester },
                )
            }

            if (edited != null) {
                val policy = edited.toPolicy()
                val example = 10 * MINUTE_MS
                Text(
                    "Example: a ${formatDuration(example)} session is followed by a " +
                        "${formatDuration(policy.cooldownFor(example))} cooldown; a ${formatDuration(policy.freeHitMs)} one by none.",
                    style = MaterialTheme.typography.bodySmall,
                )
                warnings(edited).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = edited != null && edited != loaded,
                    onClick = {
                        val toSave = edited ?: return@Button
                        scope.launch {
                            context.db.trackedApps().upsert(toSave)
                            snackbar.showSnackbar("Saved. The monitor uses the new limits straight away.")
                        }
                    },
                ) { Text("Save") }
                OutlinedButton(onClick = {
                    val defaults = TrackedApp(packageName, loaded.label)
                    form = Field.entries.associateWith { format(it.read(defaults)) }
                }) { Text("Defaults") }
            }
            TextButton(onClick = { confirmRemove = true }) {
                Text("Stop tracking ${loaded.label}", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Stop tracking?") },
            text = { Text("Its limits are removed. Its history stays.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        context.db.trackedApps().delete(packageName)
                        onBack()
                    }
                }) { Text("Stop tracking") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
