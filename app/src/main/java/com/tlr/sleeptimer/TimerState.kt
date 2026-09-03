package com.tlr.sleeptimer

/**
 * Whether a sleep timer is currently running, expressed purely in terms of the
 * elapsed-realtime clock (millis since boot, unaffected by wall-clock changes).
 *
 * This has no Android framework dependency by design: an enabled [TimerService] is bound
 * by the system the moment the user turns accessibility on, long before any timer is
 * started, so "the service instance exists" must never be used as a proxy for "a timer is
 * running". This type is the single source of truth instead, shared by the service and the UI.
 */
sealed interface TimerState {
    data object Idle : TimerState
    data class Running(val endTimeElapsedRealtime: Long) : TimerState
}

/**
 * Pure timer-state computations used by both [SleepTimerRepository] and [TimerService].
 *
 * Every function takes "now" as an explicit elapsed-realtime parameter instead of reading
 * [android.os.SystemClock] itself, so this object has zero Android framework dependencies
 * and can be exercised by plain JVM unit tests with no Robolectric/instrumentation needed.
 */
object TimerStateLogic {
    const val MIN_DURATION_MINUTES = 1
    const val MAX_DURATION_MINUTES = 60

    /** True when [minutes] is a duration the UI is allowed to start a timer for (1..60). */
    fun isValidDurationMinutes(minutes: Int): Boolean =
        minutes in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES

    /** The elapsed-realtime instant at which a timer of [durationMinutes] started "now" would expire. */
    fun endTimeFor(durationMinutes: Int, nowElapsedRealtime: Long): Long =
        nowElapsedRealtime + durationMinutes * 60_000L

    /** Milliseconds remaining until [state] expires, clamped to zero. [TimerState.Idle] is always zero. */
    fun remainingMillis(state: TimerState, nowElapsedRealtime: Long): Long = when (state) {
        is TimerState.Idle -> 0L
        is TimerState.Running -> (state.endTimeElapsedRealtime - nowElapsedRealtime).coerceAtLeast(0L)
    }

    /** True once a running timer's end time has passed. [TimerState.Idle] is never "expired". */
    fun isExpired(state: TimerState, nowElapsedRealtime: Long): Boolean = when (state) {
        is TimerState.Idle -> false
        is TimerState.Running -> nowElapsedRealtime >= state.endTimeElapsedRealtime
    }

    /**
     * The maximum time a legitimately running timer can still have left. Used as a
     * defence-in-depth sanity check when restoring persisted state.
     */
    private const val MAX_REMAINING_MILLIS = MAX_DURATION_MINUTES * 60_000L

    /**
     * How far the recorded boot instant may drift from the current one before we conclude the
     * device rebooted. Both clocks tick at the same rate within a boot session, so the
     * difference is normally a few milliseconds; the allowance only absorbs small wall-clock
     * corrections (e.g. an NTP sync).
     */
    private const val BOOT_INSTANT_TOLERANCE_MILLIS = 10_000L

    /**
     * A persisted timer. [bootInstantWallClock] is `System.currentTimeMillis() -
     * SystemClock.elapsedRealtime()` at the moment of writing, i.e. the wall-clock time the
     * device booted. It is stored alongside the end time purely so a reboot can be detected.
     */
    data class PersistedTimer(
        val endTimeElapsedRealtime: Long,
        val bootInstantWallClock: Long
    )

    /**
     * Rebuilds a [TimerState] from [persisted] state, or [TimerState.Idle] if nothing was
     * persisted. State is discarded rather than resurrected when any of these hold:
     *
     *  - **The device rebooted.** End times are elapsed-realtime values (millis since boot),
     *    which reset to zero on reboot, so an end time written during an earlier boot session
     *    is meaningless in the current one. Without this check a 60-minute timer set on a
     *    device that had been up for days would come back after a reboot as a multi-day
     *    countdown. A reboot also clears any pending alarm, so there is nothing left to fire.
     *  - **The end time has already passed**, including exactly now.
     *  - **More than [MAX_DURATION_MINUTES] minutes remain**, which no timer this app can
     *    start would ever have. This is a backstop for clock adjustments large enough to
     *    defeat the reboot check.
     */
    fun restoreState(
        persisted: PersistedTimer?,
        nowElapsedRealtime: Long,
        currentBootInstantWallClock: Long
    ): TimerState {
        if (persisted == null) return TimerState.Idle

        val bootDrift = kotlin.math.abs(persisted.bootInstantWallClock - currentBootInstantWallClock)
        if (bootDrift > BOOT_INSTANT_TOLERANCE_MILLIS) return TimerState.Idle

        val remaining = persisted.endTimeElapsedRealtime - nowElapsedRealtime
        if (remaining <= 0L || remaining > MAX_REMAINING_MILLIS) return TimerState.Idle

        return TimerState.Running(persisted.endTimeElapsedRealtime)
    }
}
