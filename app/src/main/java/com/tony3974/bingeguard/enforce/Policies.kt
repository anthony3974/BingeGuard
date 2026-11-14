package com.tony3974.bingeguard.enforce

import com.tony3974.bingeguard.data.AppDatabase
import com.tony3974.bingeguard.data.TrackedApp
import com.tony3974.bingeguard.policy.BlockReason
import com.tony3974.bingeguard.policy.Decision
import com.tony3974.bingeguard.policy.Interval
import com.tony3974.bingeguard.policy.Policy
import com.tony3974.bingeguard.policy.PolicyEngine
import com.tony3974.bingeguard.ui.startOfTodayMs
import com.tony3974.bingeguard.ui.startOfTomorrowMs

/** The phrase typed to override a block. Compared ignoring case and extra spaces. */
const val OVERRIDE_PHRASE = "I choose to spend more time here"

fun phraseMatches(typed: String): Boolean =
    normalise(typed) == normalise(OVERRIDE_PHRASE)

private fun normalise(s: String) = s.trim().lowercase().split(Regex("\\s+")).joinToString(" ")

fun TrackedApp.toPolicy() = Policy(
    sessionGapMs = sessionGapSec * 1000L,
    freeHitMs = freeHitSec * 1000L,
    nudgeAtMs = nudgeAtSec * 1000L,
    sessionCapMs = sessionCapSec * 1000L,
    cooldownMultiplier = cooldownMultiplier,
    rollingWindowMs = rollingWindowSec * 1000L,
    rollingBudgetMs = rollingBudgetSec * 1000L,
    dailyCeilingMs = dailyCeilingSec * 1000L,
)

/** How far back history matters: the rolling window, cooldowns and today all fit in a day. */
const val LOOKBACK_MS = 24 * 60 * 60_000L

/**
 * Runs the policy engine for [app] from what's in Room. [openIntervalId] is the interval the
 * monitor is still recording; it's treated as ending now rather than at its last save.
 */
suspend fun decide(db: AppDatabase, app: TrackedApp, nowMs: Long, openIntervalId: Long? = null): Decision {
    val dayStart = startOfTodayMs()
    val since = minOf(dayStart, nowMs - LOOKBACK_MS)
    val intervals = db.usage().intervalsFor(app.packageName, since).map {
        Interval(it.startMs, if (it.id == openIntervalId) nowMs else it.endMs)
    }
    val overrides = db.events().overridesFor(app.packageName, nowMs - LOOKBACK_MS)
        .map { Interval(it.atMs, it.grantedUntilMs) }
    return PolicyEngine.evaluate(app.toPolicy(), intervals, nowMs, dayStart, startOfTomorrowMs(), overrides)
}

fun BlockReason.title(): String = when (this) {
    BlockReason.SESSION_CAP -> "Session over"
    BlockReason.COOLDOWN -> "Cooling down"
    BlockReason.ROLLING_BUDGET -> "Hourly budget used"
    BlockReason.DAILY_CEILING -> "Done for today"
}

/** Short form for lists: "cooldown", "hourly budget". */
fun BlockReason.shortName(): String = when (this) {
    BlockReason.SESSION_CAP -> "session cap"
    BlockReason.COOLDOWN -> "cooldown"
    BlockReason.ROLLING_BUDGET -> "hourly budget"
    BlockReason.DAILY_CEILING -> "daily limit"
}

fun blockReasonOf(name: String): BlockReason? = BlockReason.entries.firstOrNull { it.name == name }
