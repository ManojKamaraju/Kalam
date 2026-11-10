package com.timer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Full-spectrum colour picker: hue bar, saturation/brightness square, optional opacity bar.
 * Any of the 16.7 million RGB colours (plus opacity) can be chosen; there is no fixed palette.
 */
class ColorPickerView(ctx: Context, initial: Int, private val withAlpha: Boolean, private val onChange: (Int) -> Unit) : View(ctx) {
    private val hsv = FloatArray(3)
    private var alpha = 255
    private val density = resources.displayMetrics.density

    private val svRect = RectF()
    private val hueRect = RectF()
    private val alphaRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val checkA = Paint().apply { color = 0xFF555555.toInt() }
    private val checkB = Paint().apply { color = 0xFF888888.toInt() }
    private var drag = 0 // 1 sv, 2 hue, 3 alpha

    init {
        setColor(initial, false)
    }

    fun setColor(c: Int, notify: Boolean) {
        Color.colorToHSV(c, hsv)
        alpha = if (withAlpha) Color.alpha(c) else 255
        invalidate()
        if (notify) onChange(current())
    }

    fun current(): Int {
        val rgb = Color.HSVToColor(hsv)
        return (rgb and 0x00FFFFFF) or (alpha shl 24)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 0.78f + (if (withAlpha) 96f else 56f) * density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val pad = 18f * density
        val barH = 30f * density
        val gap = 16f * density
        svRect.set(pad, pad, w - pad, pad + (w - 2 * pad) * 0.62f)
        hueRect.set(pad, svRect.bottom + gap, w - pad, svRect.bottom + gap + barH)
        alphaRect.set(pad, hueRect.bottom + gap, w - pad, hueRect.bottom + gap + barH)
    }

    override fun onDraw(c: Canvas) {
        // saturation / brightness square for the current hue
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        paint.shader = LinearGradient(svRect.left, 0f, svRect.right, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
        c.drawRoundRect(svRect, 10f * density, 10f * density, paint)
        paint.shader = LinearGradient(0f, svRect.top, 0f, svRect.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRoundRect(svRect, 10f * density, 10f * density, paint)
        // hue bar
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        paint.shader = LinearGradient(hueRect.left, 0f, hueRect.right, 0f, hues, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(hueRect, 10f * density, 10f * density, paint)
        if (withAlpha) {
            // checkerboard + opacity gradient
            paint.shader = null
            val cell = 8f * density
            var y = alphaRect.top
            var row = 0
            c.save()
            c.clipRect(alphaRect)
            while (y < alphaRect.bottom) {
                var x = alphaRect.left
                var col = row
                while (x < alphaRect.right) {
                    c.drawRect(x, y, x + cell, y + cell, if (col % 2 == 0) checkA else checkB)
                    x += cell
                    col++
                }
                y += cell
                row++
            }
            c.restore()
            val rgb = Color.HSVToColor(hsv) and 0x00FFFFFF
            paint.shader = LinearGradient(alphaRect.left, 0f, alphaRect.right, 0f, rgb, rgb or (0xFF shl 24), Shader.TileMode.CLAMP)
            c.drawRoundRect(alphaRect, 10f * density, 10f * density, paint)
        }
        paint.shader = null
        // handles
        val sx = svRect.left + hsv[1] * svRect.width()
        val sy = svRect.top + (1f - hsv[2]) * svRect.height()
        handle(c, sx, sy, Color.HSVToColor(hsv))
        handle(c, hueRect.left + hsv[0] / 360f * hueRect.width(), hueRect.centerY(), pure)
        if (withAlpha) handle(c, alphaRect.left + alpha / 255f * alphaRect.width(), alphaRect.centerY(), current() or (0xFF shl 24))
    }

    private fun handle(c: Canvas, x: Float, y: Float, fill: Int) {
        val r = 11f * density
        paint.color = fill
        paint.style = Paint.Style.FILL
        c.drawCircle(x, y, r, paint)
        handlePaint.color = Color.WHITE
        c.drawCircle(x, y, r, handlePaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                drag = when {
                    svRect.contains(e.x, e.y) -> 1
                    e.y >= hueRect.top - 12 * density && e.y <= hueRect.bottom + 12 * density -> 2
                    withAlpha && e.y >= alphaRect.top - 12 * density && e.y <= alphaRect.bottom + 12 * density -> 3
                    else -> 0
                }
                if (drag == 0) return false
                update(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> if (drag != 0) update(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = 0
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        when (drag) {
            1 -> {
                hsv[1] = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
                hsv[2] = 1f - ((y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
            }
            2 -> hsv[0] = (((x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 359.99f)
            3 -> alpha = (((x - alphaRect.left) / alphaRect.width()).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
        invalidate()
        onChange(current())
    }

    companion object {
        fun parseHex(text: String, withAlpha: Boolean): Int? {
            var s = text.trim().removePrefix("#")
            if (s.length == 6 || (withAlpha && s.length == 8)) {
                if (!s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
                if (s.length == 6) s = "FF$s"
                val v = s.toLong(16)
                val argb = v.toInt()
                return if (withAlpha) argb else (argb or (0xFF shl 24))
            }
            return null
        }

        fun toHex(c: Int, withAlpha: Boolean): String =
            if (withAlpha) "#%08X".format(c) else "#%06X".format(c and 0x00FFFFFF)
    }
}
