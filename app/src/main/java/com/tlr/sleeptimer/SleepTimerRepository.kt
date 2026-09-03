package com.tlr.sleeptimer

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for whether a sleep timer is running, shared between [TimerService]
 * and the UI. Backed by [SharedPreferences] so the running state survives process death; the
 * accessibility service is bound by the system as soon as it is enabled, independently of
 * whether a timer is active, so its mere existence must never again stand in for "a timer is
 * running" (see [TimerState]).
 *
 * End times are [SystemClock.elapsedRealtime] values, which are immune to wall-clock changes
 * but reset to zero on reboot. The boot instant is therefore persisted alongside them so
 * [TimerStateLogic.restoreState] can tell a genuinely running timer from a stale one written
 * during a previous boot session.
 */
object SleepTimerRepository {
    private const val PREFS_NAME = "sleep_timer_prefs"
    private const val KEY_END_TIME = "end_time_elapsed_realtime"
    private const val KEY_BOOT_INSTANT = "boot_instant_wall_clock"

    private val _state = MutableStateFlow<TimerState>(TimerState.Idle)
    val state: StateFlow<TimerState> = _state.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Wall-clock time at which the device booted; stable within a boot session. */
    private fun currentBootInstant(): Long =
        System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * Loads any persisted state, discarding it (and cleaning up the persisted values) if it
     * expired while the process was dead, or if it was written before a reboot. Safe to call
     * multiple times, from either the activity or the service, in any order.
     *
     * Returns the restored state so callers can re-arm side effects (alarm, notification) when
     * a timer was genuinely still running.
     */
    fun restore(context: Context): TimerState {
        val prefs = prefs(context)
        val persisted = if (prefs.contains(KEY_END_TIME)) {
            TimerStateLogic.PersistedTimer(
                endTimeElapsedRealtime = prefs.getLong(KEY_END_TIME, 0L),
                bootInstantWallClock = prefs.getLong(KEY_BOOT_INSTANT, 0L)
            )
        } else {
            null
        }

        val restored = TimerStateLogic.restoreState(
            persisted = persisted,
            nowElapsedRealtime = SystemClock.elapsedRealtime(),
            currentBootInstantWallClock = currentBootInstant()
        )
        _state.value = restored
        if (restored is TimerState.Idle && persisted != null) {
            clearPersisted(prefs)
        }
        return restored
    }

    /**
     * Starts a timer for [durationMinutes], persists it, and publishes the new state.
     * Returns `null` (without changing any state) if [durationMinutes] is not a valid duration.
     */
    fun start(context: Context, durationMinutes: Int): TimerState.Running? {
        if (!TimerStateLogic.isValidDurationMinutes(durationMinutes)) return null
        val endTime = TimerStateLogic.endTimeFor(durationMinutes, SystemClock.elapsedRealtime())
        prefs(context).edit {
            putLong(KEY_END_TIME, endTime)
            putLong(KEY_BOOT_INSTANT, currentBootInstant())
        }
        val running = TimerState.Running(endTime)
        _state.value = running
        return running
    }

    /** Clears any running timer and publishes [TimerState.Idle]. */
    fun abort(context: Context) {
        clearPersisted(prefs(context))
        _state.value = TimerState.Idle
    }

    private fun clearPersisted(prefs: SharedPreferences) {
        prefs.edit {
            remove(KEY_END_TIME)
            remove(KEY_BOOT_INSTANT)
        }
    }
}
