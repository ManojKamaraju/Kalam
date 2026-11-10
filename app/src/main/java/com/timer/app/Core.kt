package com.timer.app

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

class TimerApp : Application() {
    lateinit var core: Core
        private set

    override fun onCreate() {
        super.onCreate()
        core = Core(this)
        core.init()
    }
}

fun Context.core(): Core = (applicationContext as TimerApp).core

interface CoreListener {
    fun onTimerChanged() {}
    fun onTimerStarted() {}
    fun onRingingChanged() {}
    fun onMessage(text: String) {}
    fun onModelReset() {}
    fun onClosed() {}
}

/** Vibration helper. Respects hardware capabilities (amplitude control, predefined effects). */
object HapticPlayer {
    private val attrs: AudioAttributes by lazy { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build() }

    fun alarmAttributes(): AudioAttributes = attrs

    fun vibrator(ctx: Context): Vibrator? {
        return try {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } catch (e: Throwable) {
            null
        }
    }

    fun effectFor(cfg: HapticConfig, v: Vibrator): VibrationEffect {
        val amp = if (v.hasAmplitudeControl()) cfg.intensity.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        val d = max(5L, cfg.durationMs.toLong())
        val canAmp = v.hasAmplitudeControl()
        fun wave(t: LongArray, a: IntArray): VibrationEffect =
            if (canAmp) VibrationEffect.createWaveform(t, a, -1) else VibrationEffect.createWaveform(t, -1)
        return when (cfg.pattern) {
            1 -> wave(longArrayOf(0, d, 70, d), intArrayOf(0, amp, 0, amp))
            2 -> wave(longArrayOf(0, d, 70, d, 70, d), intArrayOf(0, amp, 0, amp, 0, amp))
            3 -> {
                if (canAmp) {
                    val step = max(5L, d / 5L)
                    val a = IntArray(5) { i -> max(1, (amp * (i + 1)) / 5) }
                    VibrationEffect.createWaveform(LongArray(5) { step }, a, -1)
                } else VibrationEffect.createOneShot(d, amp)
            }
            4 -> wave(longArrayOf(0, d, 90, (d * 8L) / 5L), intArrayOf(0, max(1, (amp * 7) / 10), 0, amp))
            5 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            else -> VibrationEffect.createOneShot(d, amp)
        }
    }

    fun play(ctx: Context, cfg: HapticConfig) {
        try {
            val v = vibrator(ctx) ?: return
            if (!v.hasVibrator()) return
            @Suppress("DEPRECATION")
            v.vibrate(effectFor(cfg, v), attrs)
        } catch (e: Throwable) {
            // unsupported pattern or vibrator unavailable: ignore (never crash for an optional feature)
        }
    }
}

/**
 * Core: wires the independent engines together (time, persistence, haptics, alarm, background service).
 * Lives in the Application so the timer survives Activity recreation, and so receivers can use it
 * when the app has no UI (alarm wake-up, device restart).
 */
class Core(val app: Application) {
    companion object {
        const val CH_TIMER = "timer"
        const val CH_ALARM = "alarm"
        const val ACTION_WAKE = "com.timer.app.WAKE"
    }

    val handler = Handler(Looper.getMainLooper())
    val clock = object : MonoClock {
        override fun nanos(): Long = SystemClock.elapsedRealtimeNanos()
    }
    val engine = TimeEngine(clock)
    private val configDir = File(app.filesDir, "config").apply { mkdirs() }
    val model = AppModel(configDir)
    private val sessionStore = JsonStore(configDir, "session")
    val listeners = CopyOnWriteArrayList<CoreListener>()

    var visible = false
        private set
    var ringing = false
        private set
    var ringWhich = 0
        private set
    private var openPending = true
    private var closed = false

    private val lastHapticIdx = LongArray(4)
    private var hapticRev = -1

    private val tick = Runnable { runDue() }
    private val persistRunnable = Runnable { writeSession() }

    // ------------------------------------------------------------------ lifecycle

    fun init() {
        model.loadAll()
        Fonts.load(app)
        createChannels()
        restoreSession()
        scheduleAll()
        syncService()
    }

    private fun createChannels() {
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            val timer = NotificationChannel(CH_TIMER, "Running timer", NotificationManager.IMPORTANCE_LOW)
            timer.description = "Shows the timer while it runs in the background"
            timer.setShowBadge(false)
            nm?.createNotificationChannel(timer)
            val alarm = NotificationChannel(CH_ALARM, "Timer alarm", NotificationManager.IMPORTANCE_HIGH)
            alarm.description = "Alerts you when the timer reaches its time"
            alarm.setSound(null, null)
            alarm.enableVibration(false)
            nm?.createNotificationChannel(alarm)
        } catch (e: Throwable) {
        }
    }

    private fun bootKey(): Long {
        return try {
            Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1).toLong()
        } catch (e: Throwable) {
            -1L
        }
    }

    // ------------------------------------------------------------------ session persistence

    private fun sessionToJson(s: SavedTimer): JSONObject = JSONObject()
        .put("mode", s.mode).put("base", s.baseMs).put("running", s.running).put("anchor", s.anchorNanos)
        .put("wall", s.anchorWallMs).put("boot", s.bootKey).put("origin", s.originMs)
        .put("finished", s.finished).put("targetFired", s.targetFired)

    private fun sessionFromJson(o: JSONObject): SavedTimer = SavedTimer(
        o.optInt("mode", 0), o.optLong("base", 0L), o.optBoolean("running", false), o.optLong("anchor", 0L),
        o.optLong("wall", 0L), o.optLong("boot", -1L), o.optLong("origin", 0L),
        o.optBoolean("finished", false), o.optBoolean("targetFired", false)
    )

    private fun restoreSession() {
        val raw = sessionStore.read() ?: return
        val s = try {
            sessionFromJson(raw)
        } catch (e: Throwable) {
            return
        }
        val prefs = model.prefs
        val bootNow = bootKey()
        val sameBoot = (s.bootKey == bootNow && bootNow >= 0L) && s.anchorNanos <= clock.nanos()
        val finishedWhileAway = engine.restore(s, sameBoot, System.currentTimeMillis(), prefs.countTimeWhileOff)
        // Which saved numbers may be brought back? (see docs/ARCHITECTURE.md, "Persistence precedence")
        val allow = prefs.rememberAfterClosing || (s.running && prefs.runInBackground)
        if (!allow) {
            engine.reset() // numbers only; mode and every visual setting are untouched
            persistSession(true)
        } else if (finishedWhileAway) {
            onFinishedCountdown()
        }
    }

    fun persistSession(immediate: Boolean) {
        handler.removeCallbacks(persistRunnable)
        if (immediate) writeSession() else handler.postDelayed(persistRunnable, 300L)
    }

    private fun writeSession() {
        val snap = engine.snapshot(System.currentTimeMillis(), bootKey())
        val text = sessionStore.serialize(sessionToJson(snap))
        AppModel.io.execute { sessionStore.writeSync(text) }
    }

    // ------------------------------------------------------------------ app lifecycle hooks

    fun setVisible(v: Boolean) {
        visible = v
        if (v) {
            try {
                if (!engine.isRunning()) app.getSystemService(NotificationManager::class.java)?.cancel(TimerService.NOTIF_ID)
            } catch (e: Throwable) {
            }
        }
        TimerService.instance?.refresh()
    }

    /** Activity became visible. Applies "Reset Timer When Reopening" exactly once per fresh open. */
    fun onOpen() {
        closed = false
        if (openPending) {
            openPending = false
            if (model.prefs.resetOnOpen && !(engine.isRunning() && TimerService.instance != null)) {
                if (ringing) stopAlarm()
                engine.reset()
                persistSession(true)
                notifyTimerChanged()
            }
        }
        scheduleAll()
        syncService()
    }

    /** Activity left the foreground. */
    fun onActivityStopped() {
        visible = false
        model.flushNow()
        if (!model.prefs.runInBackground && engine.isRunning()) {
            engine.pause() // "Run Timer in Background" is off: pause when leaving
            afterTimerChange(true)
        } else {
            persistSession(true)
            TimerService.instance?.refresh()
        }
    }

    /** The task was removed (swipe from recents) or the activity finished without the explicit close operation. */
    fun onActivityDestroyed(finishing: Boolean) {
        if (!finishing || closed) return
        if (model.prefs.runInBackground && engine.isRunning()) {
            persistSession(true)
            return // the timer keeps running through the background service
        }
        explicitClose()
    }

    /** The supported close operation (menu entry or notification action). */
    fun explicitClose() {
        stopAlarm()
        if (model.prefs.resetOnClose || !model.prefs.rememberAfterClosing) engine.reset()
        persistSession(true)
        model.flushNow()
        closed = true
        openPending = true
        visible = false
        TimerService.shutdown()
        try {
            app.getSystemService(NotificationManager::class.java)?.cancel(TimerService.NOTIF_ID)
        } catch (e: Throwable) {
        }
        scheduleAll()
        notifyTimerChanged()
        for (l in listeners) l.onClosed()
    }

    // ------------------------------------------------------------------ timer operations

    fun toggle(): Boolean {
        if (ringing) {
            stopAlarm()
            return true
        }
        val wasRunning = engine.isRunning()
        if (!engine.toggle()) {
            message("Scroll over the numbers to set a time first")
            return false
        }
        afterTimerChange(true)
        if (!wasRunning) for (l in listeners) l.onTimerStarted()
        return true
    }

    fun resetTimer() {
        if (ringing) stopAlarm()
        engine.reset()
        afterTimerChange(true)
    }

    fun setMode(mode: Int) {
        if (ringing) stopAlarm()
        engine.setMode(mode)
        afterTimerChange(true)
    }

    fun edit(c: Comp, steps: Int): Boolean {
        if (engine.isRunning() && !model.prefs.editWhileRunning) return false
        val ok = engine.edit(c, steps, model.prefs.rollover)
        if (ok) afterTimerChange(false)
        return ok
    }

    fun setTargetMs(ms: Long) {
        model.targetMs = if (ms < 0L) 0L else ms
        engine.targetFired = false
        model.markDirty(AppModel.D_ALARM)
        afterTimerChange(true)
    }

    fun onTimingPreferenceChanged() {
        afterTimerChange(true)
    }

    private fun afterTimerChange(immediate: Boolean) {
        persistSession(immediate)
        scheduleAll()
        syncService()
        notifyTimerChanged()
    }

    fun notifyTimerChanged() {
        for (l in listeners) l.onTimerChanged()
    }

    fun message(text: String) {
        for (l in listeners) l.onMessage(text)
    }

    fun resetAllSettings() {
        model.setDefaults()
        for (h in model.haptics) h.copyFrom(HapticConfig())
        model.background.readJson(BackgroundConfig().toJson())
        model.alarmFinish.readJson(AlarmConfig().toJson())
        model.alarmTarget.readJson(AlarmConfig().toJson())
        model.targetMs = 0L
        model.prefs.readJson(AppPrefs().toJson())
        model.masterIncludesGlobal = true
        model.markDirty(AppModel.D_ALL)
        for (l in listeners) l.onModelReset()
        afterTimerChange(true)
    }

    // ------------------------------------------------------------------ scheduling (haptics, alarms)

    private fun interval(i: Int): Long = max(20L, model.haptics[i].intervalMs)

    private fun hapticActive(i: Int): Boolean =
        !closed && model.prefs.hapticsMaster && model.haptics[i].enabled && engine.isRunning()

    private fun resetHapticBaseline() {
        hapticRev = engine.revision
        val p = engine.progressMs()
        for (i in 0 until 4) lastHapticIdx[i] = p / interval(i)
    }

    /** Milliseconds until the next haptic event of component [i], or -1. Events never extend past a countdown's duration. */
    private fun hapticDelay(i: Int): Long {
        if (!hapticActive(i)) return -1L
        val iv = interval(i)
        val k = lastHapticIdx[i] + 1L
        val at = k * iv
        if (engine.mode == TimeEngine.MODE_COUNTDOWN && at > engine.originMs) return -1L
        val d = at - engine.progressMs()
        return if (d < 0L) 0L else d
    }

    /** Milliseconds until the alarm condition (countdown reaches zero / stopwatch reaches its target), or -1. */
    private fun alarmDelay(): Long {
        if (engine.mode == TimeEngine.MODE_COUNTDOWN) return engine.msUntilFinish()
        if (!engine.isRunning() || engine.targetFired || model.targetMs <= 0L) return -1L
        val d = model.targetMs - engine.value()
        return if (d < 0L) 0L else d
    }

    private fun scheduleAll() {
        handler.removeCallbacks(tick)
        if (engine.revision != hapticRev) resetHapticBaseline()
        var next = Long.MAX_VALUE
        val a = alarmDelay()
        if (a >= 0L) next = a
        for (i in 0 until 4) {
            val d = hapticDelay(i)
            if (d in 0L until next) next = d
        }
        if (next != Long.MAX_VALUE) handler.postDelayed(tick, max(1L, next))
        scheduleWakeAlarm(a)
    }

    /** Fires whatever is due. Called by the Handler, and by the AlarmManager receiver when the process was asleep. */
    fun runDue() {
        if (engine.revision != hapticRev) resetHapticBaseline()
        val p = engine.progressMs()
        for (i in 0 until 4) {
            if (!hapticActive(i)) continue
            val iv = interval(i)
            val k = p / iv
            if (k > lastHapticIdx[i] && k >= 1L) {
                if (engine.mode != TimeEngine.MODE_COUNTDOWN || k * iv <= engine.originMs) {
                    HapticPlayer.play(app, model.haptics[i])
                }
                lastHapticIdx[i] = k
            }
        }
        if (engine.checkFinish()) {
            onFinishedCountdown()
        } else if (engine.mode == TimeEngine.MODE_STOPWATCH && engine.isRunning() && !engine.targetFired &&
            model.targetMs > 0L && engine.value() >= model.targetMs
        ) {
            engine.targetFired = true
            persistSession(true)
            ringAlarm(1)
            notifyTimerChanged()
        }
        scheduleAll()
    }

    private fun onFinishedCountdown() {
        persistSession(true)
        ringAlarm(0)
        syncService()
        notifyTimerChanged()
    }

    private fun scheduleWakeAlarm(delayMs: Long) {
        try {
            val am = app.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                app, 1, Intent(app, AlarmReceiver::class.java).setAction(ACTION_WAKE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (delayMs < 0L) {
                am.cancel(pi)
                return
            }
            val at = SystemClock.elapsedRealtime() + delayMs
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (e: Throwable) {
            // scheduling is best effort: the in-process timer still works while the app is alive
        }
    }

    // ------------------------------------------------------------------ alarm

    fun ringAlarm(which: Int) {
        val cfg = if (which == 0) model.alarmFinish else model.alarmTarget
        if (!cfg.enabled) return
        ringWhich = which
        ringing = true
        syncService()
        for (l in listeners) l.onRingingChanged()
    }

    fun stopAlarm() {
        if (!ringing) return
        ringing = false
        TimerService.instance?.refresh()
        for (l in listeners) l.onRingingChanged()
        syncService()
    }

    fun alarmConfig(): AlarmConfig = if (ringWhich == 0) model.alarmFinish else model.alarmTarget

    // ------------------------------------------------------------------ background service

    fun syncService() {
        val need = ringing || (!closed && model.prefs.runInBackground && engine.isRunning())
        val inst = TimerService.instance
        if (need && inst == null) TimerService.start(app) else inst?.refresh()
    }
}
