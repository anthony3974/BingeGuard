package com.tony3974.bingeguard.policy

import com.tony3974.bingeguard.policy.BlockReason.COOLDOWN
import com.tony3974.bingeguard.policy.BlockReason.DAILY_CEILING
import com.tony3974.bingeguard.policy.BlockReason.ROLLING_BUDGET
import com.tony3974.bingeguard.policy.BlockReason.SESSION_CAP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {
    private val defaults = Policy()

    // A day starting at DAY, with "now" measured in minutes after 10:00.
    private val day = 1_800_000_000_000L
    private val nextDay = day + 24 * 60 * MINUTE_MS
    private val t0 = day + 10 * 60 * MINUTE_MS

    private fun at(min: Double) = t0 + (min * MINUTE_MS).toLong()
    private fun at(min: Int) = at(min.toDouble())
    private fun use(from: Number, to: Number) = Interval(at(from.toDouble()), at(to.toDouble()))

    private fun eval(
        now: Number,
        vararg intervals: Interval,
        policy: Policy = defaults,
        overrides: List<Interval> = emptyList(),
    ) = PolicyEngine.evaluate(policy, intervals.toList(), at(now.toDouble()), day, nextDay, overrides)

    // ---- Sessions ----

    @Test fun `no history allows and starts a new session`() {
        val d = eval(0)
        assertEquals(Verdict.ALLOW, d.verdict)
        assertNull(d.session)
        assertTrue(d.blocks.isEmpty())
    }

    @Test fun `a gap under 2 minutes is the same session, and gaps don't count toward its length`() {
        val s = PolicyEngine.sessions(listOf(use(0, 4), use(5.9, 8)), 2 * MINUTE_MS)
        assertEquals(1, s.size)
        assertEquals(at(0), s[0].startMs)
        assertEquals(at(8), s[0].endMs)
        assertEquals((6.1 * MINUTE_MS).toLong(), s[0].activeMs)
    }

    @Test fun `a gap of exactly 2 minutes starts a new session`() {
        val s = PolicyEngine.sessions(listOf(use(0, 4), use(6, 8)), 2 * MINUTE_MS)
        assertEquals(2, s.size)
        assertEquals(4 * MINUTE_MS, s[0].activeMs)
        assertEquals(2 * MINUTE_MS, s[1].activeMs)
    }

    @Test fun `sessions sort their input and ignore backwards intervals`() {
        val s = PolicyEngine.sessions(listOf(use(10, 12), Interval(at(5), at(4)), use(0, 1)), 2 * MINUTE_MS)
        assertEquals(listOf(at(0), at(10)), s.map { it.startMs })
    }

    @Test fun `returning within the gap continues the session`() {
        val d = eval(4.5, use(0, 4))
        assertEquals(at(0), d.session?.startMs)
        assertEquals(4 * MINUTE_MS, d.session?.activeMs)
    }

    // ---- Free hit ----

    @Test fun `a 5 minute session triggers nothing, then a new session is allowed`() {
        val during = eval(5, use(0, 5))
        assertEquals(Verdict.ALLOW, during.verdict)
        val after = eval(7, use(0, 5))
        assertEquals(Verdict.ALLOW, after.verdict)
        assertNull(after.session)
        assertTrue(after.blocks.isEmpty())
    }

    @Test fun `free hits through the day never cool down`() {
        val checkIns = (0 until 5).map { use(it * 70, it * 70 + 4) }.toTypedArray()
        val d = eval(4 * 70 + 10, *checkIns)
        assertEquals(Verdict.ALLOW, d.verdict)
    }

    // ---- Nudge and cap ----

    @Test fun `nudge at 6 minutes, not before`() {
        assertEquals(Verdict.ALLOW, eval(5.99, use(0, 5.99)).verdict)
        assertEquals(Verdict.NUDGE, eval(6, use(0, 6)).verdict)
        assertEquals(Verdict.NUDGE, eval(7.9, use(0, 7.9)).verdict)
    }

    @Test fun `nudge counts the whole session across short gaps`() {
        val d = eval(7.5, use(0, 4), use(5, 7.5))
        assertEquals(Verdict.NUDGE, d.verdict)
        assertEquals((6.5 * MINUTE_MS).toLong(), d.session?.activeMs)
    }

    @Test fun `block at 8 minutes with a cooldown of 1_5x from the session's end`() {
        val d = eval(8, use(0, 8))
        assertEquals(Verdict.BLOCK, d.verdict)
        assertEquals(SESSION_CAP, d.block?.reason)
        assertEquals(at(8 + 12), d.block?.untilMs)
    }

    @Test fun `coming back within the gap after a cap block is still blocked, same end time`() {
        val d = eval(9, use(0, 8))
        assertEquals(Verdict.BLOCK, d.verdict)
        assertEquals(SESSION_CAP, d.block?.reason)
        assertEquals(at(20), d.block?.untilMs)
    }

    @Test fun `after the gap the cap block becomes the cooldown, ending at the same time`() {
        val d = eval(11, use(0, 8))
        assertEquals(COOLDOWN, d.block?.reason)
        assertEquals(at(20), d.block?.untilMs)
        assertEquals(Verdict.ALLOW, eval(20, use(0, 8)).verdict)
    }

    // ---- Cooldown ----

    @Test fun `cooldown scales with session length`() {
        assertEquals(0L, defaults.cooldownFor(5 * MINUTE_MS))
        assertEquals(9 * MINUTE_MS, defaults.cooldownFor(6 * MINUTE_MS))
        assertEquals(15 * MINUTE_MS, defaults.cooldownFor(10 * MINUTE_MS))
    }

    @Test fun `reopening during the cooldown of a 10 minute session blocks for 15 minutes`() {
        val session = use(0, 10)
        val d = eval(13, session)
        assertEquals(Verdict.BLOCK, d.verdict)
        assertEquals(COOLDOWN, d.block?.reason)
        assertEquals(at(25), d.block?.untilMs)
        assertEquals(Verdict.BLOCK, eval(24.99, session).verdict)
        assertEquals(Verdict.ALLOW, eval(25, session).verdict)
    }

    @Test fun `leaving a 7 minute session and coming back after the gap starts its cooldown`() {
        val d = eval(10, use(0, 7))
        assertEquals(COOLDOWN, d.block?.reason)
        assertEquals(at(7 + 10.5), d.block?.untilMs)
    }

    @Test fun `leaving a 7 minute session and coming back within the gap continues it toward the cap`() {
        val d = eval(8, use(0, 7))
        assertEquals(Verdict.NUDGE, d.verdict)
        assertEquals(7 * MINUTE_MS, d.session?.activeMs)
    }

    @Test fun `a cap at or under the free hit still blocks until the session can't continue`() {
        val policy = Policy(sessionCapMs = 3 * MINUTE_MS)
        val d = eval(3, use(0, 3), policy = policy)
        assertEquals(SESSION_CAP, d.block?.reason)
        assertEquals(at(5), d.block?.untilMs)
    }

    // ---- Rolling budget ----

    @Test fun `rolling budget blocks at 15 minutes in the hour and frees up a minute later than needed`() {
        val uses = arrayOf(use(0, 5), use(15, 20), use(35, 40))
        val d = eval(40, *uses)
        assertEquals(Verdict.BLOCK, d.verdict)
        assertEquals(15 * MINUTE_MS, d.rollingUsedMs)
        val rolling = d.blocks.first { it.reason == ROLLING_BUDGET }
        // At 61 the window [1, 61] holds 4 + 5 + 5 = 14 min, leaving the 1 min start allowance.
        assertEquals(at(61), rolling.untilMs)
    }

    @Test fun `rolling budget lets a running session use the last minute`() {
        val d = eval(34.5, use(0, 5), use(15, 20), use(30, 34.5))
        assertEquals(Verdict.ALLOW, d.verdict)
        assertEquals((14.5 * MINUTE_MS).toLong(), d.rollingUsedMs)
    }

    @Test fun `a new session needs a minute of rolling budget left`() {
        val nearlyFull = arrayOf(use(0, 5), use(15, 20), use(30, 34.5))
        val d = eval(40, *nearlyFull)
        assertEquals(Verdict.BLOCK, d.verdict)
        assertEquals(ROLLING_BUDGET, d.block?.reason)

        val oneMinuteLeft = arrayOf(use(0, 5), use(15, 20), use(30, 34))
        assertEquals(Verdict.ALLOW, eval(40, *oneMinuteLeft).verdict)
    }

    @Test fun `rolling window forgets use older than an hour`() {
        val d = eval(70, use(0, 5), use(15, 20), use(35, 40))
        assertEquals(10 * MINUTE_MS, d.rollingUsedMs)
        assertEquals(Verdict.ALLOW, d.verdict)
    }

    // ---- Daily ceiling ----

    @Test fun `daily ceiling blocks until midnight`() {
        // Twelve 7.5-minute sessions, each more than an hour apart: 90 minutes today.
        val uses = (0 until 12).map { use(it * 80 - 600, it * 80 - 600 + 7.5) }.toTypedArray()
        val now = 11 * 80 - 600 + 7.5
        val d = eval(now, *uses, policy = defaults.copy(cooldownMultiplier = 0.0))
        assertEquals(90 * MINUTE_MS, d.todayUsedMs)
        assertEquals(Verdict.BLOCK, d.verdict)
        assertEquals(DAILY_CEILING, d.block?.reason)
        assertEquals(nextDay, d.block?.untilMs)
    }

    @Test fun `yesterday's use doesn't count today, and a session over midnight counts its part`() {
        val policy = Policy(dailyCeilingMs = 10 * MINUTE_MS, sessionCapMs = 60 * MINUTE_MS)
        val beforeMidnight = Interval(day - 30 * MINUTE_MS, day + 5 * MINUTE_MS)
        val d = PolicyEngine.evaluate(policy, listOf(beforeMidnight), day + 5 * MINUTE_MS, day, nextDay)
        assertEquals(5 * MINUTE_MS, d.todayUsedMs)
        assertFalse(d.blocks.any { it.reason == DAILY_CEILING })
    }

    // ---- Overrides ----

    @Test fun `an override lets you in despite a block, then the block returns`() {
        val override = listOf(Interval(at(9), at(14)))
        val during = eval(12, use(0, 8), use(9, 12), overrides = override)
        assertEquals(Verdict.ALLOW, during.verdict)
        assertTrue(during.isOverridden)
        assertEquals(at(14), during.overrideUntilMs)

        val after = eval(14, use(0, 8), use(9, 14), overrides = override)
        assertEquals(Verdict.BLOCK, after.verdict)
        // The overridden session ran 13 minutes: 19.5 minutes of cooldown from its end.
        assertEquals(SESSION_CAP, after.block?.reason)
        assertEquals(at(14 + 19.5), after.block?.untilMs)
    }

    @Test fun `an expired override does nothing`() {
        val d = eval(13, use(0, 10), overrides = listOf(Interval(at(1), at(6))))
        assertEquals(Verdict.BLOCK, d.verdict)
        assertNull(d.overrideUntilMs)
    }

    // ---- Several limits at once ----

    @Test fun `the longest block is the one shown`() {
        val policy = Policy(dailyCeilingMs = 8 * MINUTE_MS)
        val d = eval(8, use(0, 8), policy = policy)
        assertEquals(setOf(SESSION_CAP, DAILY_CEILING), d.blocks.map { it.reason }.toSet())
        assertEquals(DAILY_CEILING, d.block?.reason)
    }

    @Test fun `per-app numbers replace the defaults`() {
        val strict = Policy(
            freeHitMs = 1 * MINUTE_MS,
            nudgeAtMs = 2 * MINUTE_MS,
            sessionCapMs = 3 * MINUTE_MS,
            cooldownMultiplier = 2.0,
        )
        assertEquals(Verdict.NUDGE, eval(2, use(0, 2), policy = strict).verdict)
        val d = eval(3, use(0, 3), policy = strict)
        assertEquals(SESSION_CAP, d.block?.reason)
        assertEquals(at(9), d.block?.untilMs)
    }

    @Test fun `an interval still open past now is cut at now`() {
        val d = eval(4, Interval(at(0), at(30)))
        assertEquals(4 * MINUTE_MS, d.session?.activeMs)
        assertEquals(Verdict.ALLOW, d.verdict)
    }
}
