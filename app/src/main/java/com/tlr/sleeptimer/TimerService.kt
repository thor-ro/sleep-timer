package com.tlr.sleeptimer

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.CountDownTimer
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat

class TimerService : AccessibilityService() {
    private var timer: CountDownTimer? = null
    var endTimeMillis: Long = 0
        private set

    companion object {
        const val ACTION_START = "com.tlr.sleeptimer.ACTION_START"
        const val ACTION_ABORT = "com.tlr.sleeptimer.ACTION_ABORT"

        var instance: TimerService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this

        when (intent?.action) {
            ACTION_START -> {
                val durationMinutes = intent.getIntExtra("duration_minutes", 0)
                if (durationMinutes > 0) {
                    startTimer(durationMinutes)
                }
            }
            ACTION_ABORT -> {
                stopTimer()
            }
        }
        return START_STICKY
    }

    private fun startTimer(durationMinutes: Int) {
        if (durationMinutes <= 0) return
        val durationMillis = durationMinutes * 60 * 1000L
        endTimeMillis = SystemClock.elapsedRealtime() + durationMillis

        val notification = createNotification("Timer aktiv", false)

        // Fix for Android 14+: Must specify foreground service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }

        timer?.cancel()
        timer = object : CountDownTimer(durationMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (millisUntilFinished <= 60000) {
                    val secondsLeft = millisUntilFinished / 1000
                    updateNotification("Beendet in ${secondsLeft}s...", true)
                }
            }
            override fun onFinish() {
                stopMedia()
                // The actual lock screen action
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                stopTimer()
            }
        }.start()
    }

    private fun stopTimer() {
        timer?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun stopMedia() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(focusRequest)
    }

    private fun updateNotification(content: String, showAbortButton: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification(content, showAbortButton))
    }

    private fun createNotification(content: String, showAbortButton: Boolean): Notification {
        val channelId = "sleep_timer_chan"
        val channel = NotificationChannel(channelId, "Sleep Timer", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sleep Timer")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_power_off)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (showAbortButton) {
            val abortIntent = Intent(this, TimerService::class.java).apply { action = ACTION_ABORT }
            val pendingIntent = PendingIntent.getService(this, 0, abortIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "ABBRUCH", pendingIntent)
        }
        return builder.build()
    }

    override fun onDestroy() {
        instance = null
        timer?.cancel()
        super.onDestroy()
    }
}