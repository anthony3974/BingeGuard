package com.tony3974.bingeguard.policy

import kotlin.math.roundToLong

const val SECOND_MS = 1_000L
const val MINUTE_MS = 60 * SECOND_MS

/** One app's limits. Defaults are the app-wide defaults; every number is editable per app. */
data class Policy(
    /** A gap shorter than this keeps the same session going. */
    val sessionGapMs: Long = 2 * MINUTE_MS,
    /** Sessions up to this long trigger nothing (no nudge, no cooldown). */
    val freeHitMs: Long = 5 * MINUTE_MS,
    val nudgeAtMs: Long = 6 * MINUTE_MS,
    val sessionCapMs: Long = 8 * MINUTE_MS,
    /** Cooldown after a session = its length × this (only for sessions longer than the free hit). */
    val cooldownMultiplier: Double = 1.5,
    val rollingWindowMs: Long = 60 * MINUTE_MS,
    val rollingBudgetMs: Long = 15 * MINUTE_MS,
    val dailyCeilingMs: Long = 90 * MINUTE_MS,
    /**
     * Starting a new session needs at least this much rolling and daily budget left, so a lifted
     * budget block gives a real check-in instead of blocking again a few seconds later.
     */
    val minStartAllowanceMs: Long = 1 * MINUTE_MS,
) {
    fun cooldownFor(sessionActiveMs: Long): Long =
        if (sessionActiveMs <= freeHitMs) 0 else (sessionActiveMs * cooldownMultiplier).roundToLong()
}

/** A stretch of time the app was on screen. */
data class Interval(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/** Foreground intervals joined by gaps shorter than the session gap. [activeMs] excludes the gaps. */
data class Session(val startMs: Long, val endMs: Long, val activeMs: Long)

enum class BlockReason { SESSION_CAP, COOLDOWN, ROLLING_BUDGET, DAILY_CEILING }

data class Block(val reason: BlockReason, val untilMs: Long)

enum class Verdict { ALLOW, NUDGE, BLOCK }

data class Decision(
    val verdict: Verdict,
    /** Every limit currently hit, latest-ending first. Non-empty when blocked or overridden. */
    val blocks: List<Block>,
    /** End of the override in force now, or null. When [blocks] is non-empty it is what lets you in. */
    val overrideUntilMs: Long?,
    /** The session that using the app now continues, or null if using it now starts a new one. */
    val session: Session?,
    val rollingUsedMs: Long,
    val todayUsedMs: Long,
) {
    /** The block that lasts longest: what the block screen shows. */
    val block: Block? get() = blocks.firstOrNull()
    val isOverridden: Boolean get() = overrideUntilMs != null && blocks.isNotEmpty()
}

/**
 * Decides whether an app may be on screen now. Stateless: everything comes from the app's
 * recorded foreground intervals, so it gives the same answer after a restart.
 *
 * Intervals are only recorded while the app is allowed on screen, so time spent bouncing off
 * the block screen never extends a session or its cooldown.
 */
object PolicyEngine {

    fun sessions(intervals: List<Interval>, gapMs: Long): List<Session> {
        val result = mutableListOf<Session>()
        var start = 0L
        var end = 0L
        var active = 0L
        var open = false
        for (i in intervals.filter { it.endMs >= it.startMs }.sortedBy { it.startMs }) {
            if (open && i.startMs - end < gapMs) {
                active += (i.endMs - maxOf(i.startMs, end)).coerceAtLeast(0)
                end = maxOf(end, i.endMs)
            } else {
                if (open) result += Session(start, end, active)
                start = i.startMs
                end = i.endMs
                active = i.lengthMs
                open = true
            }
        }
        if (open) result += Session(start, end, active)
        return result
    }

    /** Time inside [fromMs, toMs) covered by [intervals]. */
    fun usedBetween(intervals: List<Interval>, fromMs: Long, toMs: Long): Long =
        intervals.sumOf { (minOf(it.endMs, toMs) - maxOf(it.startMs, fromMs)).coerceAtLeast(0) }

    /**
     * @param intervals the app's foreground intervals (at least the last 24 h and today); an
     *   interval still open should end at [nowMs].
     * @param dayStartMs local midnight today; [nextDayStartMs] local midnight tonight (they're
     *   passed in so time zones and DST stay out of the engine).
     * @param overrides each override as (granted at, granted until).
     */
    fun evaluate(
        policy: Policy,
        intervals: List<Interval>,
        nowMs: Long,
        dayStartMs: Long,
        nextDayStartMs: Long,
        overrides: List<Interval> = emptyList(),
    ): Decision {
        val past = intervals
            .filter { it.startMs <= nowMs }
            .map { if (it.endMs > nowMs) Interval(it.startMs, nowMs) else it }
        val all = sessions(past, policy.sessionGapMs)
        val current = all.lastOrNull()?.takeIf { nowMs - it.endMs < policy.sessionGapMs }
        val starting = current == null
        val blocks = mutableListOf<Block>()

        // Session cap: the current session has run its course. It ends here, so the cooldown
        // counts from its last moment on screen (and lasts at least until it can't continue).
        if (current != null && current.activeMs >= policy.sessionCapMs) {
            val wait = maxOf(policy.cooldownFor(current.activeMs), policy.sessionGapMs)
            blocks += Block(BlockReason.SESSION_CAP, current.endMs + wait)
        }

        // Cooldowns after sessions that have ended.
        val cooldownUntil = all
            .filter { it != current }
            .maxOfOrNull { it.endMs + policy.cooldownFor(it.activeMs) }
        if (cooldownUntil != null && cooldownUntil > nowMs) {
            blocks += Block(BlockReason.COOLDOWN, cooldownUntil)
        }

        // Rolling budget.
        val rollingUsed = usedBetween(past, nowMs - policy.rollingWindowMs, nowMs)
        if (overBudget(policy.rollingBudgetMs - rollingUsed, starting, policy)) {
            blocks += Block(BlockReason.ROLLING_BUDGET, rollingFreesUpAt(policy, past, nowMs))
        }

        // Daily ceiling.
        val todayUsed = usedBetween(past, dayStartMs, nowMs)
        if (overBudget(policy.dailyCeilingMs - todayUsed, starting, policy)) {
            blocks += Block(BlockReason.DAILY_CEILING, nextDayStartMs)
        }

        blocks.sortByDescending { it.untilMs }
        val overrideUntil = overrides
            .filter { it.startMs <= nowMs && nowMs < it.endMs }
            .maxOfOrNull { it.endMs }

        val verdict = when {
            blocks.isNotEmpty() && overrideUntil == null -> Verdict.BLOCK
            blocks.isNotEmpty() -> Verdict.ALLOW
            current != null && current.activeMs >= policy.nudgeAtMs -> Verdict.NUDGE
            else -> Verdict.ALLOW
        }
        return Decision(verdict, blocks, overrideUntil, current, rollingUsed, todayUsed)
    }

    /** A running session stops at zero; a new one needs [Policy.minStartAllowanceMs] left. */
    private fun overBudget(remainingMs: Long, starting: Boolean, policy: Policy): Boolean =
        if (starting) remainingMs < policy.minStartAllowanceMs else remainingMs <= 0

    /**
     * The first moment, with no further use, when a new session may start under the rolling
     * budget: when usage inside the window has dropped to budget minus the start allowance.
     */
    private fun rollingFreesUpAt(policy: Policy, past: List<Interval>, nowMs: Long): Long {
        val threshold = (policy.rollingBudgetMs - policy.minStartAllowanceMs).coerceAtLeast(0)
        var lo = nowMs
        var hi = nowMs + policy.rollingWindowMs // by then the window holds no past use at all
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            if (usedBetween(past, mid - policy.rollingWindowMs, nowMs) <= threshold) hi = mid else lo = mid + 1
        }
        return lo
    }
}
