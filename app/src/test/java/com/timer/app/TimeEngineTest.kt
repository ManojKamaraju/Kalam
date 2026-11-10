package com.timer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeClock : MonoClock {
    var ms: Long = 1_000_000L
    override fun nanos(): Long = ms * 1_000_000L
    fun advance(d: Long) {
        ms += d
    }
}

class TimeEngineTest {
    private val ALL = booleanArrayOf(true, true, true)

    private fun ms(h: Long, m: Int, s: Int, ms: Int): Long = h * Units.HOUR + m * Units.MIN + s * Units.SEC + ms

    private fun engineAt(v: Long): Pair<TimeEngine, FakeClock> {
        val c = FakeClock()
        val e = TimeEngine(c)
        e.setValue(v)
        return Pair(e, c)
    }

    private fun cdAt(v: Long): Pair<TimeEngine, FakeClock> {
        val c = FakeClock()
        val e = TimeEngine(c)
        e.setMode(TimeEngine.MODE_COUNTDOWN)
        e.setValue(v)
        return Pair(e, c)
    }

    @Test
    fun tapAtZerosStartsStopwatch() {
        val c = FakeClock()
        val e = TimeEngine(c)
        assertTrue(e.start())
        c.advance(1500)
        assertEquals(TimeEngine.MODE_STOPWATCH, e.mode)
        assertEquals(1500L, e.value())
    }

    @Test
    fun scrollingStopwatchSwitchesToCountdownFromZero() {
        val (e, c) = engineAt(0)
        e.start()
        c.advance(5000)
        e.edit(Comp.SECONDS, 3, ALL)
        assertEquals(TimeEngine.MODE_COUNTDOWN, e.mode)
        assertTrue(e.isRunning()) // was running, so it counts back immediately
        assertEquals(3000L, e.value())
        c.advance(1000)
        assertEquals(2000L, e.value())
    }

    @Test
    fun scrollingPausedStopwatchStaysPaused() {
        val (e, c) = engineAt(0)
        e.start()
        c.advance(5000)
        e.pause()
        e.edit(Comp.SECONDS, 3, ALL)
        assertEquals(TimeEngine.MODE_COUNTDOWN, e.mode)
        assertFalse(e.isRunning())
        assertEquals(3000L, e.value())
    }

    @Test
    fun finishedCountdownThenTapIsStopwatch() {
        val (e, c) = cdAt(1000)
        e.start()
        c.advance(1000)
        assertTrue(e.checkFinish())
        assertTrue(e.start())
        c.advance(400)
        assertEquals(TimeEngine.MODE_STOPWATCH, e.mode)
        assertEquals(400L, e.value())
    }

    @Test
    fun stopwatchCountsUpWithoutDrift() {
        val (e, c) = engineAt(0)
        assertTrue(e.start())
        for (i in 1..1000) c.advance(7) // 7000 ms in many small steps
        assertEquals(7000L, e.value())
        c.advance(234)
        assertEquals(7234L, e.value())
    }

    @Test
    fun pauseFreezesAndResumeContinues() {
        val (e, c) = engineAt(1000)
        e.start()
        c.advance(500)
        e.pause()
        c.advance(10_000)
        assertEquals(1500L, e.value())
        e.start()
        c.advance(250)
        assertEquals(1750L, e.value())
    }

    @Test
    fun countdownFinishesExactlyOnce() {
        val (e, c) = engineAt(90_000)
        e.setMode(TimeEngine.MODE_COUNTDOWN)
        assertEquals(90_000L, e.value())
        e.start()
        c.advance(30_000)
        assertEquals(60_000L, e.value())
        assertFalse(e.checkFinish())
        c.advance(60_000)
        assertTrue(e.checkFinish())
        assertFalse(e.checkFinish())
        assertEquals(0L, e.value())
        assertFalse(e.isRunning())
        assertTrue(e.finished)
    }

    @Test
    fun countdownAtZeroFallsBackToStopwatch() {
        val c = FakeClock()
        val e = TimeEngine(c)
        e.setMode(TimeEngine.MODE_COUNTDOWN)
        assertTrue(e.start())
        assertEquals(TimeEngine.MODE_STOPWATCH, e.mode)
    }

    @Test
    fun resetOnlyTouchesNumbers() {
        val (e, c) = engineAt(5000)
        e.start()
        c.advance(100)
        e.reset()
        assertEquals(0L, e.value())
        assertFalse(e.isRunning())
    }

    @Test
    fun millisecondRolloverOn() {
        val (e, _) = cdAt(ms(0, 0, 0, 999))
        e.edit(Comp.MILLIS, 1, ALL)
        assertEquals(ms(0, 0, 1, 0), e.value())
    }

    @Test
    fun millisecondRolloverOffWrapsInsideComponent() {
        val (e, _) = cdAt(ms(0, 0, 5, 999))
        e.edit(Comp.MILLIS, 1, booleanArrayOf(false, true, true))
        assertEquals(ms(0, 0, 5, 0), e.value())
    }

    @Test
    fun secondsRolloverOnAndOff() {
        val (e, _) = cdAt(ms(0, 0, 59, 0))
        e.edit(Comp.SECONDS, 1, ALL)
        assertEquals(ms(0, 1, 0, 0), e.value())
        val (f, _) = cdAt(ms(0, 0, 59, 0))
        f.edit(Comp.SECONDS, 1, booleanArrayOf(true, false, true))
        assertEquals(ms(0, 0, 0, 0), f.value())
    }

    @Test
    fun minutesRolloverToHours() {
        val (e, _) = cdAt(ms(0, 59, 0, 0))
        e.edit(Comp.MINUTES, 1, ALL)
        assertEquals(ms(1, 0, 0, 0), e.value())
        val (f, _) = cdAt(ms(0, 59, 0, 0))
        f.edit(Comp.MINUTES, 1, booleanArrayOf(true, true, false))
        assertEquals(ms(0, 0, 0, 0), f.value())
    }

    @Test
    fun decreasingBorrowsWhenEnabled() {
        val (e, _) = cdAt(ms(0, 1, 0, 0))
        e.edit(Comp.SECONDS, -1, ALL)
        assertEquals(ms(0, 0, 59, 0), e.value())
    }

    @Test
    fun decreasingWrapsWhenDisabled() {
        val (e, _) = cdAt(ms(0, 1, 0, 0))
        e.edit(Comp.SECONDS, -1, booleanArrayOf(true, false, true))
        assertEquals(ms(0, 1, 59, 0), e.value())
    }

    @Test
    fun decreasingClampsAtZeroWhenNothingToBorrow() {
        val (e, _) = engineAt(0)
        assertFalse(e.edit(Comp.MILLIS, -1, ALL))
        assertEquals(0L, e.value())
        assertFalse(e.edit(Comp.HOURS, -1, ALL))
        // with toggle off it wraps instead
        assertTrue(e.edit(Comp.MILLIS, -1, booleanArrayOf(false, true, true)))
        assertEquals(999L, e.value())
    }

    @Test
    fun cascadeFollowsEachIndependentToggle() {
        val (e, _) = cdAt(ms(0, 59, 59, 999))
        e.edit(Comp.MILLIS, 1, ALL)
        assertEquals(ms(1, 0, 0, 0), e.value())
        // ms->s on, s->m OFF: seconds wrap, minutes untouched
        val (f, _) = cdAt(ms(0, 12, 59, 999))
        f.edit(Comp.MILLIS, 1, booleanArrayOf(true, false, true))
        assertEquals(ms(0, 12, 0, 0), f.value())
    }

    @Test
    fun changingOneComponentDoesNotTouchOthers() {
        val (e, _) = cdAt(ms(3, 20, 10, 500))
        e.edit(Comp.HOURS, 4, ALL)
        assertEquals(ms(7, 20, 10, 500), e.value())
        e.edit(Comp.MINUTES, -5, ALL)
        assertEquals(ms(7, 15, 10, 500), e.value())
    }

    @Test
    fun hoursAreUnbounded() {
        val (e, _) = cdAt(10_000L * Units.HOUR)
        e.edit(Comp.HOURS, 1, ALL)
        assertEquals(10_001L * Units.HOUR, e.value())
        e.setValue(1_000_000L * Units.HOUR)
        e.edit(Comp.HOURS, 1, ALL)
        assertEquals(1_000_001L * Units.HOUR, e.value())
    }

    @Test
    fun editWhileRunningKeepsRunningFromNewValue() {
        val (e, c) = cdAt(20_000)
        e.start()
        c.advance(500)
        e.edit(Comp.SECONDS, 10, ALL)
        assertEquals(29_500L, e.value())
        c.advance(100)
        assertEquals(29_400L, e.value())
    }

    @Test
    fun progressMatchesModes() {
        val (e, c) = engineAt(10_000)
        e.start()
        c.advance(3000)
        assertEquals(3000L, e.progressMs())
        e.setMode(TimeEngine.MODE_COUNTDOWN)
        e.start()
        c.advance(4000)
        assertEquals(4000L, e.progressMs())
    }

    @Test
    fun restoreSameBootAccountsElapsedTime() {
        val (e, c) = engineAt(1000)
        e.start()
        c.advance(500)
        val snap = e.snapshot(5_000_000L, 7L)
        val c2 = FakeClock()
        c2.ms = c.ms + 2000 // same boot: monotonic clock moved on by 2 s
        val e2 = TimeEngine(c2)
        e2.restore(snap, true, 5_002_000L, true)
        assertTrue(e2.isRunning())
        // 1000 base + 500 before the snapshot + 2000 while the process was away
        assertEquals(3500L, e2.value())
    }

    @Test
    fun restoreAfterRebootUsesWallClockWhenAllowed() {
        val c = FakeClock()
        val s = SavedTimer(0, 1000L, true, 123L, 10_000L, 1L, 0L, false, false)
        val e = TimeEngine(c)
        e.restore(s, false, 14_000L, true)
        assertEquals(5000L, e.value())
        val e2 = TimeEngine(c)
        e2.restore(s, false, 14_000L, false)
        assertEquals(1000L, e2.value())
    }

    @Test
    fun restoreCountdownThatFinishedWhileAway() {
        val c = FakeClock()
        val s = SavedTimer(1, 5000L, true, 0L, 10_000L, 1L, 5000L, false, false)
        val e = TimeEngine(c)
        val finished = e.restore(s, false, 30_000L, true)
        assertTrue(finished)
        assertEquals(0L, e.value())
        assertFalse(e.isRunning())
    }
}
