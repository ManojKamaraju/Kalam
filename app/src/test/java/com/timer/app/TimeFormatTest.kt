package com.timer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeFormatTest {
    @Test
    fun fullFormatMatchesSpec() {
        assertEquals("00:00:00.000", TimeFormat.full(0))
        assertEquals("00:00:01.000", TimeFormat.full(1000))
        assertEquals("01:02:03.456", TimeFormat.full(3_723_456))
        assertEquals("10000:00:00.000", TimeFormat.full(10_000L * Units.HOUR))
        assertEquals("1000:59:59.999", TimeFormat.full(1000L * Units.HOUR + 59 * Units.MIN + 59 * Units.SEC + 999))
    }

    @Test
    fun digitsPadsAndGrows() {
        val buf = CharArray(24)
        var n = TimeFormat.digits(7, 2, buf)
        assertEquals("07", String(buf, 0, n))
        n = TimeFormat.digits(0, 3, buf)
        assertEquals("000", String(buf, 0, n))
        n = TimeFormat.digits(123456, 2, buf)
        assertEquals("123456", String(buf, 0, n))
    }

    @Test
    fun parseAcceptsCommonForms() {
        assertEquals(90_000L, TimeFormat.parse("90"))
        assertEquals(90_000L, TimeFormat.parse("1:30"))
        assertEquals(3_723_000L, TimeFormat.parse("1:02:03"))
        assertEquals(90_500L, TimeFormat.parse("00:01:30.500"))
        assertEquals(75_500L, TimeFormat.parse("75.5"))
        assertEquals(75_000L, TimeFormat.parse("75"))
        assertEquals(86_000L, TimeFormat.parse("86"))
        assertEquals(300_000L, TimeFormat.parse("5:00"))
        assertEquals(500L, TimeFormat.parse(".5"))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(TimeFormat.parse(""))
        assertNull(TimeFormat.parse("abc"))
        assertNull(TimeFormat.parse("1:2:3:4"))
        assertNull(TimeFormat.parse("1.2.3"))
        assertNull(TimeFormat.parse("-5"))
        assertNull(TimeFormat.parse("1::2"))
    }

    @Test
    fun secondsTextTrims() {
        assertEquals("5", TimeFormat.secondsText(5000))
        assertEquals("1.5", TimeFormat.secondsText(1500))
        assertEquals("75.25", TimeFormat.secondsText(75_250))
        assertEquals("0.02", TimeFormat.secondsText(20))
    }
}
