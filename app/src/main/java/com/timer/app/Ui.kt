package com.timer.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Ui {
    val CARD: Int = 0xF2171717.toInt()
    val TEXT: Int = 0xFFFFFFFF.toInt()
    val DIM: Int = 0xFFA8A8A8.toInt()
    val ACCENT: Int = 0xFF8AB4F8.toInt()
    val DANGER: Int = 0xFFFF8A80.toInt()
    val SCRIM: Int = 0x59000000
    val DIVIDER: Int = 0x22FFFFFF

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusPx: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radiusPx
        return g
    }

    fun ripple(): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE))
}

/** One screen inside the menu. [build] runs on open and on every refresh(). */
class Page(val title: String, val build: (PageBuilder) -> Unit)

class PageBuilder(val ctx: Context, private val list: LinearLayout, val menu: MenuLayer) {
    private fun dp(v: Float) = Ui.dp(ctx, v)

    private fun row(minH: Float = 56f): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.minimumHeight = dp(minH)
        r.setPadding(dp(18f), dp(6f), dp(18f), dp(6f))
        list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return r
    }

    private fun label(text: String, size: Float = 16f, color: Int = Ui.TEXT): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(color)
        t.textSize = size
        return t
    }

    fun header(text: String) {
        val t = label(text.uppercase(), 12f, Ui.ACCENT)
        t.letterSpacing = 0.08f
        t.setPadding(dp(18f), dp(18f), dp(18f), dp(4f))
        list.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun note(text: String) {
        val t = label(text, 13f, Ui.DIM)
        t.setPadding(dp(18f), dp(4f), dp(18f), dp(10f))
        list.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun divider() {
        val v = View(ctx)
        v.setBackgroundColor(Ui.DIVIDER)
        list.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    fun toggle(text: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
        val r = row()
        r.background = Ui.ripple()
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(text))
        if (sub != null) col.addView(label(sub, 12f, Ui.DIM))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx)
        sw.isChecked = checked
        sw.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Ui.ACCENT, 0xFFBDBDBD.toInt())
        )
        sw.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x668AB4F8, 0x66888888)
        )
        sw.isClickable = false
        r.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r.setOnClickListener {
            sw.isChecked = !sw.isChecked
            onChange(sw.isChecked)
        }
    }

    /** Slider with a live value read-out. [onChange] receives the raw progress (0..max) while dragging. */
    fun slider(text: String, progress: Int, max: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18f), dp(10f), dp(18f), dp(6f))
        list.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        val l = label(text)
        val v = label(fmt(progress), 15f, Ui.ACCENT)
        top.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(top)
        val sb = SeekBar(ctx)
        sb.max = max
        sb.progress = progress
        sb.progressTintList = ColorStateList.valueOf(Ui.ACCENT)
        sb.thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
        sb.setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                v.text = fmt(p)
                if (fromUser) onChange(p)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)))
    }

    fun nav(text: String, value: String?, onClick: () -> Unit) {
        val r = row()
        r.background = Ui.ripple()
        r.addView(label(text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            val v = label(value, 14f, Ui.DIM)
            v.setPadding(dp(8f), 0, dp(8f), 0)
            v.maxWidth = dp(150f)
            v.maxLines = 1
            v.ellipsize = android.text.TextUtils.TruncateAt.END
            r.addView(v)
        }
        r.addView(label("›", 22f, Ui.DIM))
        r.setOnClickListener { onClick() }
    }

    fun action(text: String, danger: Boolean = false, onClick: () -> Unit) {
        val r = row()
        r.background = Ui.ripple()
        r.addView(label(text, 16f, if (danger) Ui.DANGER else Ui.ACCENT))
        r.setOnClickListener { onClick() }
    }

    fun radio(text: String, selected: Boolean, tf: Typeface? = null, size: Float = 16f, enabled: Boolean = true, onClick: () -> Unit) {
        val r = row()
        r.background = Ui.ripple()
        val t = label(text, size, if (enabled) Ui.TEXT else 0xFF707070.toInt())
        if (tf != null) t.typeface = tf
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) r.addView(label("✓", 20f, Ui.ACCENT))
        if (enabled) r.setOnClickListener { onClick() }
    }

    fun colorRow(text: String, color: Int, onClick: () -> Unit) {
        val r = row()
        r.background = Ui.ripple()
        r.addView(label(text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = View(ctx)
        val g = Ui.rounded(color, dp(14f).toFloat())
        g.setStroke(dp(1.5f), 0x88FFFFFF.toInt())
        sw.background = g
        r.addView(sw, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        r.addView(label("›", 22f, Ui.DIM).also { it.setPadding(dp(8f), 0, 0, 0) })
        r.setOnClickListener { onClick() }
    }

    /** Text input row. [onDone] is called with the text when the user presses done or leaves the field. */
    fun field(text: String, initial: String, hint: String, numeric: Boolean, onDone: (String) -> Unit) {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
        list.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(label(text, 14f, Ui.DIM))
        val et = EditText(ctx)
        et.setText(initial)
        et.hint = hint
        et.setTextColor(Ui.TEXT)
        et.setHintTextColor(0xFF707070.toInt())
        et.textSize = 18f
        et.setSingleLine(true)
        et.inputType = if (numeric) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_CLASS_TEXT
        et.imeOptions = EditorInfo.IME_ACTION_DONE
        et.backgroundTintList = ColorStateList.valueOf(Ui.ACCENT)
        et.minimumHeight = dp(48f)
        var delivered = initial
        val deliver = {
            val s = et.text.toString()
            if (s != delivered) {
                delivered = s
                onDone(s)
            }
        }
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                deliver()
                et.clearFocus()
                menu.hideKeyboard()
                true
            } else false
        }
        et.setOnFocusChangeListener { _, has -> if (!has) deliver() }
        box.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun custom(v: View, heightPx: Int) {
        list.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (heightPx > 0) heightPx else ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

/**
 * The only "window" KALAM has: a floating panel that opens next to the long-press, scrolls, stays inside the screen,
 * and is dismissed by tapping outside it (never by accident while using it).
 */
class MenuLayer(ctx: Context, private val onClosed: () -> Unit) : FrameLayout(ctx) {
    private fun dp(v: Float) = Ui.dp(context, v)

    private val card = LinearLayout(ctx)
    private val header = LinearLayout(ctx)
    private val back = TextView(ctx)
    private val titleView = TextView(ctx)
    private val body = FrameLayout(ctx)
    private val scroll = ScrollView(ctx)
    private val list = LinearLayout(ctx)
    private val stack = ArrayList<Page>()
    private var imeBottom = 0
    private var downX = 0f
    private var downY = 0f
    var anchorX = 0f
    var anchorY = 0f
    var onPageShown: (() -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    init {
        setBackgroundColor(Ui.SCRIM)
        isClickable = true
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.rounded(Ui.CARD, dp(22f).toFloat())
        card.elevation = dp(16f).toFloat()
        card.clipToOutline = true

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        back.text = "‹"
        back.textSize = 30f
        back.setTextColor(Ui.ACCENT)
        back.gravity = Gravity.CENTER
        back.background = Ui.ripple()
        back.setOnClickListener { pop() }
        titleView.setTextColor(Ui.TEXT)
        titleView.textSize = 18f
        titleView.typeface = Typeface.DEFAULT_BOLD
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        header.addView(back, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(ctx)
        close.text = "✕"
        close.textSize = 18f
        close.setTextColor(Ui.DIM)
        close.gravity = Gravity.CENTER
        close.background = Ui.ripple()
        close.setOnClickListener { dismiss() }
        header.addView(close, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        card.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, 0, 0, dp(10f))
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(card, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setOnApplyWindowInsetsListener { _, insets ->
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            requestLayout()
            insets
        }
    }

    fun hideKeyboard() {
        try {
            val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(windowToken, 0)
        } catch (e: Throwable) {
        }
    }

    // ------------------------------------------------------------------ navigation

    fun open(first: Page, x: Float, y: Float) {
        anchorX = x
        anchorY = y
        stack.clear()
        stack.add(first)
        show(true)
        card.alpha = 0f
        card.scaleX = 0.95f
        card.scaleY = 0.95f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(DecelerateInterpolator()).start()
    }

    fun push(p: Page) {
        stack.add(p)
        show(true)
    }

    fun pop() {
        hideKeyboard()
        if (stack.size <= 1) {
            dismiss()
            return
        }
        stack.removeAt(stack.size - 1)
        show(true)
    }

    fun depth(): Int = stack.size

    /** Rebuilds the current page (keeps the scroll position). */
    fun refresh() {
        show(false)
    }

    private fun show(resetScroll: Boolean) {
        if (stack.isEmpty()) return
        val page = stack[stack.size - 1]
        val sy = scroll.scrollY
        list.removeAllViews()
        titleView.text = page.title
        back.visibility = if (stack.size > 1) VISIBLE else INVISIBLE
        page.build(PageBuilder(context, list, this))
        if (resetScroll) scroll.scrollTo(0, 0) else scroll.post { scroll.scrollTo(0, sy) }
        requestLayout()
        onPageShown?.invoke()
    }

    fun dismiss() {
        hideKeyboard()
        stack.clear()
        onDismissed?.invoke()
        onClosed()
    }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = min((w * 0.94f).toInt(), dp(400f))
        val availH = max(dp(200f), h - imeBottom - dp(24f))
        val ch = min((h * 0.86f).toInt(), availH)
        card.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val cw = card.measuredWidth
        val ch = card.measuredHeight
        val m = dp(12f)
        val left = (anchorX - cw / 2f).toInt().coerceIn(m, max(m, w - cw - m))
        val maxTop = max(m, h - imeBottom - ch - m)
        val top = (anchorY - ch / 2f).toInt().coerceIn(m, maxTop)
        card.layout(left, top, left + cw, top + ch)
    }

    // ------------------------------------------------------------------ outside tap closes

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val inside = e.x >= card.left && e.x <= card.right && e.y >= card.top && e.y <= card.bottom
                val slop = dp(10f)
                if (!inside && abs(e.x - downX) < slop && abs(e.y - downY) < slop) dismiss()
            }
        }
        return true
    }
}
