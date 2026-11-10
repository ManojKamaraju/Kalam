package com.timer.app

/** Formatting / parsing helpers. Pure Kotlin (no Android types) so they are unit tested. */
object TimeFormat {
    /** Writes the decimal digits of [v] left-padded with zeros to [minDigits]. Returns the number of chars written. */
    fun digits(v: Long, minDigits: Int, out: CharArray): Int {
        var x = if (v < 0L) 0L else v
        var n = 0
        val tmp = CharArray(24)
        if (x == 0L) {
            tmp[n++] = '0'
        }
        while (x > 0L) {
            tmp[n++] = ('0' + (x % 10L).toInt())
            x /= 10L
        }
        var len = n
        while (len < minDigits) {
            tmp[len++] = '0'
        }
        for (i in 0 until len) out[i] = tmp[len - 1 - i]
        return len
    }

    /** "HH:MM:SS.mmm" (hours grow as needed). */
    fun full(ms: Long): String {
        val d = Digits.from(ms)
        return pad(d.h, 2) + ":" + pad(d.m.toLong(), 2) + ":" + pad(d.s.toLong(), 2) + "." + pad(d.ms.toLong(), 3)
    }

    /** "H:MM:SS" for the notification (milliseconds omitted). */
    fun clock(ms: Long): String {
        val d = Digits.from(ms)
        return if (d.h > 0) d.h.toString() + ":" + pad(d.m.toLong(), 2) + ":" + pad(d.s.toLong(), 2)
        else pad(d.m.toLong(), 2) + ":" + pad(d.s.toLong(), 2)
    }

    private fun pad(v: Long, n: Int): String {
        val s = v.toString()
        return if (s.length >= n) s else "0".repeat(n - s.length) + s
    }

    /**
     * Parses "90", "1:30", "1:02:03", "00:01:30.500", "75.5" into milliseconds. Returns null for invalid input.
     * A bare number is seconds. Colon-separated groups are [H:]M:S. Seconds may carry a decimal fraction.
     */
    fun parse(text: String): Long? {
        val s = text.trim().replace(',', '.')
        if (s.isEmpty()) return null
        val parts = s.split(":")
        if (parts.size > 3) return null
        var secPart = parts[parts.size - 1]
        if (secPart.isEmpty() || secPart.count { it == '.' } > 1) return null
        for (ch in secPart) if (!(ch.isDigit() || ch == '.')) return null
        var h = 0L
        var m = 0L
        for (i in 0 until parts.size - 1) {
            val p = parts[i]
            if (p.isEmpty() || !p.all { it.isDigit() } || p.length > 12) return null
        }
        if (parts.size == 3) {
            h = parts[0].toLong()
            m = parts[1].toLong()
        } else if (parts.size == 2) {
            m = parts[0].toLong()
        }
        if (secPart.startsWith(".")) secPart = "0$secPart"
        if (secPart.endsWith(".")) secPart += "0"
        val dot = secPart.indexOf('.')
        val whole = if (dot < 0) secPart else secPart.substring(0, dot)
        if (whole.length > 12) return null
        var frac = if (dot < 0) "" else secPart.substring(dot + 1)
        if (frac.length > 3) frac = frac.substring(0, 3)
        while (frac.length < 3) frac += "0"
        val total = h * Units.HOUR + m * Units.MIN + whole.toLong() * Units.SEC + frac.toLong()
        if (total < 0L || total > Units.MAX_MS) return null
        return total
    }

    /** Seconds with up to 3 decimals, trimmed ("5", "1.5", "75.25"). */
    fun secondsText(ms: Long): String {
        val whole = ms / 1000L
        val frac = (ms % 1000L).toInt()
        if (frac == 0) return whole.toString()
        var f = frac.toString().padStart(3, '0').trimEnd('0')
        if (f.isEmpty()) f = "0"
        return "$whole.$f"
    }
}
