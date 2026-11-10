package com.timer.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Gesture Engine: a deterministic state machine. Pure Kotlin (no Android types) so it can be unit tested
 * with simulated pointer streams and a virtual clock.
 *
 * Resolution rules:
 *  - Finger lands on a number and moves BEFORE the pick-up delay  -> vertical scroll (changes the value).
 *  - Finger lands on a number, rests for the pick-up delay, then moves -> drag that number anywhere.
 *  - Finger rests (no movement) for the long-press delay           -> contextual menu (number or background).
 *  - Quick tap(s) on the background                               -> 1 = pause/resume, 2 = reset, 3 = Master Layout.
 *    Taps are only reported after the tap window closes (or immediately at the 3rd tap), so a triple tap can
 *    never fire the double-tap action first.
 *  - Two fingers: the first threshold crossed locks the gesture for its whole lifetime:
 *      angle change  -> rotation of the whole composition
 *      distance change, fingers arranged horizontally -> global scale
 *      distance change, fingers arranged vertically   -> scale of the single nearest number
 */
interface GestureHost {
    /** Returns the Comp ordinal under the point, or -1 for background. */
    fun hitElement(x: Float, y: Float): Int

    /** Returns the Comp ordinal closest to the point (proximity based, never -1). */
    fun nearestElement(x: Float, y: Float): Int

    fun schedule(timerId: Int, delayMs: Long)
    fun cancel(timerId: Int)
}

interface GestureListener {
    fun onSingleTap(x: Float, y: Float)
    fun onDoubleTap(x: Float, y: Float)
    fun onTripleTap(x: Float, y: Float)
    fun onLongPress(x: Float, y: Float, target: Int)
    fun onScrollSteps(comp: Int, steps: Int)
    fun onDragStart(comp: Int)
    fun onDragMove(comp: Int, dx: Float, dy: Float)
    fun onDragEnd(comp: Int)
    fun onGlobalScale(factor: Float)
    fun onElementScale(comp: Int, factor: Float)
    fun onRotate(deltaDeg: Float)
    fun onTransformEnd()
    fun onFeedback(kind: Int)
}

class GestureConfig {
    var density: Float = 1f
    var scrollStepDp: Float = 16f
    var pickupMs: Long = 180L
    var longPressMs: Long = 450L
    var tapWindowMs: Long = 280L
    var slopDp: Float = 8f
    var tapDistanceDp: Float = 56f
    var pinchSlopDp: Float = 14f
    var rotateSlopDeg: Float = 9f
    var pinchSensitivity: Float = 1f
    var rotateSensitivity: Float = 1f
    var elementTapIsBackgroundTap: Boolean = true
}

class GestureEngine(
    private val host: GestureHost,
    private val listener: GestureListener,
    val cfg: GestureConfig
) {
    companion object {
        const val T_LONG = 1
        const val T_PICKUP = 2
        const val T_TAP = 3

        const val FB_PICKUP = 1
        const val FB_LONGPRESS = 2
        const val FB_PINCH = 3
        const val FB_ROTATE = 4
        const val FB_TRIPLE = 5
        const val FB_DOUBLE = 6

        private const val S_IDLE = 0
        private const val S_DOWN = 1
        private const val S_SCROLL = 2
        private const val S_ARMED = 3
        private const val S_DRAG = 4
        private const val S_MENU = 5
        private const val S_MULTI_PENDING = 6
        private const val S_PINCH_G = 7
        private const val S_PINCH_E = 8
        private const val S_ROTATE = 9
        private const val S_IGNORE = 10
    }

    private var state = S_IDLE
    private var pCount = 0
    private var id1 = -1
    private var id2 = -1
    private var x1 = 0f
    private var y1 = 0f
    private var x2 = 0f
    private var y2 = 0f

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var target = -1
    private var scrollAccum = 0f
    private var speed = 0f // px per ms, smoothed

    private var d0 = 0f
    private var a0 = 0f
    private var lastD = 0f
    private var lastA = 0f
    private var targetE = 0

    private var tapCount = 0
    private var tapX = 0f
    private var tapY = 0f

    private val slopPx: Float get() = cfg.slopDp * cfg.density
    private val stepPx: Float get() = cfg.scrollStepDp * cfg.density

    fun isInteracting(): Boolean = state != S_IDLE

    // ------------------------------------------------------------------ input

    fun down(id: Int, x: Float, y: Float, t: Long) {
        if (pCount == 0) startSingle(id, x, y, t) else if (pCount == 1) startMulti(id, x, y)
    }

    /** ids/xs/ys describe all pointers currently down. */
    fun move(ids: IntArray, xs: FloatArray, ys: FloatArray, count: Int, t: Long) {
        for (i in 0 until count) {
            if (ids[i] == id1) {
                x1 = xs[i]; y1 = ys[i]
            } else if (pCount == 2 && ids[i] == id2) {
                x2 = xs[i]; y2 = ys[i]
            }
        }
        if (pCount == 1) singleMove(x1, y1, t) else if (pCount == 2) multiMove()
    }

    fun up(id: Int, t: Long) {
        if (pCount == 1) {
            if (id != id1) return
            host.cancel(T_LONG)
            host.cancel(T_PICKUP)
            when (state) {
                S_DOWN, S_ARMED -> {
                    val isTap = (t - downT) < cfg.longPressMs
                    if (isTap && (target < 0 || cfg.elementTapIsBackgroundTap)) {
                        tapCount++
                        tapX = downX
                        tapY = downY
                        if (tapCount >= 3) {
                            host.cancel(T_TAP)
                            resolveTaps()
                        } else {
                            host.schedule(T_TAP, cfg.tapWindowMs)
                        }
                    } else if (tapCount > 0) {
                        host.cancel(T_TAP)
                        resolveTaps()
                    }
                }
                S_DRAG -> listener.onDragEnd(target)
                else -> {}
            }
            pCount = 0
            state = S_IDLE
        } else if (pCount == 2) {
            if (id != id1 && id != id2) return
            if (state == S_PINCH_G || state == S_PINCH_E || state == S_ROTATE) listener.onTransformEnd()
            if (id == id1) {
                id1 = id2; x1 = x2; y1 = y2
            }
            id2 = -1
            pCount = 1
            state = S_IGNORE
        }
    }

    fun cancel() {
        host.cancel(T_LONG)
        host.cancel(T_PICKUP)
        host.cancel(T_TAP)
        if (state == S_DRAG) listener.onDragEnd(target)
        if (state == S_PINCH_G || state == S_PINCH_E || state == S_ROTATE) listener.onTransformEnd()
        tapCount = 0
        pCount = 0
        state = S_IDLE
    }

    fun onTimer(timerId: Int) {
        when (timerId) {
            T_PICKUP -> if (state == S_DOWN && target >= 0) {
                state = S_ARMED
                listener.onFeedback(FB_PICKUP)
            }
            T_LONG -> if (state == S_DOWN || state == S_ARMED) {
                host.cancel(T_PICKUP)
                state = S_MENU
                if (tapCount > 0) {
                    host.cancel(T_TAP)
                    resolveTaps()
                }
                listener.onFeedback(FB_LONGPRESS)
                listener.onLongPress(downX, downY, target)
            }
            T_TAP -> resolveTaps()
        }
    }

    // ------------------------------------------------------------------ internals

    private fun resolveTaps() {
        val c = tapCount
        tapCount = 0
        when (c) {
            1 -> listener.onSingleTap(tapX, tapY)
            2 -> {
                listener.onFeedback(FB_DOUBLE)
                listener.onDoubleTap(tapX, tapY)
            }
            3 -> {
                listener.onFeedback(FB_TRIPLE)
                listener.onTripleTap(tapX, tapY)
            }
        }
    }

    private fun startSingle(id: Int, x: Float, y: Float, t: Long) {
        id1 = id
        x1 = x
        y1 = y
        pCount = 1
        downX = x
        downY = y
        downT = t
        lastX = x
        lastY = y
        lastT = t
        scrollAccum = 0f
        speed = 0f
        if (tapCount > 0) {
            host.cancel(T_TAP)
            if (hypot(x - tapX, y - tapY) > cfg.tapDistanceDp * cfg.density) resolveTaps()
        }
        target = host.hitElement(x, y)
        if (tapCount > 0 && target >= 0 && !cfg.elementTapIsBackgroundTap) resolveTaps()
        state = S_DOWN
        host.schedule(T_LONG, cfg.longPressMs)
        if (target >= 0) host.schedule(T_PICKUP, cfg.pickupMs)
    }

    private fun startMulti(id: Int, x: Float, y: Float) {
        host.cancel(T_LONG)
        host.cancel(T_PICKUP)
        id2 = id
        x2 = x
        y2 = y
        pCount = 2
        if (state == S_MENU) {
            state = S_IGNORE
            return
        }
        if (state == S_DRAG) listener.onDragEnd(target)
        if (tapCount > 0) {
            host.cancel(T_TAP)
            resolveTaps()
        }
        d0 = hypot(x2 - x1, y2 - y1)
        a0 = angleDeg()
        lastD = d0
        lastA = a0
        state = S_MULTI_PENDING
    }

    private fun angleDeg(): Float = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()

    private fun angDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > 180f) d -= 360f
        while (d <= -180f) d += 360f
        return d
    }

    private fun singleMove(x: Float, y: Float, t: Long) {
        val moved = hypot(x - downX, y - downY)
        when (state) {
            S_DOWN -> if (moved > slopPx) {
                host.cancel(T_LONG)
                if (target >= 0) {
                    host.cancel(T_PICKUP)
                    state = S_SCROLL
                    lastY = downY
                    lastT = downT
                    scrollAccum = 0f
                    speed = 0f
                    processScroll(y, t)
                } else {
                    state = S_IGNORE
                    if (tapCount > 0) {
                        host.cancel(T_TAP)
                        resolveTaps()
                    }
                }
            }
            S_ARMED -> if (moved > slopPx) {
                host.cancel(T_LONG)
                state = S_DRAG
                lastX = downX
                lastY = downY
                listener.onDragStart(target)
                listener.onDragMove(target, x - lastX, y - lastY)
                lastX = x
                lastY = y
            }
            S_SCROLL -> processScroll(y, t)
            S_DRAG -> {
                listener.onDragMove(target, x - lastX, y - lastY)
                lastX = x
                lastY = y
            }
            else -> {}
        }
    }

    private fun multiplier(comp: Int, speedPxPerMs: Float): Int {
        val dpPerSec = speedPxPerMs * 1000f / cfg.density
        val tier = (dpPerSec / 300f).toInt()
        return if (comp == Comp.MILLIS.ordinal) {
            if (tier <= 0) 1 else if (tier == 1) 10 else 100
        } else {
            1 + (if (tier > 9) 9 else tier)
        }
    }

    private fun processScroll(y: Float, t: Long) {
        val dt = if (t - lastT < 1L) 1L else t - lastT
        val inst = abs(y - lastY) / dt
        speed = speed * 0.7f + inst * 0.3f
        scrollAccum += (lastY - y) // finger moving up = positive = increase
        lastY = y
        lastT = t
        val steps = (scrollAccum / stepPx).toInt()
        if (steps != 0) {
            scrollAccum -= steps * stepPx
            listener.onScrollSteps(target, steps * multiplier(target, speed))
        }
    }

    private fun multiMove() {
        val d = hypot(x2 - x1, y2 - y1)
        val a = angleDeg()
        when (state) {
            S_MULTI_PENDING -> {
                val dAng = angDiff(a, a0)
                val rotScore = abs(dAng) / cfg.rotateSlopDeg
                val pinchScore = abs(d - d0) / (cfg.pinchSlopDp * cfg.density)
                if (rotScore >= 1f && rotScore >= pinchScore) {
                    state = S_ROTATE
                    lastA = a0
                    listener.onFeedback(FB_ROTATE)
                    rotateTo(a)
                } else if (pinchScore >= 1f) {
                    val ang = Math.toDegrees(atan2(abs(y2 - y1).toDouble(), abs(x2 - x1).toDouble()))
                    lastD = d0
                    if (ang < 45.0) {
                        state = S_PINCH_G
                    } else {
                        state = S_PINCH_E
                        targetE = host.nearestElement((x1 + x2) / 2f, (y1 + y2) / 2f)
                    }
                    listener.onFeedback(FB_PINCH)
                    pinchTo(d)
                }
            }
            S_PINCH_G, S_PINCH_E -> pinchTo(d)
            S_ROTATE -> rotateTo(a)
            else -> {}
        }
    }

    private fun pinchTo(d: Float) {
        if (lastD > 1e-3f && d > 1e-3f) {
            val f = (d / lastD).toDouble().pow(cfg.pinchSensitivity.toDouble()).toFloat()
            if (state == S_PINCH_G) listener.onGlobalScale(f) else listener.onElementScale(targetE, f)
        }
        lastD = d
    }

    private fun rotateTo(a: Float) {
        val delta = angDiff(a, lastA) * cfg.rotateSensitivity
        if (delta != 0f) listener.onRotate(delta)
        lastA = a
    }
}
