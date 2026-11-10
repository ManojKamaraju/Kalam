package com.timer.app

import org.json.JSONArray
import org.json.JSONObject

/** Typography + colour of ONE time component. Each of the four components owns its own instance. */
class ElementStyle {
    var fontId: String = "inter"
    var weight: Int = 300
    var fontStyle: Int = 0 // 0 normal, 1 italic, 2 oblique
    var drawMode: Int = 0 // 0 fill, 1 outline only (transparent interior), 2 fill + outline
    var fillColor: Int = -1 // white
    var outlineColor: Int = -1
    var outlineWidth: Float = 0.035f // fraction of the text size

    fun copyFrom(o: ElementStyle) {
        fontId = o.fontId
        weight = o.weight
        fontStyle = o.fontStyle
        drawMode = o.drawMode
        fillColor = o.fillColor
        outlineColor = o.outlineColor
        outlineWidth = o.outlineWidth
    }

    fun toJson(): JSONObject = JSONObject()
        .put("font", fontId).put("weight", weight).put("fontStyle", fontStyle).put("drawMode", drawMode)
        .put("fill", fillColor).put("outline", outlineColor).put("outlineWidth", outlineWidth.toDouble())

    fun readJson(o: JSONObject?) {
        if (o == null) return
        fontId = o.optString("font", fontId)
        weight = o.optInt("weight", weight).coerceIn(1, 1000)
        fontStyle = o.optInt("fontStyle", fontStyle).coerceIn(0, 2)
        drawMode = o.optInt("drawMode", drawMode).coerceIn(0, 2)
        fillColor = o.optInt("fill", fillColor)
        outlineColor = o.optInt("outline", outlineColor)
        outlineWidth = o.optDouble("outlineWidth", outlineWidth.toDouble()).toFloat().coerceIn(0.002f, 0.2f)
    }
}

class HapticConfig {
    var enabled: Boolean = false
    var intervalMs: Long = 1000L
    var intensity: Int = 160 // 1..255
    var durationMs: Int = 30
    var pattern: Int = 0

    fun copyFrom(o: HapticConfig) {
        enabled = o.enabled; intervalMs = o.intervalMs; intensity = o.intensity; durationMs = o.durationMs; pattern = o.pattern
    }

    fun toJson(): JSONObject = JSONObject().put("on", enabled).put("interval", intervalMs).put("intensity", intensity)
        .put("duration", durationMs).put("pattern", pattern)

    fun readJson(o: JSONObject?) {
        if (o == null) return
        enabled = o.optBoolean("on", enabled)
        intervalMs = o.optLong("interval", intervalMs).coerceAtLeast(20L)
        intensity = o.optInt("intensity", intensity).coerceIn(1, 255)
        durationMs = o.optInt("duration", durationMs).coerceIn(5, 2000)
        pattern = o.optInt("pattern", pattern).coerceIn(0, Haptics.PATTERNS.size - 1)
    }
}

/** Position (as a fraction of the visible frame, relative to its centre) and size of one component. */
class Pose(var x: Float = 0f, var y: Float = 0f, var scale: Float = 1f) {
    fun set(o: Pose) {
        x = o.x; y = o.y; scale = o.scale
    }

    fun toJson(): JSONObject = JSONObject().put("x", x.toDouble()).put("y", y.toDouble()).put("s", scale.toDouble())

    fun readJson(o: JSONObject?) {
        if (o == null) return
        x = o.optDouble("x", x.toDouble()).toFloat()
        y = o.optDouble("y", y.toDouble()).toFloat()
        scale = o.optDouble("s", scale.toDouble()).toFloat().coerceIn(0.05f, 30f)
    }
}

/** A complete arrangement: four poses + global scale + global rotation. One exists per screen class (portrait / landscape). */
class LayoutSet {
    val poses: Array<Pose> = Array(4) { Pose() }
    var flowRow: Boolean = true // auto-flowing single line until the user moves something
    val seps: BooleanArray = booleanArrayOf(true, true, true, false) // trailing ":" ":" "." per component
    var globalScale: Float = 1f
    var globalRotation: Float = 0f
    var presetId: Int = 0

    fun copyFrom(o: LayoutSet) {
        for (i in 0 until 4) poses[i].set(o.poses[i])
        flowRow = o.flowRow
        for (i in 0 until 4) seps[i] = o.seps[i]
        globalScale = o.globalScale
        globalRotation = o.globalRotation
        presetId = o.presetId
    }

    fun copy(): LayoutSet {
        val l = LayoutSet()
        l.copyFrom(this)
        return l
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (p in poses) arr.put(p.toJson())
        val sp = JSONArray()
        for (b in seps) sp.put(b)
        return JSONObject().put("poses", arr).put("flow", flowRow).put("seps", sp).put("gs", globalScale.toDouble())
            .put("gr", globalRotation.toDouble()).put("preset", presetId)
    }

    fun readJson(o: JSONObject?) {
        if (o == null) return
        val arr = o.optJSONArray("poses")
        if (arr != null) for (i in 0 until 4) poses[i].readJson(arr.optJSONObject(i))
        flowRow = o.optBoolean("flow", flowRow)
        val sp = o.optJSONArray("seps")
        if (sp != null && sp.length() >= 4) for (i in 0 until 4) seps[i] = sp.optBoolean(i, seps[i])
        globalScale = o.optDouble("gs", globalScale.toDouble()).toFloat().coerceIn(0.05f, 20f)
        globalRotation = o.optDouble("gr", globalRotation.toDouble()).toFloat()
        presetId = o.optInt("preset", presetId)
    }
}

class BackgroundConfig {
    var type: Int = 0 // 0 solid, 1 pattern, 2 animation, 3 wallpaper
    var solidColor: Int = 0xFF000000.toInt()
    var patternIndex: Int = 0
    var animIndex: Int = 0
    var wallpaperFile: String? = null
    var wallpaperDim: Float = 0.25f

    fun toJson(): JSONObject {
        val o = JSONObject().put("type", type).put("solid", solidColor).put("pattern", patternIndex)
            .put("anim", animIndex).put("dim", wallpaperDim.toDouble())
        if (wallpaperFile != null) o.put("wallpaper", wallpaperFile)
        return o
    }

    fun readJson(o: JSONObject?) {
        if (o == null) return
        type = o.optInt("type", type).coerceIn(0, 3)
        solidColor = o.optInt("solid", solidColor)
        patternIndex = o.optInt("pattern", patternIndex).coerceIn(0, BackgroundFactory.PATTERN_COUNT - 1)
        animIndex = o.optInt("anim", animIndex).coerceIn(0, BackgroundFactory.ANIM_COUNT - 1)
        wallpaperFile = if (o.has("wallpaper")) o.optString("wallpaper") else null
        wallpaperDim = o.optDouble("dim", wallpaperDim.toDouble()).toFloat().coerceIn(0f, 0.85f)
    }
}

class AlarmConfig {
    var enabled: Boolean = true
    var source: Int = 0 // 0 built-in, 1 user file
    var builtinId: String = "alarm_soft_chime"
    var customFile: String? = null
    var customName: String? = null
    var volume: Float = 0.8f
    var loopSeconds: Int = 30 // 0 = until dismissed
    var vibrate: Boolean = true

    fun toJson(): JSONObject {
        val o = JSONObject().put("on", enabled).put("source", source).put("builtin", builtinId)
            .put("volume", volume.toDouble()).put("loop", loopSeconds).put("vibrate", vibrate)
        if (customFile != null) o.put("file", customFile)
        if (customName != null) o.put("name", customName)
        return o
    }

    fun readJson(o: JSONObject?) {
        if (o == null) return
        enabled = o.optBoolean("on", enabled)
        source = o.optInt("source", source).coerceIn(0, 1)
        builtinId = o.optString("builtin", builtinId)
        customFile = if (o.has("file")) o.optString("file") else null
        customName = if (o.has("name")) o.optString("name") else null
        volume = o.optDouble("volume", volume.toDouble()).toFloat().coerceIn(0f, 1f)
        loopSeconds = o.optInt("loop", loopSeconds).coerceIn(0, 3600)
        vibrate = o.optBoolean("vibrate", vibrate)
    }
}

/** All application preferences that are not typography, layout, background, haptics or alarm. */
class AppPrefs {
    // timer behaviour
    var editWhileRunning: Boolean = true
    var keepScreenOn: Boolean = true
    val rollover: BooleanArray = booleanArrayOf(true, true, true) // ms->s, s->m, m->h

    // persistence (see docs/ARCHITECTURE.md for precedence)
    var rememberAfterClosing: Boolean = true
    var resetOnOpen: Boolean = false
    var resetOnClose: Boolean = false

    // background execution + notification
    var runInBackground: Boolean = true
    var countTimeWhileOff: Boolean = true
    var notificationShowsTime: Boolean = true

    // gestures
    var scrollStepDp: Float = 16f
    var pickupMs: Int = 180
    var longPressMs: Int = 450
    var tapWindowMs: Int = 280
    var pinchSensitivity: Float = 1f
    var rotateSensitivity: Float = 1f
    var gestureFeedback: Boolean = true
    var elementTapIsBackgroundTap: Boolean = true

    // global appearance / motion
    var followOrientation: Boolean = true
    var animFps: Int = 30

    // haptics master switch (per-component settings live in HapticConfig)
    var hapticsMaster: Boolean = true

    fun toJson(): JSONObject = JSONObject()
        .put("editWhileRunning", editWhileRunning).put("keepScreenOn", keepScreenOn)
        .put("roll", JSONArray().put(rollover[0]).put(rollover[1]).put(rollover[2]))
        .put("remember", rememberAfterClosing).put("resetOnOpen", resetOnOpen).put("resetOnClose", resetOnClose)
        .put("bg", runInBackground).put("countOff", countTimeWhileOff).put("notifTime", notificationShowsTime)
        .put("scrollStep", scrollStepDp.toDouble()).put("pickup", pickupMs).put("longPress", longPressMs)
        .put("tapWindow", tapWindowMs).put("pinchSens", pinchSensitivity.toDouble())
        .put("rotSens", rotateSensitivity.toDouble()).put("gestureFb", gestureFeedback)
        .put("elTap", elementTapIsBackgroundTap).put("followOrientation", followOrientation)
        .put("fps", animFps).put("hapticsMaster", hapticsMaster)

    fun readJson(o: JSONObject?) {
        if (o == null) return
        editWhileRunning = o.optBoolean("editWhileRunning", editWhileRunning)
        keepScreenOn = o.optBoolean("keepScreenOn", keepScreenOn)
        val r = o.optJSONArray("roll")
        if (r != null && r.length() >= 3) for (i in 0 until 3) rollover[i] = r.optBoolean(i, rollover[i])
        rememberAfterClosing = o.optBoolean("remember", rememberAfterClosing)
        resetOnOpen = o.optBoolean("resetOnOpen", resetOnOpen)
        resetOnClose = o.optBoolean("resetOnClose", resetOnClose)
        runInBackground = o.optBoolean("bg", runInBackground)
        countTimeWhileOff = o.optBoolean("countOff", countTimeWhileOff)
        notificationShowsTime = o.optBoolean("notifTime", notificationShowsTime)
        scrollStepDp = o.optDouble("scrollStep", scrollStepDp.toDouble()).toFloat().coerceIn(6f, 60f)
        pickupMs = o.optInt("pickup", pickupMs).coerceIn(80, 400)
        longPressMs = o.optInt("longPress", longPressMs).coerceIn(250, 1200)
        tapWindowMs = o.optInt("tapWindow", tapWindowMs).coerceIn(150, 600)
        pinchSensitivity = o.optDouble("pinchSens", pinchSensitivity.toDouble()).toFloat().coerceIn(0.3f, 3f)
        rotateSensitivity = o.optDouble("rotSens", rotateSensitivity.toDouble()).toFloat().coerceIn(0.3f, 3f)
        gestureFeedback = o.optBoolean("gestureFb", gestureFeedback)
        elementTapIsBackgroundTap = o.optBoolean("elTap", elementTapIsBackgroundTap)
        followOrientation = o.optBoolean("followOrientation", followOrientation)
        animFps = o.optInt("fps", animFps).let { if (it <= 15) 15 else if (it <= 30) 30 else 60 }
        hapticsMaster = o.optBoolean("hapticsMaster", hapticsMaster)
    }
}

object Haptics {
    val PATTERNS: List<String> = listOf("Single pulse", "Double pulse", "Triple pulse", "Rising ramp", "Heartbeat", "System tick")
}

object Alarms {
    /** id (= raw resource name) to display name */
    val BUILTIN: List<Pair<String, String>> = listOf(
        Pair("alarm_soft_chime", "Soft chime"),
        Pair("alarm_gentle_bells", "Gentle bells"),
        Pair("alarm_minimal_ping", "Minimal ping"),
        Pair("alarm_notification", "Notification style"),
        Pair("alarm_digital", "Digital beeps"),
        Pair("alarm_classic_bell", "Traditional bell"),
        Pair("alarm_rapid_pulse", "Rapid pulse"),
        Pair("alarm_urgent_siren", "Urgent siren")
    )

    fun nameOf(id: String): String {
        for (p in BUILTIN) if (p.first == id) return p.second
        return id
    }
}
