package com.tony3974.bingeguard.enforce

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tony3974.bingeguard.data.TrackedApp
import com.tony3974.bingeguard.policy.BlockReason
import com.tony3974.bingeguard.ui.common.AppIcon
import com.tony3974.bingeguard.ui.formatClock
import com.tony3974.bingeguard.ui.formatDuration
import com.tony3974.bingeguard.ui.formatTime
import kotlinx.coroutines.delay

/** What the block screen shows, and what its buttons do. */
data class BlockUi(
    val app: TrackedApp,
    val reason: BlockReason,
    val untilMs: Long,
    /** Length of the session that was blocked or that set off the cooldown. */
    val sessionMs: Long?,
    val todayMs: Long,
    val rollingMs: Long,
    val onClose: () -> Unit,
    val onOverride: (waitedSec: Int, phrase: String) -> Unit,
) {
    val packageName: String get() = app.packageName
}

private enum class OverrideStep { NONE, WAITING, TYPING }

@Composable
fun BlockScreen(ui: BlockUi) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    // A new block (another app, or a new end time) starts the override over.
    key(ui.packageName, ui.untilMs) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                AppIcon(ui.packageName, size = 72.dp)
                Text(ui.app.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    ui.reason.title(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(explanation(ui), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)

                val remaining = ui.untilMs - now
                if (remaining > 0) {
                    Text(formatClock(remaining), style = MaterialTheme.typography.displayMedium)
                    Text(
                        if (ui.reason == BlockReason.DAILY_CEILING) "Opens again at midnight" else "Opens again at ${formatTime(ui.untilMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("You can open it again now.", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Today ${formatDuration(ui.todayMs)} · last hour ${formatDuration(ui.rollingMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(8.dp))
                Button(onClick = ui.onClose, modifier = Modifier.widthIn(min = 200.dp)) { Text("Close") }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OverrideSection(ui, now)
            }
        }
    }
}

private fun explanation(ui: BlockUi): String {
    val a = ui.app
    val session = ui.sessionMs?.let { formatDuration(it) }
    return when (ui.reason) {
        BlockReason.SESSION_CAP ->
            "That's ${formatDuration(a.sessionCapSec * 1000L)} in one go, the cap for ${a.label}."
        BlockReason.COOLDOWN ->
            (if (session != null) "Your last session ran $session. " else "") +
                "Short check-ins are fine again after the cooldown."
        BlockReason.ROLLING_BUDGET ->
            "${formatDuration(ui.rollingMs)} in the last hour; the budget is ${formatDuration(a.rollingBudgetSec * 1000L)} " +
                "per ${formatDuration(a.rollingWindowSec * 1000L)}."
        BlockReason.DAILY_CEILING ->
            "${formatDuration(ui.todayMs)} today; the daily limit is ${formatDuration(a.dailyCeilingSec * 1000L)}."
    }
}

@Composable
private fun OverrideSection(ui: BlockUi, now: Long) {
    var step by remember { mutableStateOf(OverrideStep.NONE) }
    var waitStartedAt by remember { mutableLongStateOf(0L) }
    var typed by remember { mutableStateOf("") }
    val waitMs = ui.app.overrideWaitSec * 1000L
    val grant = formatDuration(ui.app.overrideGrantSec * 1000L)

    when (step) {
        OverrideStep.NONE -> TextButton(onClick = {
            waitStartedAt = System.currentTimeMillis()
            step = if (waitMs > 0) OverrideStep.WAITING else OverrideStep.TYPING
        }) { Text("I need it anyway…") }

        OverrideStep.WAITING -> {
            val left = waitMs - (now - waitStartedAt)
            LaunchedEffect(left <= 0) { if (left <= 0) step = OverrideStep.TYPING }
            Text("Take a breath. Is this worth it?", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { ((now - waitStartedAt).toFloat() / waitMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(0.7f),
            )
            Text(formatClock(left.coerceAtLeast(0)), style = MaterialTheme.typography.bodyMedium)
        }

        OverrideStep.TYPING -> {
            Text(
                "Type “$OVERRIDE_PHRASE” to open ${ui.app.label} for $grant. This is logged.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = phraseMatches(typed),
                onClick = {
                    val waited = ((System.currentTimeMillis() - waitStartedAt) / 1000).toInt()
                    ui.onOverride(waited, typed.trim())
                },
            ) { Text("Open for $grant") }
        }
    }
}
