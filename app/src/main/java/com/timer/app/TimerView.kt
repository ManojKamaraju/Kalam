package com.timer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

interface ViewHost {
    fun openElementMenu(comp: Int, x: Float, y: Float)
    fun openBackgroundMenu(x: Float, y: Float)
}

/** Cached glyph geometry for one (font, weight, style). Glyphs are stored as paths at a fixed reference size and scaled by the canvas. */
class ResolvedStyle {
    companion object {
        const val REF = 200f
        const val SEP_PAD = 0.04f * REF
    }

    var fontId: String = ""
    var weight: Int = -1
    var fontStyle: Int = -1
    var skew: Float = 0f
    var cellW: Float = 0f
    var digitH: Float = 0f
    var baseline: Float = 0f // y of the baseline relative to the digits' vertical centre
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = HashMap<Char, Path>()
    private val outline = HashMap<Char, Path>()
    private val adv = HashMap<Char, Float>()

    fun resolve(ctx: Context, s: ElementStyle) {
        if (fontId == s.fontId && weight == s.weight && fontStyle == s.fontStyle) return
        fontId = s.fontId
        weight = s.weight
        fontStyle = s.fontStyle
        val fam = Fonts.family(s.fontId)
        val hasItalic = fam?.hasItalic() ?: false
        val wantItalic = s.fontStyle == 1
        val tf: Typeface = Fonts.typeface(ctx, s.fontId, s.weight, wantItalic)
        skew = if (s.fontStyle == 2 || (wantItalic && !hasItalic)) -0.2f else 0f
        paint.typeface = tf
        paint.textSize = REF
        fill.clear()
        outline.clear()
        adv.clear()
        var maxW = 0f
        for (ch in '0'..'9') maxW = max(maxW, advance(ch))
        cellW = if (maxW <= 0f) REF * 0.6f else maxW
        val b = Rect()
        paint.getTextBounds("0123456789", 0, 10, b)
        digitH = if (b.height() <= 0) REF * 0.7f else b.height().toFloat()
        baseline = -(b.top.toFloat()) - digitH / 2f
    }

    fun advance(ch: Char): Float = adv.getOrPut(ch) { paint.measureText(ch.toString()) }

    fun fillPath(ch: Char): Path = fill.getOrPut(ch) {
        val p = Path()
        paint.getTextPath(ch.toString(), 0, 1, 0f, 0f, p)
        p
    }

    /** Overlapping contours (common in variable fonts) would show crossing lines when stroked, so merge them first. */
    fun outlinePath(ch: Char): Path = outline.getOrPut(ch) {
        val src = fillPath(ch)
        val out = Path()
        val ok = try {
            out.op(src, src, Path.Op.UNION)
        } catch (e: Throwable) {
            false
        }
        if (!ok) out.set(src)
        out
    }
}

class TimerView(ctx: Context, private val core: Core, private val host: ViewHost) :
    View(ctx), GestureHost, GestureListener, CoreListener, ModelListener {

    private val model = core.model
    private val engine = core.engine
    private val density = resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    private val styles = Array(4) { ResolvedStyle() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val bg = BackgroundRenderer(ctx, model.background, { postInvalidateOnAnimation() }, { what -> onBackgroundProblem(what) })

    // element digits
    private val buf = Array(4) { CharArray(24) }
    private val len = IntArray(4)

    // geometry targets (computed from the model every frame) and visual state (what is on screen)
    private val tcx = FloatArray(4)
    private val tcy = FloatArray(4)
    private val tsz = FloatArray(4)
    private var trot = 0f
    private val vcx = FloatArray(4)
    private val vcy = FloatArray(4)
    private val vsz = FloatArray(4)
    private var vrot = 0f
    private val wcx = FloatArray(4) // velocities
    private val wcy = FloatArray(4)
    private val wsz = FloatArray(4)
    private val rotVel = FloatArray(1)
    private var wrot: Float
        get() = rotVel[0]
        set(value) { rotVel[0] = value }
    private var orient = 0f // extra rotation of the whole composition during an orientation change
    private val orientVel = FloatArray(1)
    private var worient: Float
        get() = orientVel[0]
        set(value) { orientVel[0] = value }
    private var visualInit = false
    private var snapPending = false
    private var lastDisplayRotation = -1

    private var lastDrawMs = 0L
    private var lastFrameNs = 0L
    private val t0 = SystemClock.uptimeMillis()

    // gestures
    private val gcfg = GestureConfig()
    private val gestures = GestureEngine(this, this, gcfg)
    private val timerRunnables = HashMap<Int, Runnable>()
    private var swallow = false
    private val pIds = IntArray(10)
    private val pXs = FloatArray(10)
    private val pYs = FloatArray(10)

    private val tmpRect = Rect()

    init {
        isFocusable = true
        isClickable = true
        applyGestureConfig()
        core.listeners.add(this)
        model.listeners.add(this)
        updateKeepScreenOn()
    }

    fun release() {
        core.listeners.remove(this)
        model.listeners.remove(this)
        bg.release()
    }

    // ------------------------------------------------------------------ configuration

    private fun applyGestureConfig() {
        val p = model.prefs
        gcfg.density = density
        gcfg.scrollStepDp = p.scrollStepDp
        gcfg.pickupMs = p.pickupMs.toLong()
        gcfg.longPressMs = p.longPressMs.toLong()
        gcfg.tapWindowMs = p.tapWindowMs.toLong()
        gcfg.pinchSensitivity = p.pinchSensitivity
        gcfg.rotateSensitivity = p.rotateSensitivity
        gcfg.elementTapIsBackgroundTap = p.elementTapIsBackgroundTap
    }

    private fun updateKeepScreenOn() {
        keepScreenOn = model.prefs.keepScreenOn && engine.isRunning()
    }

    override fun onModelChanged(flags: Int) {
        if (flags and AppModel.D_PREFS != 0) {
            applyGestureConfig()
        }
        postInvalidateOnAnimation()
    }

    override fun onTimerChanged() {
        updateKeepScreenOn()
        postInvalidateOnAnimation()
    }

    override fun onRingingChanged() {
        postInvalidateOnAnimation()
    }

    override fun onModelReset() {
        snapPending = false
        postInvalidateOnAnimation()
    }

    private fun onBackgroundProblem(what: String) {
        if (what == "wallpaper" && model.background.type == 3) {
            model.background.type = 0
            model.background.wallpaperFile = null
            model.markDirty(AppModel.D_BACKGROUND)
            core.message("Wallpaper is unavailable. Switched to a solid background.")
        }
    }

    // ------------------------------------------------------------------ layout helpers

    private fun cls(): Int = if (width > height) 1 else 0
    fun layout(): LayoutSet = model.layouts[cls()]
    private fun unit(): Float = min(width, height).toFloat()

    fun applyPreset(id: Int) {
        if (width == 0) return
        Presets.apply(id, layout(), width.toFloat(), height.toFloat(), unit())
        layoutEdited()
    }

    fun saveMaster() {
        model.masters[cls()] = layout().copy()
        model.markDirty(AppModel.D_LAYOUT)
        core.message("Master Layout saved")
    }

    /** Triple tap. Restores the saved arrangement (never touches the numbers). */
    fun restoreMaster() {
        val m = model.masters[cls()]
        if (m == null) {
            applyPreset(0)
            core.message("No Master Layout saved yet. Long-press the background to save one.")
            return
        }
        Presets.restoreMaster(m, layout(), model.masterIncludesGlobal)
        layoutEdited()
    }

    fun resetGlobalTransform() {
        val l = layout()
        l.globalScale = 1f
        l.globalRotation = 0f
        layoutEdited()
    }

    private fun layoutEdited() {
        model.markDirty(AppModel.D_LAYOUT)
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ size / orientation

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val rot = try {
            display?.rotation ?: 0
        } catch (e: Throwable) {
            0
        }
        if (visualInit && oldw > 0 && oldh > 0) {
            val sx = w.toFloat() / oldw
            val sy = h.toFloat() / oldh
            val su = min(w, h).toFloat() / min(oldw, oldh).toFloat()
            for (i in 0 until 4) {
                vcx[i] *= sx
                vcy[i] *= sy
                vsz[i] *= su
                wcx[i] = 0f
                wcy[i] = 0f
                wsz[i] = 0f
            }
            if (lastDisplayRotation >= 0 && rot != lastDisplayRotation) {
                val delta = (rot - lastDisplayRotation + 4) % 4
                // the content was glued to the old device frame; let it swing back upright
                val off = when (delta) {
                    1 -> -90f
                    3 -> 90f
                    2 -> 180f
                    else -> 0f
                }
                orient += off
                worient = 0f
            }
        }
        lastDisplayRotation = rot
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ drawing

    private fun mulAlpha(color: Int, f: Float): Int {
        val a = (Color.alpha(color) * f).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun springStep(x: Float, vel: FloatArray, idx: Int, target: Float, omega: Float, dt: Float): Float {
        val d = x - target
        val e = exp(-omega * dt)
        val tmp = vel[idx] + omega * d
        val nd = (d + tmp * dt) * e
        vel[idx] = (vel[idx] - tmp * omega * dt) * e
        return target + nd
    }

    private fun angDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > 180f) d -= 360f
        while (d <= -180f) d += 360f
        return d
    }

    private fun fillDigits(i: Int, v: Long) {
        len[i] = when (i) {
            0 -> TimeFormat.digits(v / Units.HOUR, 2, buf[0])
            1 -> TimeFormat.digits((v / Units.MIN) % 60L, 2, buf[1])
            2 -> TimeFormat.digits((v / Units.SEC) % 60L, 2, buf[2])
            else -> TimeFormat.digits(v % 1000L, 3, buf[3])
        }
    }

    private fun sepChar(i: Int): Char = when (i) {
        0, 1 -> ':'
        2 -> '.'
        else -> ' '
    }

    private fun hasSep(l: LayoutSet, i: Int): Boolean = i < 3 && l.seps[i]

    private fun sepSpace(i: Int): Float {
        val s = styles[i]
        return s.advance(sepChar(i)) + 2f * ResolvedStyle.SEP_PAD
    }

    /** Computes where every element wants to be, from the model. Does not depend on animation state. */
    private fun computeTargets(l: LayoutSet) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bs = 0.2f * unit()
        val cx = w / 2f
        val cy = h / 2f
        val gs = l.globalScale
        val rad = Math.toRadians(l.globalRotation.toDouble())
        val cr = cos(rad).toFloat()
        val sr = sin(rad).toFloat()
        trot = l.globalRotation
        if (l.flowRow) {
            var rowW1 = 0f
            for (i in 0 until 4) {
                val sz = bs * l.poses[i].scale
                rowW1 += styles[i].cellW * len[i] * sz / ResolvedStyle.REF
                if (hasSep(l, i)) rowW1 += sepSpace(i) * sz / ResolvedStyle.REF
            }
            val fit = if (rowW1 <= 0f) 1f else min(1f, 0.92f * w / rowW1)
            var total = 0f
            for (i in 0 until 4) {
                val sz = bs * l.poses[i].scale * fit * gs
                tsz[i] = sz
                total += styles[i].cellW * len[i] * sz / ResolvedStyle.REF
                if (hasSep(l, i)) total += sepSpace(i) * sz / ResolvedStyle.REF
            }
            var x = -total / 2f
            for (i in 0 until 4) {
                val sz = tsz[i]
                val ew = styles[i].cellW * len[i] * sz / ResolvedStyle.REF
                val lx = x + ew / 2f
                x += ew
                if (hasSep(l, i)) x += sepSpace(i) * sz / ResolvedStyle.REF
                tcx[i] = cx + lx * cr
                tcy[i] = cy + lx * sr
            }
        } else {
            for (i in 0 until 4) {
                val base = bs * l.poses[i].scale
                val ew = styles[i].cellW * len[i] * base / ResolvedStyle.REF
                val fit = if (ew <= 0f) 1f else min(1f, 0.96f * w / ew)
                tsz[i] = base * fit * gs
                val lx = gs * l.poses[i].x * w
                val ly = gs * l.poses[i].y * h
                tcx[i] = cx + lx * cr - ly * sr
                tcy[i] = cy + lx * sr + ly * cr
            }
        }
    }

    /** Converts the current on-screen arrangement into explicit poses (used when the user starts moving things). */
    private fun freezeFlow(l: LayoutSet) {
        if (!l.flowRow) return
        for (i in 0 until 4) setPoseFromCenter(l, i, tcx[i], tcy[i], false)
        l.flowRow = false
        l.presetId = -1
    }

    private fun setPoseFromCenter(l: LayoutSet, i: Int, scx: Float, scy: Float, clamp: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        var x = scx
        var y = scy
        if (clamp) {
            x = x.coerceIn(0f, w)
            y = y.coerceIn(0f, h)
        }
        val rad = Math.toRadians(l.globalRotation.toDouble())
        val cr = cos(rad).toFloat()
        val sr = sin(rad).toFloat()
        val dx = x - w / 2f
        val dy = y - h / 2f
        val lx = dx * cr + dy * sr
        val ly = -dx * sr + dy * cr
        val gs = if (l.globalScale <= 0f) 1f else l.globalScale
        l.poses[i].x = lx / gs / w
        l.poses[i].y = ly / gs / h
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val nowMs = SystemClock.uptimeMillis()
        val nowNs = System.nanoTime()
        var dt = if (lastFrameNs == 0L) 0.016f else (nowNs - lastFrameNs) / 1e9f
        if (dt < 0.001f) dt = 0.001f
        if (dt > 0.05f) dt = 0.05f
        lastFrameNs = nowNs
        lastDrawMs = nowMs

        val l = layout()
        val value = engine.value()
        for (i in 0 until 4) {
            styles[i].resolve(context, model.styles[i])
            fillDigits(i, value)
        }
        computeTargets(l)

        if (!visualInit) {
            for (i in 0 until 4) {
                vcx[i] = tcx[i]; vcy[i] = tcy[i]; vsz[i] = tsz[i]
            }
            vrot = trot
            visualInit = true
        }

        var animating = false
        if (snapPending) {
            for (i in 0 until 4) {
                vcx[i] = tcx[i]; vcy[i] = tcy[i]; vsz[i] = tsz[i]
                wcx[i] = 0f; wcy[i] = 0f; wsz[i] = 0f
            }
            vrot = trot
            wrot = 0f
            snapPending = false
        } else {
            val om = 15f
            for (i in 0 until 4) {
                vcx[i] = springStep(vcx[i], wcx, i, tcx[i], om, dt)
                vcy[i] = springStep(vcy[i], wcy, i, tcy[i], om, dt)
                vsz[i] = springStep(vsz[i], wsz, i, tsz[i], om, dt)
                if (abs(vcx[i] - tcx[i]) < 0.15f && abs(vcy[i] - tcy[i]) < 0.15f && abs(vsz[i] - tsz[i]) < 0.1f &&
                    abs(wcx[i]) < 1f && abs(wcy[i]) < 1f && abs(wsz[i]) < 1f
                ) {
                    vcx[i] = tcx[i]; vcy[i] = tcy[i]; vsz[i] = tsz[i]
                    wcx[i] = 0f; wcy[i] = 0f; wsz[i] = 0f
                } else {
                    animating = true
                }
            }
            val target = vrot + angDiff(trot, vrot)
            vrot = springStep(vrot, rotVel, 0, target, om, dt)
            if (abs(vrot - trot) < 0.05f && abs(wrot) < 0.5f) {
                vrot = trot
                wrot = 0f
            } else {
                animating = true
            }
        }
        if (orient != 0f || worient != 0f) {
            orient = springStep(orient, orientVel, 0, 0f, 10f, dt)
            if (abs(orient) < 0.05f && abs(worient) < 0.5f) {
                orient = 0f
                worient = 0f
            } else {
                animating = true
            }
        }

        // background (not affected by the composition's rotation)
        val timeSec = (nowMs - t0) / 1000f
        bg.draw(canvas, w, h, timeSec)

        val ring = core.ringing
        val pulse = if (ring) 0.55f + 0.45f * (0.5f + 0.5f * sin(nowMs * 0.012f)) else 1f

        canvas.save()
        if (orient != 0f) canvas.rotate(orient, w / 2f, h / 2f)
        for (i in 0 until 4) drawElement(canvas, i, l, pulse)
        canvas.restore()

        // next frame?
        val running = engine.isRunning()
        val animBg = bg.isAnimated()
        if (animating || ring || (running && !animBg)) {
            postInvalidateOnAnimation()
        } else if (animBg || running) {
            val interval = 1000L / max(1, model.prefs.animFps)
            postInvalidateDelayed(interval)
        }
    }

    private fun drawElement(c: Canvas, i: Int, l: LayoutSet, alphaMul: Float) {
        val st = model.styles[i]
        val rs = styles[i]
        val k = vsz[i] / ResolvedStyle.REF
        if (k <= 0f) return
        c.save()
        c.translate(vcx[i], vcy[i])
        c.rotate(vrot)
        c.scale(k, k)
        if (rs.skew != 0f) c.skew(rs.skew, 0f)
        val n = len[i]
        val x0 = -(n * rs.cellW) / 2f
        val by = rs.baseline
        val mode = st.drawMode
        val strokeW = st.outlineWidth * ResolvedStyle.REF
        fillPaint.color = mulAlpha(st.fillColor, alphaMul)
        strokePaint.color = mulAlpha(st.outlineColor, alphaMul)
        strokePaint.strokeWidth = strokeW
        for (j in 0 until n) {
            val ch = buf[i][j]
            val gx = x0 + (j + 0.5f) * rs.cellW - rs.advance(ch) / 2f
            drawGlyph(c, rs, ch, gx, by, mode)
        }
        if (hasSep(l, i)) {
            val sc = sepChar(i)
            drawGlyph(c, rs, sc, x0 + n * rs.cellW + ResolvedStyle.SEP_PAD, by, mode)
        }
        c.restore()
    }

    private fun drawGlyph(c: Canvas, rs: ResolvedStyle, ch: Char, x: Float, y: Float, mode: Int) {
        c.save()
        c.translate(x, y)
        when (mode) {
            1 -> c.drawPath(rs.outlinePath(ch), strokePaint)
            2 -> {
                c.drawPath(rs.outlinePath(ch), strokePaint)
                c.drawPath(rs.fillPath(ch), fillPaint)
            }
            else -> c.drawPath(rs.fillPath(ch), fillPaint)
        }
        c.restore()
    }

    // ------------------------------------------------------------------ hit testing (GestureHost)

    override fun hitElement(x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        val rad = Math.toRadians(vrot.toDouble())
        val cr = cos(rad).toFloat()
        val sr = sin(rad).toFloat()
        for (i in 0 until 4) {
            val rs = styles[i]
            val k = vsz[i] / ResolvedStyle.REF
            val dx = x - vcx[i]
            val dy = y - vcy[i]
            val lx = dx * cr + dy * sr
            val ly = -dx * sr + dy * cr
            val pad = max(28f * density, 0.3f * rs.digitH * k)
            val hw = rs.cellW * len[i] * k / 2f + pad
            val hh = rs.digitH * k / 2f + pad
            if (abs(lx) <= hw && abs(ly) <= hh) {
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
        }
        return best
    }

    override fun nearestElement(x: Float, y: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until 4) {
            val d = hypot(x - vcx[i], y - vcy[i])
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    override fun schedule(timerId: Int, delayMs: Long) {
        cancel(timerId)
        val r = Runnable {
            timerRunnables.remove(timerId)
            gestures.onTimer(timerId)
        }
        timerRunnables[timerId] = r
        mainHandler.postDelayed(r, delayMs)
    }

    override fun cancel(timerId: Int) {
        val r = timerRunnables.remove(timerId)
        if (r != null) mainHandler.removeCallbacks(r)
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (core.ringing) {
                    core.stopAlarm()
                    swallow = true
                    return true
                }
                swallow = false
                gestures.down(e.getPointerId(0), e.getX(0), e.getY(0), e.eventTime)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!swallow) {
                val i = e.actionIndex
                gestures.down(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> if (!swallow) {
                val n = min(e.pointerCount, pIds.size)
                for (i in 0 until n) {
                    pIds[i] = e.getPointerId(i)
                    pXs[i] = e.getX(i)
                    pYs[i] = e.getY(i)
                }
                gestures.move(pIds, pXs, pYs, n, e.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP -> if (!swallow) gestures.up(e.getPointerId(e.actionIndex), e.eventTime)
            MotionEvent.ACTION_UP -> {
                if (!swallow) gestures.up(e.getPointerId(e.actionIndex), e.eventTime)
                swallow = false
            }
            MotionEvent.ACTION_CANCEL -> {
                gestures.cancel()
                swallow = false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ gesture callbacks

    override fun onSingleTap(x: Float, y: Float) {
        core.toggle()
    }

    override fun onDoubleTap(x: Float, y: Float) {
        core.resetTimer()
    }

    override fun onTripleTap(x: Float, y: Float) {
        restoreMaster()
    }

    override fun onLongPress(x: Float, y: Float, target: Int) {
        if (target >= 0) host.openElementMenu(target, x, y) else host.openBackgroundMenu(x, y)
    }

    override fun onScrollSteps(comp: Int, steps: Int) {
        if (comp < 0 || comp > 3) return
        core.edit(Comp.ALL[comp], steps)
    }

    override fun onDragStart(comp: Int) {
        freezeFlow(layout())
    }

    override fun onDragMove(comp: Int, dx: Float, dy: Float) {
        if (comp < 0 || comp > 3) return
        val l = layout()
        freezeFlow(l)
        // the element's current on-screen centre moves with the finger, then is converted back to a pose
        setPoseFromCenter(l, comp, vcx[comp] + dx, vcy[comp] + dy, true)
        vcx[comp] += dx
        vcy[comp] += dy
        snapPending = true
        postInvalidateOnAnimation()
    }

    override fun onDragEnd(comp: Int) {
        layoutEdited()
    }

    override fun onGlobalScale(factor: Float) {
        val l = layout()
        l.globalScale = (l.globalScale * factor).coerceIn(0.15f, 8f)
        snapPending = true
        postInvalidateOnAnimation()
    }

    override fun onElementScale(comp: Int, factor: Float) {
        if (comp < 0 || comp > 3) return
        val l = layout()
        l.poses[comp].scale = (l.poses[comp].scale * factor).coerceIn(0.1f, 12f)
        snapPending = true
        postInvalidateOnAnimation()
    }

    override fun onRotate(deltaDeg: Float) {
        val l = layout()
        var r = l.globalRotation + deltaDeg
        while (r > 180f) r -= 360f
        while (r <= -180f) r += 360f
        l.globalRotation = r
        snapPending = true
        vrot = vrot + deltaDeg
        postInvalidateOnAnimation()
    }

    override fun onTransformEnd() {
        layoutEdited()
    }

    override fun onFeedback(kind: Int) {
        if (!model.prefs.gestureFeedback) return
        val c = when (kind) {
            GestureEngine.FB_LONGPRESS -> HapticFeedbackConstants.LONG_PRESS
            GestureEngine.FB_TRIPLE, GestureEngine.FB_DOUBLE -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.GESTURE_START
        }
        performHapticFeedback(c)
    }
}
