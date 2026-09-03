package com.tlr.sleeptimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast JVM unit tests for [TimerStateLogic], the pure timer-state math extracted from
 * [TimerService] / [SleepTimerRepository]. No Android framework or Robolectric dependency:
 * every function under test takes "now" as an explicit elapsed-realtime parameter rather than
 * reading [android.os.SystemClock] itself.
 */
class TimerStateLogicTest {

    // ---- isValidDurationMinutes ---------------------------------------------------------

    @Test
    fun `duration validation accepts the full 1 to 60 range`() {
        assertTrue(TimerStateLogic.isValidDurationMinutes(1))
        assertTrue(TimerStateLogic.isValidDurationMinutes(30))
        assertTrue(TimerStateLogic.isValidDurationMinutes(60))
    }

    @Test
    fun `duration validation rejects zero, negative and above-60 values`() {
        assertFalse(TimerStateLogic.isValidDurationMinutes(0))
        assertFalse(TimerStateLogic.isValidDurationMinutes(-5))
        assertFalse(TimerStateLogic.isValidDurationMinutes(61))
        assertFalse(TimerStateLogic.isValidDurationMinutes(Int.MIN_VALUE))
    }

    @Test
    fun `duration validation rejects the missing-extra sentinel`() {
        // TimerService reads the duration extra with a default of -1 when it is absent/malformed.
        assertFalse(TimerStateLogic.isValidDurationMinutes(-1))
    }

    // ---- endTimeFor --------------------------------------------------------------------

    @Test
    fun `endTimeFor adds duration in minutes converted to millis`() {
        val now = 10_000L
        val endTime = TimerStateLogic.endTimeFor(durationMinutes = 5, nowElapsedRealtime = now)
        assertEquals(now + 5 * 60_000L, endTime)
    }

    // ---- remainingMillis -----------------------------------------------------------------

    @Test
    fun `remainingMillis is zero for Idle regardless of now`() {
        assertEquals(0L, TimerStateLogic.remainingMillis(TimerState.Idle, nowElapsedRealtime = 999_999L))
    }

    @Test
    fun `remainingMillis computes the gap to the end time while running`() {
        val state = TimerState.Running(endTimeElapsedRealtime = 20_000L)
        assertEquals(15_000L, TimerStateLogic.remainingMillis(state, nowElapsedRealtime = 5_000L))
    }

    @Test
    fun `remainingMillis is clamped to zero once the end time has passed`() {
        val state = TimerState.Running(endTimeElapsedRealtime = 20_000L)
        assertEquals(0L, TimerStateLogic.remainingMillis(state, nowElapsedRealtime = 30_000L))
    }

    // ---- isExpired -------------------------------------------------------------------------

    @Test
    fun `isExpired is always false for Idle`() {
        assertFalse(TimerStateLogic.isExpired(TimerState.Idle, nowElapsedRealtime = 0L))
        assertFalse(TimerStateLogic.isExpired(TimerState.Idle, nowElapsedRealtime = Long.MAX_VALUE))
    }

    @Test
    fun `isExpired is false while the end time is still in the future`() {
        val state = TimerState.Running(endTimeElapsedRealtime = 20_000L)
        assertFalse(TimerStateLogic.isExpired(state, nowElapsedRealtime = 19_999L))
    }

    @Test
    fun `isExpired is true exactly at and after the end time`() {
        val state = TimerState.Running(endTimeElapsedRealtime = 20_000L)
        assertTrue(TimerStateLogic.isExpired(state, nowElapsedRealtime = 20_000L))
        assertTrue(TimerStateLogic.isExpired(state, nowElapsedRealtime = 20_001L))
    }

    // ---- restoreState ------------------------------------------------------------------------
    //
    // End times are elapsed-realtime values (millis since boot), so restoring them is only
    // valid within the same boot session. BOOT is a stand-in for "the wall-clock instant this
    // device booted", persisted alongside the end time so a reboot can be detected.

    private val BOOT = 1_700_000_000_000L

    private fun persisted(endTime: Long, bootInstant: Long = BOOT) =
        TimerStateLogic.PersistedTimer(endTime, bootInstant)

    @Test
    fun `restoreState is Idle when nothing was ever persisted`() {
        val state = TimerStateLogic.restoreState(null, nowElapsedRealtime = 1_000L, currentBootInstantWallClock = BOOT)
        assertEquals(TimerState.Idle, state)
    }

    @Test
    fun `restoreState resurrects a still-future persisted end time as Running`() {
        val state = TimerStateLogic.restoreState(
            persisted(endTime = 600_000L),
            nowElapsedRealtime = 300_000L,
            currentBootInstantWallClock = BOOT
        )
        assertEquals(TimerState.Running(600_000L), state)
    }

    @Test
    fun `restoreState collapses an already-expired persisted end time to Idle`() {
        val state = TimerStateLogic.restoreState(
            persisted(endTime = 600_000L),
            nowElapsedRealtime = 900_000L,
            currentBootInstantWallClock = BOOT
        )
        assertEquals(TimerState.Idle, state)
    }

    @Test
    fun `restoreState treats an end time equal to now as already expired`() {
        val state = TimerStateLogic.restoreState(
            persisted(endTime = 600_000L),
            nowElapsedRealtime = 600_000L,
            currentBootInstantWallClock = BOOT
        )
        assertEquals(TimerState.Idle, state)
    }

    @Test
    fun `restoreState discards state written before a reboot`() {
        // Regression test. elapsedRealtime resets to zero on reboot, so an end time persisted
        // after days of uptime is a huge number compared with the post-reboot clock and would
        // otherwise restore as a multi-day countdown. The device booted much later than the
        // boot instant recorded with the timer, which is what gives the reboot away.
        val endTimeFromPreviousSession = 503_600_000L // ~5.8 days of uptime + 60 min
        val state = TimerStateLogic.restoreState(
            persisted(endTime = endTimeFromPreviousSession, bootInstant = BOOT),
            nowElapsedRealtime = 30_000L,               // 30s into the new boot session
            currentBootInstantWallClock = BOOT + 503_700_000L
        )
        assertEquals(TimerState.Idle, state)
    }

    @Test
    fun `restoreState tolerates a small wall-clock correction within the same boot session`() {
        // An NTP sync nudges currentTimeMillis, so the derived boot instant drifts slightly.
        // That must not be mistaken for a reboot and throw away a live timer.
        val state = TimerStateLogic.restoreState(
            persisted(endTime = 600_000L, bootInstant = BOOT),
            nowElapsedRealtime = 300_000L,
            currentBootInstantWallClock = BOOT + 2_000L
        )
        assertEquals(TimerState.Running(600_000L), state)
    }

    @Test
    fun `restoreState rejects a remaining time longer than any timer this app can start`() {
        // Backstop for clock adjustments large enough to defeat the reboot check: no timer
        // started by this app can ever have more than MAX_DURATION_MINUTES left.
        val overLongRemaining = (TimerStateLogic.MAX_DURATION_MINUTES + 1) * 60_000L
        val state = TimerStateLogic.restoreState(
            persisted(endTime = 100_000L + overLongRemaining),
            nowElapsedRealtime = 100_000L,
            currentBootInstantWallClock = BOOT
        )
        assertEquals(TimerState.Idle, state)
    }

    @Test
    fun `restoreState accepts a remaining time of exactly the maximum duration`() {
        val maxRemaining = TimerStateLogic.MAX_DURATION_MINUTES * 60_000L
        val endTime = 100_000L + maxRemaining
        val state = TimerStateLogic.restoreState(
            persisted(endTime = endTime),
            nowElapsedRealtime = 100_000L,
            currentBootInstantWallClock = BOOT
        )
        assertEquals(TimerState.Running(endTime), state)
    }

    @Test
    fun `a freshly started timer survives a restore round trip`() {
        // The end-to-end path the repository takes: endTimeFor -> persist -> restoreState.
        val now = 42_000L
        val endTime = TimerStateLogic.endTimeFor(durationMinutes = 30, nowElapsedRealtime = now)
        val state = TimerStateLogic.restoreState(
            persisted(endTime = endTime),
            nowElapsedRealtime = now,
            currentBootInstantWallClock = BOOT
        )
        assertEquals(TimerState.Running(endTime), state)
        assertEquals(30 * 60_000L, TimerStateLogic.remainingMillis(state, now))
    }
}
