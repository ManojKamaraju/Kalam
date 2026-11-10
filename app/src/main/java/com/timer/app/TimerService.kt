package com.timer.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import java.io.File

/** Plays the alarm tone (looping) with audio focus, and vibrates. Used by the service and by the settings preview. */
class AlarmPlayer(private val ctx: Context) {
    private var mp: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var playing = false
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    val isPlaying: Boolean get() = playing

    fun start(cfg: AlarmConfig, loop: Boolean, onTimeout: (() -> Unit)?) {
        if (playing) return
        playing = true
        val am = ctx.getSystemService(AudioManager::class.java)
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs())
                .build()
            focus = req
            am?.requestAudioFocus(req)
        } catch (e: Throwable) {
        }
        if (!startPlayer(cfg, loop, cfg.source == 1)) {
            // custom file missing/corrupt, or built-in failed: fall back to the default tone, never crash
            startPlayer(cfg, loop, false)
        }
        if (cfg.vibrate && loop) {
            try {
                val v = HapticPlayer.vibrator(ctx)
                if (v != null && v.hasVibrator()) {
                    @Suppress("DEPRECATION")
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0), HapticPlayer.alarmAttributes())
                }
            } catch (e: Throwable) {
            }
        }
        if (loop && cfg.loopSeconds > 0 && onTimeout != null) {
            val r = Runnable { onTimeout() }
            timeout = r
            handler.postDelayed(r, cfg.loopSeconds * 1000L)
        }
    }

    private fun attrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun rawId(id: String): Int = when (id) {
        "alarm_soft_chime" -> R.raw.alarm_soft_chime
        "alarm_gentle_bells" -> R.raw.alarm_gentle_bells
        "alarm_minimal_ping" -> R.raw.alarm_minimal_ping
        "alarm_notification" -> R.raw.alarm_notification
        "alarm_digital" -> R.raw.alarm_digital
        "alarm_classic_bell" -> R.raw.alarm_classic_bell
        "alarm_rapid_pulse" -> R.raw.alarm_rapid_pulse
        "alarm_urgent_siren" -> R.raw.alarm_urgent_siren
        else -> R.raw.alarm_soft_chime
    }

    private fun startPlayer(cfg: AlarmConfig, loop: Boolean, custom: Boolean): Boolean {
        releasePlayer()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attrs())
            if (custom) {
                val name = cfg.customFile
                if (!MediaImport.exists(ctx, name)) {
                    p.release()
                    return false
                }
                p.setDataSource(File(ctx.filesDir, name!!).path)
            } else {
                val afd = ctx.resources.openRawResourceFd(rawId(cfg.builtinId))
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            }
            p.isLooping = loop
            val vol = cfg.volume.coerceIn(0f, 1f)
            p.setVolume(vol, vol)
            p.setOnErrorListener { _, _, _ ->
                releasePlayer()
                true
            }
            p.prepare()
            p.start()
            mp = p
            return true
        } catch (e: Throwable) {
            try {
                p.release()
            } catch (e2: Throwable) {
            }
            return false
        }
    }

    private fun releasePlayer() {
        val p = mp
        mp = null
        if (p != null) {
            try {
                p.stop()
            } catch (e: Throwable) {
            }
            try {
                p.release()
            } catch (e: Throwable) {
            }
        }
    }

    fun stop() {
        if (!playing) return
        playing = false
        val r = timeout
        if (r != null) handler.removeCallbacks(r)
        timeout = null
        releasePlayer()
        try {
            HapticPlayer.vibrator(ctx)?.cancel()
        } catch (e: Throwable) {
        }
        try {
            val f = focus
            if (f != null) ctx.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f)
        } catch (e: Throwable) {
        }
        focus = null
    }
}

/** Foreground service: keeps the process alive while the timer runs in the background, shows the notification, plays the alarm. */
class TimerService : Service() {
    companion object {
        const val NOTIF_ID = 1001
        const val ACT_TOGGLE = "com.timer.app.ACT_TOGGLE"
        const val ACT_CLOSE = "com.timer.app.ACT_CLOSE"
        const val ACT_STOP_ALARM = "com.timer.app.ACT_STOP_ALARM"

        @Volatile
        var instance: TimerService? = null

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, TimerService::class.java))
            } catch (e: Throwable) {
                // Background-start restrictions: the in-process timer and the AlarmManager wake-up still work.
            }
        }

        fun shutdown() {
            instance?.shutdownNow()
        }
    }

    private lateinit var alarm: AlarmPlayer
    private var shuttingDown = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        alarm = AlarmPlayer(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        shuttingDown = false
        enterForeground()
        refresh()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The UI was swiped away: the Core decides (running timer + background enabled keeps running).
        core().onActivityDestroyed(true)
    }

    override fun onDestroy() {
        alarm.stop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun enterForeground() {
        try {
            startForeground(NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Throwable) {
            // Not allowed right now: nothing else to do, the notification is optional
        }
    }

    fun shutdownNow() {
        shuttingDown = true
        alarm.stop()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
        }
        stopSelf()
    }

    /** Re-evaluates what the service has to do. Safe to call at any time from the main thread. */
    fun refresh() {
        if (shuttingDown) return
        val c = core()
        val running = c.engine.isRunning()
        val ring = c.ringing
        if (ring) {
            alarm.start(c.alarmConfig(), true) { c.stopAlarm() }
        } else {
            alarm.stop()
        }
        val bgNeeded = c.model.prefs.runInBackground && running
        val nm = getSystemService(NotificationManager::class.java)
        if (!ring && !bgNeeded) {
            val pausedNotice = !c.visible && c.model.prefs.runInBackground && !running && c.engine.value() > 0L
            try {
                if (pausedNotice) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    nm?.notify(NOTIF_ID, build())
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            } catch (e: Throwable) {
            }
            stopSelf()
            return
        }
        try {
            nm?.notify(NOTIF_ID, build())
        } catch (e: Throwable) {
        }
    }

    private fun actionPi(code: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        this, code, Intent(this, ActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun act(label: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notification), label, pi).build()

    private fun build(): Notification {
        val c = core()
        val e = c.engine
        val prefs = c.model.prefs
        val ring = c.ringing
        val running = e.isRunning()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, if (ring) Core.CH_ALARM else Core.CH_TIMER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("KALAM")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (ring) {
            b.setContentText(if (c.ringWhich == 0) "Time is up" else "Target time reached")
            b.setCategory(Notification.CATEGORY_ALARM)
            b.setOngoing(true)
            b.addAction(act("Stop", actionPi(3, ACT_STOP_ALARM)))
        } else if (running) {
            val now = System.currentTimeMillis()
            val v = e.value()
            val countdown = e.mode == TimeEngine.MODE_COUNTDOWN
            b.setContentText(if (countdown) "Countdown running" else "Stopwatch running")
            b.setCategory(Notification.CATEGORY_STOPWATCH)
            b.setOngoing(true)
            if (prefs.notificationShowsTime) {
                b.setShowWhen(true)
                b.setUsesChronometer(true)
                if (countdown) {
                    b.setChronometerCountDown(true)
                    b.setWhen(now + v)
                } else {
                    b.setWhen(now - v)
                }
            } else {
                b.setShowWhen(false)
            }
            b.addAction(act("Pause", actionPi(1, ACT_TOGGLE)))
            b.addAction(act("Close", actionPi(2, ACT_CLOSE)))
        } else {
            b.setContentText(if (prefs.notificationShowsTime) TimeFormat.full(e.value()) + "   Paused" else "Paused")
            b.setShowWhen(false)
            b.setOngoing(false)
            b.addAction(act("Resume", actionPi(1, ACT_TOGGLE)))
            b.addAction(act("Close", actionPi(2, ACT_CLOSE)))
        }
        return b.build()
    }
}

/** AlarmManager wake-up: fires when the countdown/target is reached while the process was asleep or gone. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        context.core().runDue()
    }
}

/** Device restart / app update: the Application has already restored the session; re-arm background work. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val c = context.core()
        c.runDue()
        c.syncService()
    }
}

/** Notification buttons. They go straight to the Core, so they work without any UI. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val c = context.core()
        when (intent?.action) {
            TimerService.ACT_TOGGLE -> c.toggle()
            TimerService.ACT_CLOSE -> c.explicitClose()
            TimerService.ACT_STOP_ALARM -> c.stopAlarm()
        }
    }
}
