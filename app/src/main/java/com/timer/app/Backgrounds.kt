package com.timer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Background library. Patterns are WebP assets generated during development (tools/make_patterns.py).
 * Animations are small GPU shaders (AGSL, Android 13+): procedural, no assets, no network, cheap on battery.
 */
object BackgroundFactory {
    const val PATTERN_COUNT = 25
    const val ANIM_COUNT = 25

    val PATTERN_NAMES: List<String> = listOf(
        "Midnight blue", "Dusk gradient", "Deep radial", "Teal cone", "Graphite",
        "Checker", "Blueprint", "Concentric", "Hex lights", "Coral cloud",
        "Ice marble", "Ember flow", "Smoke rings", "Violet spiral", "Plaid",
        "Amethyst weave", "Sand veins", "Forest flow", "Storm cloud", "Terminal",
        "Pixel dust", "Moss grain", "Soft noise", "Fine weave", "Warm paper"
    )

    val ANIM_NAMES: List<String> = listOf(
        "Aurora", "Colour drift", "Lava", "Gentle waves", "Fireflies",
        "Pulse rings", "Marble flow", "Ripples", "Breathing moss", "Starfield",
        "Calm plasma", "Sunset shift", "Neon grid", "Contour flow", "Bokeh",
        "Diagonal drift", "Spiral", "Smoke", "Digital rain", "Prism",
        "Caustics", "Orbit glow", "Checker wave", "Film grain", "Rain streaks"
    )

    private const val PRELUDE = """
uniform float2 res;
uniform float t;
float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
float fbm(float2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        s += a * vnoise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return s;
}
float3 pal(float x, float3 a, float3 b, float3 c, float3 d) { return a + b * cos(6.28318 * (c * x + d)); }
half4 main(float2 fc) {
    float2 uv = fc / res;
    float2 p = (fc - 0.5 * res) / min(res.x, res.y);
    float3 col = float3(0.0);
"""

    private const val EPILOGUE = """
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

    private val BODIES: Array<String> = arrayOf(
        // 0 Aurora
        """
        float w = sin(uv.x * 3.0 + t * 0.15) * 0.5 + 0.5;
        float v2 = sin(uv.y * 2.0 - t * 0.1 + uv.x * 2.0) * 0.5 + 0.5;
        col = mix(float3(0.04, 0.06, 0.18), float3(0.10, 0.45, 0.55), w * v2);
        col = mix(col, float3(0.45, 0.15, 0.55), v2 * 0.5 * (1.0 - w));
        """,
        // 1 Colour drift
        """
        col = pal(uv.x * 0.3 + uv.y * 0.2 + t * 0.02, float3(0.5), float3(0.5), float3(1.0), float3(0.0, 0.33, 0.67)) * 0.5;
        """,
        // 2 Lava
        """
        float s = 0.0;
        for (int i = 0; i < 4; i++) {
            float fi = float(i);
            float2 c = float2(sin(t * 0.2 * (fi + 1.0) + fi * 2.0), cos(t * 0.17 * (fi + 1.3) + fi)) * 0.35;
            s += 0.05 / (dot(p - c, p - c) + 0.02);
        }
        col = mix(float3(0.10, 0.02, 0.05), float3(1.0, 0.45, 0.15), clamp(s * 0.5 - 0.35, 0.0, 1.0));
        """,
        // 3 Gentle waves
        """
        float s = 0.0;
        for (int i = 0; i < 4; i++) {
            float fi = float(i);
            float wv = 0.45 + 0.07 * sin(uv.x * (3.0 + fi) + t * (0.3 + 0.1 * fi) + fi * 1.7) + fi * 0.09;
            s += smoothstep(wv, wv + 0.02, uv.y) * 0.18;
        }
        col = float3(0.03, 0.08, 0.18) + float3(0.05, 0.20, 0.30) * s * 2.0;
        """,
        // 4 Fireflies
        """
        float2 g = uv * float2(res.x / res.y, 1.0) * 8.0;
        float2 id = floor(g);
        float2 f = fract(g) - 0.5;
        float h = hash(id);
        float2 off = float2(sin(t * 0.4 + h * 6.28), cos(t * 0.35 + h * 5.0)) * 0.3;
        float d = length(f - off);
        float tw = 0.5 + 0.5 * sin(t * 0.8 + h * 20.0);
        col = float3(0.02, 0.03, 0.07) + float3(0.5, 0.7, 1.0) * smoothstep(0.12, 0.0, d) * tw * step(0.55, h);
        """,
        // 5 Pulse rings
        """
        float a = atan(p.y, p.x) + t * 0.15;
        float rr = length(p);
        float k = 0.5 + 0.5 * sin(rr * 28.0 - t * 0.8 + sin(a * 5.0) * 1.5);
        col = mix(float3(0.03, 0.03, 0.08), float3(0.25, 0.20, 0.60), k * smoothstep(0.9, 0.1, rr));
        """,
        // 6 Marble flow
        """
        float2 q = float2(fbm(p * 2.0 + t * 0.05), fbm(p * 2.0 + float2(5.2, 1.3) - t * 0.04));
        float n = fbm(p * 2.0 + q * 2.0);
        col = mix(float3(0.05, 0.07, 0.15), float3(0.55, 0.35, 0.70), n);
        col = mix(col, float3(0.9, 0.6, 0.5), q.x * q.y);
        col *= 0.8;
        """,
        // 7 Ripples
        """
        float rr = length(p - float2(0.12 * sin(t * 0.2), 0.1 * cos(t * 0.17)));
        float w = sin(rr * 30.0 - t * 1.2) * exp(-rr * 3.0);
        col = float3(0.02, 0.05, 0.09) + float3(0.10, 0.30, 0.40) * (w * 0.5 + 0.5) * 0.8;
        """,
        // 8 Breathing moss
        """
        float n = fbm(p * 3.0 + float2(t * 0.06, -t * 0.04));
        float b = 0.5 + 0.5 * sin(t * 0.4);
        col = mix(float3(0.02, 0.12, 0.09), float3(0.25, 0.60, 0.40), n * (0.6 + 0.4 * b));
        """,
        // 9 Starfield
        """
        float3 s = float3(0.0);
        for (int i = 0; i < 3; i++) {
            float fi = float(i);
            float2 g = (uv + float2(t * 0.003 * (fi + 1.0), 0.0)) * float2(res.x / res.y, 1.0) * (18.0 + fi * 14.0);
            float2 id = floor(g);
            float h = hash(id + fi * 13.0);
            float2 f = fract(g) - 0.5;
            float d = length(f - (float2(hash(id + 1.3), hash(id + 7.1)) - 0.5) * 0.6);
            s += float3(1.0) * smoothstep(0.08, 0.0, d) * step(0.8, h) * (0.6 + 0.4 * sin(t * (1.0 + h * 3.0) + h * 30.0));
        }
        col = float3(0.01, 0.01, 0.04) + s;
        """,
        // 10 Calm plasma
        """
        float v1 = sin(p.x * 5.0 + t * 0.4) + sin(p.y * 5.0 + t * 0.3) + sin((p.x + p.y) * 4.0 + t * 0.2) + sin(length(p) * 6.0 - t * 0.4);
        col = (0.5 + 0.5 * cos(v1 * 1.2 + float3(0.0, 2.0, 4.0))) * 0.45;
        """,
        // 11 Sunset shift
        """
        float k = 0.5 + 0.5 * sin(t * 0.05);
        col = mix(float3(0.07, 0.04, 0.20), float3(0.80, 0.30, 0.30), uv.y * (0.6 + 0.4 * k));
        col = mix(col, float3(0.95, 0.70, 0.40), smoothstep(0.7, 1.0, uv.y) * k);
        col *= 0.8;
        """,
        // 12 Neon grid
        """
        float2 g = p * 7.0;
        float2 f = abs(fract(g) - 0.5);
        float l = max(f.x, f.y);
        float pl = 0.5 + 0.5 * sin(t * 0.8 - length(p) * 6.0);
        col = float3(0.02, 0.03, 0.06) + float3(0.10, 0.50, 0.70) * smoothstep(0.44, 0.5, l) * pl;
        """,
        // 13 Contour flow
        """
        float n = fbm(p * 2.5 + t * 0.03);
        float l = abs(fract(n * 8.0 + t * 0.05) - 0.5);
        col = float3(0.03, 0.03, 0.05) + float3(0.50, 0.60, 0.90) * smoothstep(0.05, 0.0, l) * 0.5;
        """,
        // 14 Bokeh
        """
        float3 s = float3(0.0);
        for (int i = 0; i < 6; i++) {
            float fi = float(i);
            float2 c = float2(sin(t * 0.1 * (fi + 1.0) + fi * 3.0) * 0.45, cos(t * 0.08 * (fi + 2.0) + fi * 1.3) * 0.7);
            float d = length(p - c);
            s += (0.5 + 0.5 * cos(fi + float3(0.0, 2.0, 4.0))) * smoothstep(0.25 + 0.03 * fi, 0.0, d) * 0.25;
        }
        col = float3(0.02, 0.02, 0.05) + s;
        """,
        // 15 Diagonal drift
        """
        float s = 0.5 + 0.5 * sin((p.x * 0.8 + p.y) * 20.0 + t * 0.6);
        col = mix(float3(0.04, 0.05, 0.09), float3(0.10, 0.12, 0.20), smoothstep(0.4, 0.6, s));
        """,
        // 16 Spiral
        """
        float a = atan(p.y, p.x);
        float rr = length(p);
        float s = 0.5 + 0.5 * sin(a * 3.0 + log(rr + 0.001) * 8.0 - t * 0.6);
        col = mix(float3(0.03, 0.02, 0.07), float3(0.40, 0.20, 0.70), s * smoothstep(1.0, 0.0, rr));
        """,
        // 17 Smoke
        """
        float n = fbm(p * 2.0 + float2(0.0, -t * 0.08));
        n = fbm(p * 3.0 + n * 1.5 + float2(t * 0.03, 0.0));
        col = mix(float3(0.02, 0.02, 0.03), float3(0.50, 0.50, 0.60), n * n * 1.6);
        """,
        // 18 Digital rain
        """
        float2 g = float2(floor(uv.x * res.x / 14.0), 0.0);
        float h = hash(float2(g.x, 1.0));
        float y = fract(uv.y + t * (0.1 + h * 0.25) + h * 5.0);
        col = float3(0.0, 0.05, 0.02) + float3(0.10, 0.90, 0.40) * pow(1.0 - y, 6.0) * step(0.35, h) * 0.5;
        """,
        // 19 Prism
        """
        float a = atan(p.y, p.x) / 6.28318 + 0.5;
        float k = fract(a * 3.0 + t * 0.02);
        col = (0.5 + 0.5 * cos(6.28318 * (k + float3(0.0, 0.33, 0.67)))) * 0.25 * smoothstep(1.2, 0.0, length(p));
        """,
        // 20 Caustics
        """
        float2 q = p * 4.0;
        float v1 = 0.0;
        for (int i = 0; i < 3; i++) {
            q += float2(sin(q.y + t * 0.4 + float(i)), cos(q.x - t * 0.3));
            v1 += 1.0 / (0.2 + abs(sin(q.x) * cos(q.y)) * 4.0);
        }
        col = float3(0.0, 0.08, 0.12) + float3(0.10, 0.40, 0.50) * v1 * 0.04;
        """,
        // 21 Orbit glow
        """
        float d = length(p - 0.28 * float2(cos(t * 0.3), sin(t * 0.39)));
        float d2 = length(p + 0.22 * float2(cos(t * 0.21), sin(t * 0.4)));
        col = float3(0.02, 0.02, 0.04) + float3(0.90, 0.40, 0.20) * 0.04 / (d + 0.05) + float3(0.20, 0.50, 0.90) * 0.04 / (d2 + 0.05);
        """,
        // 22 Checker wave
        """
        float2 g = p * 8.0 + float2(t * 0.2, t * 0.1);
        float c = mod(floor(g.x) + floor(g.y), 2.0);
        float s = 0.5 + 0.5 * sin(t * 0.5 + length(floor(g)) * 0.6);
        col = mix(float3(0.05, 0.05, 0.08), float3(0.12, 0.16, 0.28), c * s);
        """,
        // 23 Film grain
        """
        col = mix(float3(0.06, 0.05, 0.09), float3(0.15, 0.12, 0.20), uv.y + 0.1 * sin(t * 0.2 + uv.x * 3.0));
        col += (hash(fc + floor(t * 8.0)) - 0.5) * 0.03;
        """,
        // 24 Rain streaks
        """
        float h = hash(float2(floor(uv.x * 60.0), 1.0));
        float y = fract(uv.y * 0.6 - t * (0.15 + h * 0.3) + h * 9.0);
        float s = smoothstep(0.0, 0.02, y) * smoothstep(0.25, 0.02, y);
        col = float3(0.02, 0.03, 0.06) + float3(0.30, 0.50, 0.80) * s * step(0.7, h) * 0.5;
        """
    )

    fun shaderSource(index: Int): String {
        val i = if (index < 0 || index >= BODIES.size) 0 else index
        return PRELUDE + BODIES[i] + EPILOGUE
    }

    /** Two-colour gradient used when a shader cannot be created on this device (never crashes). */
    fun fallbackColors(index: Int): IntArray {
        val h = (index * 37) % 360
        return intArrayOf(Color.HSVToColor(floatArrayOf(h.toFloat(), 0.55f, 0.28f)), Color.HSVToColor(floatArrayOf(((h + 50) % 360).toFloat(), 0.6f, 0.12f)))
    }
}

/** Draws the configured background. All decoding happens off the UI thread. */
class BackgroundRenderer(
    private val ctx: Context,
    private val cfg: BackgroundConfig,
    private val invalidate: () -> Unit,
    private val onProblem: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shaderPaint = Paint()
    private val dimPaint = Paint()
    private val matrix = Matrix()

    private var shader: RuntimeShader? = null
    private var shaderIndex = -1
    private val shaderFailed = BooleanArray(BackgroundFactory.ANIM_COUNT)
    private var fallbackIndex = -1
    private var fallbackW = 0f
    private var fallbackH = 0f

    private var bmp: Bitmap? = null
    private var bmpKey: String? = null
    private var loadingKey: String? = null

    fun isAnimated(): Boolean = cfg.type == 2

    fun release() {
        bmp = null
        bmpKey = null
        shader = null
        exec.shutdown()
    }

    fun draw(c: Canvas, w: Float, h: Float, timeSec: Float) {
        when (cfg.type) {
            1 -> drawBitmapBg(c, w, h, "patterns/p%02d.webp".format(cfg.patternIndex + 1), true, 0f)
            2 -> drawAnimation(c, w, h, timeSec)
            3 -> drawBitmapBg(c, w, h, cfg.wallpaperFile ?: "", false, cfg.wallpaperDim)
            else -> c.drawColor(cfg.solidColor or (0xFF shl 24))
        }
    }

    private fun drawAnimation(c: Canvas, w: Float, h: Float, timeSec: Float) {
        val idx = cfg.animIndex
        if (idx != shaderIndex) {
            shaderIndex = idx
            shader = null
            if (!shaderFailed[idx]) {
                try {
                    shader = RuntimeShader(BackgroundFactory.shaderSource(idx))
                } catch (e: Throwable) {
                    shaderFailed[idx] = true
                    shader = null
                }
            }
        }
        val s = shader
        if (s != null) {
            try {
                s.setFloatUniform("res", w, h)
                s.setFloatUniform("t", timeSec)
                shaderPaint.shader = s
                c.drawRect(0f, 0f, w, h, shaderPaint)
                return
            } catch (e: Throwable) {
                shaderFailed[idx] = true
                shader = null
            }
        }
        // Fallback: slowly moving two-colour gradient.
        val colors = BackgroundFactory.fallbackColors(idx)
        if (fallbackIndex != idx || fallbackW != w || fallbackH != h) {
            fallbackIndex = idx
            fallbackW = w
            fallbackH = h
        }
        val k = (timeSec * 0.05f) % 1f
        val x0 = w * k
        val g = LinearGradient(x0, 0f, x0 + w, h, colors[0], colors[1], Shader.TileMode.MIRROR)
        shaderPaint.shader = g
        c.drawRect(0f, 0f, w, h, shaderPaint)
    }

    private fun drawBitmapBg(c: Canvas, w: Float, h: Float, key: String, isAsset: Boolean, dim: Float) {
        c.drawColor(Color.BLACK)
        if (key.isEmpty()) {
            if (!isAsset) problem("wallpaper")
            return
        }
        if (bmpKey != key && loadingKey != key) {
            loadingKey = key
            exec.execute {
                var b: Bitmap? = null
                try {
                    if (isAsset) {
                        ctx.assets.open(key).use { b = BitmapFactory.decodeStream(it) }
                    } else {
                        val f = File(ctx.filesDir, key)
                        if (f.exists()) b = BitmapFactory.decodeFile(f.path)
                    }
                } catch (e: Throwable) {
                    b = null
                }
                val result = b
                main.post {
                    if (loadingKey == key) {
                        loadingKey = null
                        if (result == null) {
                            if (!isAsset) problem("wallpaper")
                        } else {
                            bmp = result
                            bmpKey = key
                            invalidate()
                        }
                    }
                }
            }
        }
        val b = bmp
        if (b != null && bmpKey == key) {
            val s = max(w / b.width, h / b.height)
            matrix.reset()
            matrix.postScale(s, s)
            matrix.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f)
            c.drawBitmap(b, matrix, bmpPaint)
            if (dim > 0f) {
                dimPaint.color = Color.argb((dim * 255f).toInt(), 0, 0, 0)
                c.drawRect(0f, 0f, w, h, dimPaint)
            }
        }
    }

    private var problemReported = false
    private fun problem(what: String) {
        if (problemReported) return
        problemReported = true
        main.post {
            problemReported = false
            onProblem(what)
        }
    }
}
