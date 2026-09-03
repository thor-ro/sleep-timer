package com.tlr.sleeptimer

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The only reason this is an [AccessibilityService] at all is [performGlobalAction] with
 * [AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN] — locking the screen requires it. It is
 * bound by the system as soon as the user enables it in Settings, which is independent of
 * whether a timer is running (see [SleepTimerRepository]), and being bound already exempts it
 * from background execution limits, so there is no foreground service, no started-service
 * lifecycle to babysit, and no [stopSelf] (which would be a no-op on a system-bound service
 * anyway, since [onDestroy] never runs while the service stays enabled).
 */
class TimerService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pauseAndLockJob: Job? = null
    private var notificationChannelCreated = false

    companion object {
        private const val TAG = "TimerService"

        const val ACTION_START = "com.tlr.sleeptimer.action.START"
        const val ACTION_ABORT = "com.tlr.sleeptimer.action.ABORT"
        const val ACTION_ALARM_FIRED = "com.tlr.sleeptimer.action.ALARM_FIRED"
        const val EXTRA_DURATION_MINUTES = "com.tlr.sleeptimer.extra.DURATION_MINUTES"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sleep_timer_channel"
        private const val ALARM_REQUEST_CODE = 2001
        private const val ABORT_REQUEST_CODE = 2002
        private const val CONTENT_REQUEST_CODE = 2003

        /** Gives the media player a moment to actually process the pause before the screen locks. */
        private const val PAUSE_BEFORE_LOCK_DELAY_MS = 400L
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The system just bound us because accessibility was enabled — this says nothing
        // about whether a timer is running, so recover that from persisted state instead.
        // If a timer really was still running (i.e. the process died mid-timer), re-arm its
        // side effects: rescheduling the alarm is idempotent because the PendingIntent is
        // reused, and it restores the alarm in the case where it was lost with the process.
        val restored = SleepTimerRepository.restore(this)
        if (restored is TimerState.Running) {
            scheduleAlarm(restored.endTimeElapsedRealtime)
            showNotification(restored.endTimeElapsedRealtime)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val minutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, -1)
                if (TimerStateLogic.isValidDurationMinutes(minutes)) {
                    startTimer(minutes)
                } else {
                    Log.w(TAG, "Ignoring $ACTION_START with invalid duration extra: $minutes")
                }
            }
            ACTION_ABORT -> abortTimer()
            ACTION_ALARM_FIRED -> onTimerExpired()
        }
        return START_NOT_STICKY
    }

    private fun startTimer(durationMinutes: Int) {
        pauseAndLockJob?.cancel()
        val running = SleepTimerRepository.start(this, durationMinutes) ?: return
        scheduleAlarm(running.endTimeElapsedRealtime)
        showNotification(running.endTimeElapsedRealtime)
    }

    private fun abortTimer() {
        pauseAndLockJob?.cancel()
        cancelAlarm()
        dismissNotification()
        SleepTimerRepository.abort(this)
    }

    private fun onTimerExpired() {
        pauseAndLockJob?.cancel()
        pauseAndLockJob = serviceScope.launch {
            pauseMedia()
            delay(PAUSE_BEFORE_LOCK_DELAY_MS)
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            cancelAlarm()
            dismissNotification()
            SleepTimerRepository.abort(this@TimerService)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- Media pause -----------------------------------------------------------------

    /**
     * Pauses whatever media is playing. A media-key event is the most broadly compatible
     * signal (most players react to it directly); requesting transient audio focus is a
     * second, independent signal for players that pause on focus loss instead. The result of
     * the focus request is checked, and the focus is abandoned right away — never held for
     * the life of the process.
     */
    private fun pauseMedia() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = System.currentTimeMillis()
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
        )

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        val result = audioManager.requestAudioFocus(focusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        } else {
            Log.w(TAG, "Audio focus request was not granted (result=$result); relying on the media-key pause")
        }
    }

    // ---- Alarm scheduling -------------------------------------------------------------

    // Lint's permission database only lists SCHEDULE_EXACT_ALARM for this API (verified against
    // the platform's annotations.zip), not the USE_EXACT_ALARM this app actually declares and
    // relies on — that permission is exactly what Google reserves for user-set timers/alarms
    // like this one. The runtime guard below (canScheduleExactAlarms(), with a real fallback) is
    // the documented way to use it; declaring SCHEDULE_EXACT_ALARM instead would be the wrong
    // permission model (a different, special-access runtime grant flow).
    @android.annotation.SuppressLint("MissingPermission")
    private fun scheduleAlarm(endTimeElapsedRealtime: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = alarmPendingIntent()
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                endTimeElapsedRealtime,
                pendingIntent
            )
        } else {
            Log.w(TAG, "Exact alarms unavailable; falling back to an inexact idle-aware alarm")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                endTimeElapsedRealtime,
                pendingIntent
            )
        }
    }

    private fun cancelAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmPendingIntent())
    }

    private fun alarmPendingIntent(): PendingIntent {
        val intent = Intent(this, TimerService::class.java).apply { action = ACTION_ALARM_FIRED }
        return PendingIntent.getService(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---- Notification -------------------------------------------------------------------

    private fun ensureNotificationChannel() {
        if (notificationChannelCreated) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        notificationChannelCreated = true
    }

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Builds and posts a single notification with a live, chronometer-driven countdown, so
     * there is no periodic rebuild for the whole duration of the timer. The abort action is
     * attached from the very first second, not just the final minute.
     */
    private fun showNotification(endTimeElapsedRealtime: Long) {
        if (!hasNotificationPermission()) return

        val remainingMillis = TimerStateLogic.remainingMillis(
            TimerState.Running(endTimeElapsedRealtime),
            SystemClock.elapsedRealtime()
        )
        val chronometerBase = System.currentTimeMillis() + remainingMillis

        val contentIntent = PendingIntent.getActivity(
            this,
            CONTENT_REQUEST_CODE,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val abortIntent = Intent(this, TimerService::class.java).apply { action = ACTION_ABORT }
        val abortPendingIntent = PendingIntent.getService(
            this,
            ABORT_REQUEST_CODE,
            abortIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(android.R.drawable.ic_lock_power_off)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(chronometerBase)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_abort_action),
                abortPendingIntent
            )
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun dismissNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
