package com.timer.app

/**
 * Time Engine: pure Kotlin, no Android dependencies, independently testable.
 *
 * The displayed value is NEVER accumulated frame by frame. It is always derived from a monotonic
 * reference: value = base +/- (now - anchor). Cosmetic changes (fonts, rotation, background...)
 * cannot touch this class.
 */

enum class Comp {
    HOURS, MINUTES, SECONDS, MILLIS;

    companion object {
        val ALL: Array<Comp> = values()
    }
}

object Units {
    const val SEC = 1000L
    const val MIN = 60_000L
    const val HOUR = 3_600_000L

    /** Upper bound that only exists to make Long arithmetic overflow-proof (about 1.3 billion hours of headroom x 2). */
    const val MAX_MS = Long.MAX_VALUE / 4
}

/** Source of monotonic time. Production uses SystemClock.elapsedRealtimeNanos(). */
interface MonoClock {
    fun nanos(): Long
}

class Digits(var h: Long, var m: Int, var s: Int, var ms: Int) {
    fun toMs(): Long {
        val total = h * Units.HOUR + m * Units.MIN + s * Units.SEC + ms
        return if (total < 0L) 0L else if (total > Units.MAX_MS) Units.MAX_MS else total
    }

    companion object {
        fun from(totalMs: Long): Digits {
            val t = if (totalMs < 0L) 0L else totalMs
            return Digits(t / Units.HOUR, ((t / Units.MIN) % 60L).toInt(), ((t / Units.SEC) % 60L).toInt(), (t % 1000L).toInt())
        }
    }
}

/**
 * Rollover / borrow logic for editing one component.
 * rolls[0] = milliseconds -> seconds, rolls[1] = seconds -> minutes, rolls[2] = minutes -> hours.
 *
 * Rules (identical for increasing and decreasing):
 *  - Toggle ON : crossing the unit boundary carries into / borrows from the next component.
 *                If nothing can be borrowed (e.g. 00:00:00.000 going down) the value is clamped at zero.
 *  - Toggle OFF: the component wraps inside its own range and never touches the next component.
 *  - Hours have no maximum and no next component; they clamp at zero going down.
 */
object Rollover {
    private fun range(c: Comp): Long = when (c) {
        Comp.MILLIS -> 1000L
        Comp.SECONDS -> 60L
        Comp.MINUTES -> 60L
        Comp.HOURS -> Long.MAX_VALUE
    }

    private fun get(d: Digits, c: Comp): Long = when (c) {
        Comp.HOURS -> d.h
        Comp.MINUTES -> d.m.toLong()
        Comp.SECONDS -> d.s.toLong()
        Comp.MILLIS -> d.ms.toLong()
    }

    private fun put(d: Digits, c: Comp, v: Long) {
        when (c) {
            Comp.HOURS -> d.h = v
            Comp.MINUTES -> d.m = v.toInt()
            Comp.SECONDS -> d.s = v.toInt()
            Comp.MILLIS -> d.ms = v.toInt()
        }
    }

    private fun next(c: Comp): Comp? = when (c) {
        Comp.MILLIS -> Comp.SECONDS
        Comp.SECONDS -> Comp.MINUTES
        Comp.MINUTES -> Comp.HOURS
        Comp.HOURS -> null
    }

    private fun rollOn(c: Comp, rolls: BooleanArray): Boolean = when (c) {
        Comp.MILLIS -> rolls[0]
        Comp.SECONDS -> rolls[1]
        Comp.MINUTES -> rolls[2]
        Comp.HOURS -> false
    }

    private fun canDecrement(d: Digits, c: Comp, rolls: BooleanArray): Boolean {
        if (get(d, c) > 0L) return true
        if (c == Comp.HOURS) return false
        if (!rollOn(c, rolls)) return true // wraps inside its own range
        val nx = next(c) ?: return false
        return canDecrement(d, nx, rolls)
    }

    /** One unit step (dir = +1 or -1). Returns false when the step was clamped (nothing changed). */
    fun stepOnce(d: Digits, c: Comp, dir: Int, rolls: BooleanArray): Boolean {
        val cur = get(d, c)
        if (c == Comp.HOURS) {
            val n = cur + dir
            if (n < 0L || n > Units.MAX_MS / Units.HOUR) return false
            put(d, c, n)
            return true
        }
        val r = range(c)
        val n = cur + dir
        if (n in 0L until r) {
            put(d, c, n)
            return true
        }
        val nx = next(c) ?: return false
        if (dir > 0) {
            put(d, c, 0L)
            if (rollOn(c, rolls)) stepOnce(d, nx, 1, rolls)
            return true
        }
        // dir < 0, n == -1
        if (rollOn(c, rolls)) {
            if (!canDecrement(d, nx, rolls)) return false
            put(d, c, r - 1L)
            stepOnce(d, nx, -1, rolls)
            return true
        }
        put(d, c, r - 1L)
        return true
    }

    /** Applies |delta| unit steps. Stops early when clamped. */
    fun apply(d: Digits, c: Comp, delta: Int, rolls: BooleanArray): Boolean {
        val dir = if (delta >= 0) 1 else -1
        var n = if (delta >= 0) delta else -delta
        var changed = false
        while (n > 0) {
            if (!stepOnce(d, c, dir, rolls)) break
            changed = true
            n--
        }
        return changed
    }
}

/** Snapshot used by the persistence layer. */
class SavedTimer(
    var mode: Int = 0,
    var baseMs: Long = 0L,
    var running: Boolean = false,
    var anchorNanos: Long = 0L,
    var anchorWallMs: Long = 0L,
    var bootKey: Long = 0L,
    var originMs: Long = 0L,
    var finished: Boolean = false,
    var targetFired: Boolean = false
)

class TimeEngine(private val clock: MonoClock) {
    companion object {
        const val MODE_STOPWATCH = 0
        const val MODE_COUNTDOWN = 1
    }

    var mode: Int = MODE_STOPWATCH
        private set
    private var baseMs: Long = 0L
    private var running: Boolean = false
    private var anchorNanos: Long = 0L
    var originMs: Long = 0L
        private set
    var finished: Boolean = false
        private set
    var targetFired: Boolean = false

    /** Incremented on every discontinuity (start/pause/edit/reset/mode) so drivers can reschedule. */
    @Volatile
    var revision: Int = 0
        private set

    @Synchronized
    fun isRunning(): Boolean = running

    @Synchronized
    fun nowNanos(): Long = clock.nanos()

    private fun rawValue(now: Long): Long {
        if (!running) return baseMs
        val elapsedMs = (now - anchorNanos) / 1_000_000L
        val v = if (mode == MODE_COUNTDOWN) baseMs - elapsedMs else baseMs + elapsedMs
        return if (v > Units.MAX_MS) Units.MAX_MS else v
    }

    /** Current value in ms (never negative). */
    @Synchronized
    fun value(): Long {
        val v = rawValue(clock.nanos())
        return if (v < 0L) 0L else v
    }

    /** Elapsed progress of the current session in ms (used for haptic scheduling). */
    @Synchronized
    fun progressMs(): Long {
        val v = rawValue(clock.nanos())
        val p = if (mode == MODE_COUNTDOWN) originMs - (if (v < 0L) 0L else v) else v - originMs
        return if (p < 0L) 0L else p
    }

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        if (mode == MODE_COUNTDOWN && baseMs <= 0L) {
            // Nothing to count down from: tapping at all zeros starts a stopwatch.
            mode = MODE_STOPWATCH
            baseMs = 0L
            originMs = 0L
            targetFired = false
        }
        running = true
        anchorNanos = clock.nanos()
        finished = false
        revision++
        return true
    }

    @Synchronized
    fun pause() {
        if (!running) return
        val v = rawValue(clock.nanos())
        baseMs = if (v < 0L) 0L else v
        running = false
        revision++
    }

    @Synchronized
    fun toggle(): Boolean {
        if (running) {
            pause()
            return true
        }
        return start()
    }

    /** Resets numerical values only (00:00:00.000) and stops. */
    @Synchronized
    fun reset() {
        baseMs = 0L
        running = false
        finished = false
        targetFired = false
        originMs = 0L
        mode = MODE_STOPWATCH
        revision++
    }

    @Synchronized
    fun setMode(newMode: Int) {
        if (newMode == mode) return
        val v = value()
        mode = newMode
        running = false
        baseMs = v
        originMs = v
        finished = false
        targetFired = false
        revision++
    }

    @Synchronized
    fun setValue(ms: Long) {
        val v = if (ms < 0L) 0L else if (ms > Units.MAX_MS) Units.MAX_MS else ms
        baseMs = v
        if (running) anchorNanos = clock.nanos()
        originMs = v
        finished = false
        targetFired = false
        revision++
    }

    /**
     * Edits one component by [delta] units using the configured rollover relationships.
     * Works while running as well (the caller decides whether that is allowed).
     */
    @Synchronized
    fun edit(c: Comp, delta: Int, rolls: BooleanArray): Boolean {
        val now = clock.nanos()
        // Scrolling a stopwatch turns the timer into a countdown that starts again from zero.
        val toCountdown = mode == MODE_STOPWATCH
        val cur = if (toCountdown) 0L else rawValue(now).let { if (it < 0L) 0L else it }
        val d = Digits.from(cur)
        val changed = Rollover.apply(d, c, delta, rolls)
        if (!changed) return false
        if (toCountdown) mode = MODE_COUNTDOWN // a running stopwatch keeps running, now counting down
        baseMs = d.toMs()
        if (running) anchorNanos = now
        originMs = baseMs
        finished = false
        targetFired = false
        revision++
        return true
    }

    /** Detects countdown completion. Returns true exactly once when the countdown has just finished. */
    @Synchronized
    fun checkFinish(): Boolean {
        if (!running || mode != MODE_COUNTDOWN) return false
        if (rawValue(clock.nanos()) > 0L) return false
        baseMs = 0L
        running = false
        finished = true
        revision++
        return true
    }

    /** Milliseconds until a running countdown reaches zero, or -1 when not applicable. */
    @Synchronized
    fun msUntilFinish(): Long {
        if (!running || mode != MODE_COUNTDOWN) return -1L
        val v = rawValue(clock.nanos())
        return if (v < 0L) 0L else v
    }

    @Synchronized
    fun snapshot(wallMs: Long, bootKey: Long): SavedTimer {
        return SavedTimer(mode, baseMs, running, anchorNanos, wallMs, bootKey, originMs, finished, targetFired)
    }

    /**
     * Restores a snapshot after process death / device restart.
     * @param sameBoot true when the monotonic clock is still the same one the snapshot was taken with
     * @param countOffTime when the device restarted, count the wall-clock time that passed while it was off
     * @return true when a running countdown completed while the app was away
     */
    @Synchronized
    fun restore(s: SavedTimer, sameBoot: Boolean, nowWallMs: Long, countOffTime: Boolean): Boolean {
        mode = if (s.mode == MODE_COUNTDOWN) MODE_COUNTDOWN else MODE_STOPWATCH
        baseMs = if (s.baseMs < 0L) 0L else if (s.baseMs > Units.MAX_MS) Units.MAX_MS else s.baseMs
        originMs = if (s.originMs < 0L) 0L else s.originMs
        finished = s.finished
        targetFired = s.targetFired
        running = false
        if (s.running) {
            val now = clock.nanos()
            val elapsedMs: Long = if (sameBoot) {
                val e = (now - s.anchorNanos) / 1_000_000L
                if (e < 0L) 0L else e
            } else if (countOffTime) {
                val e = nowWallMs - s.anchorWallMs
                if (e < 0L) 0L else e
            } else {
                0L
            }
            running = true
            anchorNanos = now - elapsedMs * 1_000_000L
        }
        revision++
        return checkFinish()
    }
}
