package com.tony3974.bingeguard.monitor

import com.tony3974.bingeguard.monitor.TrackerEvent.ACTIVITY_PAUSED
import com.tony3974.bingeguard.monitor.TrackerEvent.ACTIVITY_RESUMED
import com.tony3974.bingeguard.monitor.TrackerEvent.ACTIVITY_STOPPED
import com.tony3974.bingeguard.monitor.TrackerEvent.DEVICE_SHUTDOWN
import com.tony3974.bingeguard.monitor.TrackerEvent.KEYGUARD_HIDDEN
import com.tony3974.bingeguard.monitor.TrackerEvent.KEYGUARD_SHOWN
import com.tony3974.bingeguard.monitor.TrackerEvent.SCREEN_INTERACTIVE
import com.tony3974.bingeguard.monitor.TrackerEvent.SCREEN_NON_INTERACTIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTrackerTest {
    private val t = ForegroundTracker()

    @Test fun `nothing resumed means nothing in front`() {
        assertNull(t.foregroundPackage)
    }

    @Test fun `switching apps follows the latest resume`() {
        t.onEvent(ACTIVITY_RESUMED, "tiktok", "Main")
        assertEquals("tiktok", t.foregroundPackage)
        t.onEvent(ACTIVITY_PAUSED, "tiktok", "Main")
        t.onEvent(ACTIVITY_RESUMED, "launcher", "Home")
        assertEquals("launcher", t.foregroundPackage)
    }

    @Test fun `a resume arriving before the old pause still ends on the new app`() {
        t.onEvent(ACTIVITY_RESUMED, "tiktok", "Main")
        t.onEvent(ACTIVITY_RESUMED, "launcher", "Home")
        t.onEvent(ACTIVITY_PAUSED, "tiktok", "Main")
        assertEquals("launcher", t.foregroundPackage)
    }

    @Test fun `moving between activities of one app keeps that app`() {
        t.onEvent(ACTIVITY_RESUMED, "tiktok", "Main")
        t.onEvent(ACTIVITY_PAUSED, "tiktok", "Main")
        t.onEvent(ACTIVITY_RESUMED, "tiktok", "Comments")
        assertEquals("tiktok", t.foregroundPackage)
        t.onEvent(ACTIVITY_STOPPED, "tiktok", "Main")
        assertEquals("tiktok", t.foregroundPackage)
    }

    @Test fun `stock and modded builds are separate packages`() {
        t.onEvent(ACTIVITY_RESUMED, "com.zhiliaoapp.musically", "Main")
        t.onEvent(ACTIVITY_PAUSED, "com.zhiliaoapp.musically", "Main")
        t.onEvent(ACTIVITY_RESUMED, "com.zhiliaoapp.musically.mod", "Main")
        assertEquals("com.zhiliaoapp.musically.mod", t.foregroundPackage)
    }

    @Test fun `screen off and lock screen hide the app`() {
        t.onEvent(ACTIVITY_RESUMED, "tiktok", "Main")
        t.onEvent(SCREEN_NON_INTERACTIVE, null, null)
        assertNull(t.foregroundPackage)
        t.onEvent(SCREEN_INTERACTIVE, null, null)
        t.onEvent(KEYGUARD_SHOWN, null, null)
        assertNull(t.foregroundPackage)
        t.onEvent(KEYGUARD_HIDDEN, null, null)
        assertEquals("tiktok", t.foregroundPackage)
    }

    @Test fun `shutdown clears everything`() {
        t.onEvent(ACTIVITY_RESUMED, "tiktok", "Main")
        t.onEvent(DEVICE_SHUTDOWN, null, null)
        assertNull(t.foregroundPackage)
    }

    @Test fun `replaying events already seen gives the same answer`() {
        val events = listOf(
            Triple(ACTIVITY_RESUMED, "a", "A"),
            Triple(ACTIVITY_PAUSED, "a", "A"),
            Triple(ACTIVITY_RESUMED, "b", "B"),
            Triple(ACTIVITY_RESUMED, "c", "C"),
            Triple(ACTIVITY_PAUSED, "c", "C"),
        )
        events.forEach { (e, p, c) -> t.onEvent(e, p, c) }
        val once = t.foregroundPackage
        events.drop(2).forEach { (e, p, c) -> t.onEvent(e, p, c) }
        assertEquals("b", once)
        assertEquals(once, t.foregroundPackage)
    }
}
