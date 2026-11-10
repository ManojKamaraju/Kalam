package com.timer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Virtual clock + simulated fingers for the gesture state machine. */
class Sim : GestureHost, GestureListener {
    var t = 0L
    val timers = HashMap<Int, Long>()
    val log = ArrayList<String>()
    val cfg = GestureConfig()
    val engine = GestureEngine(this, this, cfg)

    var elementAt: (Float, Float) -> Int = { _, _ -> -1 }

    // totals
    var steps = 0
    var globalScale = 1f
    var elementScale = 1f
    var elementScaleTarget = -1
    var rotation = 0f
    var dragDx = 0f
    var dragDy = 0f

    init {
        cfg.density = 1f
    }

    override fun hitElement(x: Float, y: Float): Int = elementAt(x, y)
    override fun nearestElement(x: Float, y: Float): Int = if (x < 200f) 0 else 3
    override fun schedule(timerId: Int, delayMs: Long) {
        timers[timerId] = t + delayMs
    }

    override fun cancel(timerId: Int) {
        timers.remove(timerId)
    }

    override fun onSingleTap(x: Float, y: Float) { log.add("single") }
    override fun onDoubleTap(x: Float, y: Float) { log.add("double") }
    override fun onTripleTap(x: Float, y: Float) { log.add("triple") }
    override fun onLongPress(x: Float, y: Float, target: Int) { log.add("long:$target") }
    override fun onScrollSteps(comp: Int, steps: Int) { this.steps += steps; log.add("scroll:$comp") }
    override fun onDragStart(comp: Int) { log.add("dragStart:$comp") }
    override fun onDragMove(comp: Int, dx: Float, dy: Float) { dragDx += dx; dragDy += dy }
    override fun onDragEnd(comp: Int) { log.add("dragEnd:$comp") }
    override fun onGlobalScale(factor: Float) { globalScale *= factor; log.add("gscale") }
    override fun onElementScale(comp: Int, factor: Float) { elementScale *= factor; elementScaleTarget = comp; log.add("escale") }
    override fun onRotate(deltaDeg: Float) { rotation += deltaDeg; log.add("rotate") }
    override fun onTransformEnd() { log.add("end") }
    override fun onFeedback(kind: Int) {}

    fun advance(ms: Long) {
        val end = t + ms
        while (true) {
            var bestId = -1
            var bestAt = Long.MAX_VALUE
            for ((id, at) in timers) if (at <= end && at < bestAt) { bestId = id; bestAt = at }
            if (bestId < 0) break
            timers.remove(bestId)
            t = bestAt
            engine.onTimer(bestId)
        }
        t = end
    }

    fun down(id: Int, x: Float, y: Float) = engine.down(id, x, y, t)
    fun up(id: Int) = engine.up(id, t)
    fun move1(x: Float, y: Float) = engine.move(intArrayOf(0), floatArrayOf(x), floatArrayOf(y), 1, t)
    fun move2(xa: Float, ya: Float, xb: Float, yb: Float) =
        engine.move(intArrayOf(0, 1), floatArrayOf(xa, xb), floatArrayOf(ya, yb), 2, t)

    fun tap(x: Float = 500f, y: Float = 900f) {
        down(0, x, y)
        advance(60)
        up(0)
        advance(60)
    }
}

class GestureEngineTest {
    @Test
    fun singleTapFiresOnlyAfterWindow() {
        val s = Sim()
        s.tap()
        assertEquals(0, s.log.size)
        s.advance(300)
        assertEquals(listOf("single"), s.log)
    }

    @Test
    fun doubleTapIsNotPrecededBySingle() {
        val s = Sim()
        s.tap(); s.tap()
        s.advance(400)
        assertEquals(listOf("double"), s.log)
    }

    @Test
    fun tripleTapDoesNotFireDoubleFirst() {
        val s = Sim()
        s.tap(); s.tap(); s.tap()
        s.advance(500)
        assertEquals(listOf("triple"), s.log)
    }

    @Test
    fun twoSeparateTapsFarApartInTimeAreTwoSingles() {
        val s = Sim()
        s.tap()
        s.advance(400)
        s.tap()
        s.advance(400)
        assertEquals(listOf("single", "single"), s.log)
    }

    @Test
    fun longPressOnBackgroundOpensMenu() {
        val s = Sim()
        s.down(0, 300f, 300f)
        s.advance(500)
        assertEquals(listOf("long:-1"), s.log)
        s.up(0)
        s.advance(500)
        assertEquals(listOf("long:-1"), s.log) // no tap after a long press
    }

    @Test
    fun longPressOnNumberReportsTarget() {
        val s = Sim()
        s.elementAt = { _, _ -> 2 }
        s.down(0, 300f, 300f)
        s.advance(500)
        assertEquals(listOf("long:2"), s.log)
    }

    @Test
    fun quickVerticalSwipeOnNumberScrollsUpAndDown() {
        val s = Sim()
        s.elementAt = { _, _ -> 1 }
        s.down(0, 300f, 600f)
        s.advance(20)
        s.move1(300f, 560f) // 40 px up in 20 ms
        s.advance(20)
        s.move1(300f, 520f)
        s.up(0)
        assertTrue("steps should be positive: ${s.steps}", s.steps > 0)
        assertFalse(s.log.contains("dragStart:1"))
        val up = s.steps
        s.down(0, 300f, 400f)
        s.advance(20)
        s.move1(300f, 460f)
        s.advance(20)
        s.move1(300f, 520f)
        s.up(0)
        assertTrue("steps should have decreased", s.steps < up)
        s.advance(600)
        assertFalse(s.log.any { it.startsWith("single") || it.startsWith("long") })
    }

    @Test
    fun holdThenMoveDragsInsteadOfScrolling() {
        val s = Sim()
        s.elementAt = { _, _ -> 2 }
        s.down(0, 300f, 600f)
        s.advance(250) // past pick-up delay, before long press
        s.move1(330f, 640f)
        s.advance(16)
        s.move1(360f, 700f)
        s.up(0)
        assertEquals(0, s.steps)
        assertTrue(s.log.contains("dragStart:2"))
        assertTrue(s.log.contains("dragEnd:2"))
        assertEquals(60f, s.dragDx, 0.01f)
        assertEquals(100f, s.dragDy, 0.01f)
    }

    @Test
    fun movingOnBackgroundDoesNothingAndCancelsTap() {
        val s = Sim()
        s.down(0, 300f, 600f)
        s.advance(30)
        s.move1(300f, 700f)
        s.up(0)
        s.advance(600)
        assertEquals(0, s.log.size)
    }

    @Test
    fun horizontalPinchScalesEverythingOnly() {
        val s = Sim()
        s.elementAt = { _, _ -> 1 }
        s.down(0, 300f, 600f)
        s.down(1, 500f, 600f)
        for (i in 1..10) {
            s.advance(16)
            s.move2(300f - i * 10, 600f, 500f + i * 10, 600f)
        }
        s.up(1); s.up(0)
        assertTrue("global scale should grow: ${s.globalScale}", s.globalScale > 1.5f)
        assertEquals(1f, s.elementScale, 0.0001f)
        assertEquals(0f, s.rotation, 0.0001f)
        assertEquals(0, s.steps)
    }

    @Test
    fun verticalPinchScalesOneElementAndNeverScrolls() {
        val s = Sim()
        s.elementAt = { _, _ -> -1 }
        s.down(0, 100f, 500f)
        s.down(1, 100f, 700f)
        for (i in 1..10) {
            s.advance(16)
            s.move2(100f, 500f - i * 10, 100f, 700f + i * 10)
        }
        s.up(1); s.up(0)
        assertTrue(s.elementScale > 1.5f)
        assertEquals(0, s.elementScaleTarget) // x=100 -> nearest is element 0 in the Sim
        assertEquals(1f, s.globalScale, 0.0001f)
        assertEquals(0, s.steps)
    }

    @Test
    fun twistRotatesButDoesNotScale() {
        val s = Sim()
        s.down(0, 300f, 600f)
        s.down(1, 500f, 600f)
        // rotate the second finger around the first by 40 degrees, constant distance
        for (i in 1..8) {
            s.advance(16)
            val a = Math.toRadians(5.0 * i)
            s.move2(300f, 600f, (300f + 200f * Math.cos(a)).toFloat(), (600f + 200f * Math.sin(a)).toFloat())
        }
        s.up(1); s.up(0)
        assertEquals(40f, s.rotation, 1.0f)
        assertEquals(1f, s.globalScale, 0.01f)
        assertEquals(1f, s.elementScale, 0.01f)
    }

    @Test
    fun gestureLocksOncePinchStarted() {
        val s = Sim()
        s.down(0, 300f, 600f)
        s.down(1, 500f, 600f)
        for (i in 1..5) { s.advance(16); s.move2(300f - i * 10, 600f, 500f + i * 10, 600f) }
        // now twist while pinching: must keep scaling, never rotate
        for (i in 1..5) { s.advance(16); s.move2(250f, 600f - i * 10, 550f, 600f + i * 10) }
        s.up(1); s.up(0)
        assertEquals(0f, s.rotation, 0.0001f)
    }

    @Test
    fun secondFingerCancelsPendingScrollAndTap() {
        val s = Sim()
        s.elementAt = { _, _ -> 1 }
        s.down(0, 300f, 600f)
        s.down(1, 400f, 600f)
        s.up(1); s.up(0)
        s.advance(600)
        assertEquals(0, s.steps)
        assertFalse(s.log.contains("single"))
    }
}
