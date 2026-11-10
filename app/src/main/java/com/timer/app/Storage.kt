package com.timer.app

import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Versioned configuration schema. Bump CURRENT and add a branch to [migrate] when a structure changes. */
object Schema {
    const val CURRENT = 1

    fun migrate(store: String, data: JSONObject, from: Int): JSONObject {
        var v = from
        val d = data
        while (v < CURRENT) {
            when (v) {
                0 -> { /* v0 -> v1: legacy unversioned files had the same layout, only the envelope was added */ }
            }
            v++
        }
        return d
    }
}

/** One JSON file, written atomically (write to a new file, then swap) so a crash can never leave half a file. */
class JsonStore(dir: File, val name: String) {
    private val file = File(dir, "$name.json")

    fun read(): JSONObject? {
        val af = AtomicFile(file)
        val bytes: ByteArray = try {
            af.readFully()
        } catch (e: IOException) {
            return null
        }
        return try {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val hasEnvelope = root.has("schema") && root.has("data")
            val data = if (hasEnvelope) root.optJSONObject("data") else root
            if (data == null) throw JSONException("missing data")
            Schema.migrate(name, data, if (hasEnvelope) root.optInt("schema", 0) else 0)
        } catch (e: Exception) {
            // Corrupt configuration: keep the broken file for diagnosis and fall back to defaults.
            try {
                file.renameTo(File(file.path + ".corrupt"))
            } catch (e2: Exception) {
            }
            null
        }
    }

    fun serialize(data: JSONObject): String = JSONObject().put("schema", Schema.CURRENT).put("data", data).toString()

    fun writeSync(text: String) {
        val af = AtomicFile(file)
        var os: FileOutputStream? = null
        try {
            os = af.startWrite()
            os.write(text.toByteArray(Charsets.UTF_8))
            af.finishWrite(os)
        } catch (e: IOException) {
            if (os != null) af.failWrite(os)
        }
    }
}

interface ModelListener {
    fun onModelChanged(flags: Int)
}

/**
 * All persistent configuration except the timer session. Separate files per concern:
 * appearance, layout (+Master Layout), background, haptics, alarm, prefs.
 */
class AppModel(private val dir: File) {
    companion object {
        const val D_APPEARANCE = 1
        const val D_LAYOUT = 2
        const val D_BACKGROUND = 4
        const val D_HAPTICS = 8
        const val D_ALARM = 16
        const val D_PREFS = 32
        const val D_ALL = 63
        val io = Executors.newSingleThreadExecutor()
    }

    val styles: Array<ElementStyle> = Array(4) { ElementStyle() }
    val haptics: Array<HapticConfig> = Array(4) { HapticConfig() }
    val layouts: Array<LayoutSet> = arrayOf(LayoutSet(), LayoutSet())
    val masters: Array<LayoutSet?> = arrayOfNulls(2)
    var masterIncludesGlobal: Boolean = true
    val background = BackgroundConfig()
    val alarmFinish = AlarmConfig()
    val alarmTarget = AlarmConfig()
    var targetMs: Long = 0L
    val prefs = AppPrefs()

    val listeners = CopyOnWriteArrayList<ModelListener>()

    private val sAppearance = JsonStore(dir, "appearance")
    private val sLayout = JsonStore(dir, "layout")
    private val sBackground = JsonStore(dir, "background")
    private val sHaptics = JsonStore(dir, "haptics")
    private val sAlarm = JsonStore(dir, "alarm")
    private val sPrefs = JsonStore(dir, "prefs")

    private val handler = Handler(Looper.getMainLooper())
    private var dirty = 0
    private val flushRunnable = Runnable { flushNow() }

    init {
        setDefaults()
    }

    fun resetStyles() {
        for (i in 0 until 4) {
            styles[i].copyFrom(ElementStyle())
        }
        styles[Comp.MILLIS.ordinal].fillColor = 0xB3FFFFFF.toInt()
        styles[Comp.MILLIS.ordinal].outlineColor = 0xB3FFFFFF.toInt()
    }

    fun setDefaults() {
        resetStyles()
        for (i in 0 until 2) {
            layouts[i].copyFrom(LayoutSet())
            masters[i] = null
        }
    }

    fun loadAll() {
        val a = sAppearance.read()
        if (a != null) {
            val arr = a.optJSONArray("styles")
            if (arr != null) for (i in 0 until 4) styles[i].readJson(arr.optJSONObject(i))
        }
        val l = sLayout.read()
        if (l != null) {
            val arr = l.optJSONArray("layouts")
            if (arr != null) for (i in 0 until 2) layouts[i].readJson(arr.optJSONObject(i))
            for (i in 0 until 2) {
                val m = l.optJSONObject("master$i")
                if (m != null) {
                    val ls = LayoutSet()
                    ls.readJson(m)
                    masters[i] = ls
                }
            }
            masterIncludesGlobal = l.optBoolean("masterGlobal", masterIncludesGlobal)
        }
        background.readJson(sBackground.read())
        val h = sHaptics.read()
        if (h != null) {
            val arr = h.optJSONArray("items")
            if (arr != null) for (i in 0 until 4) haptics[i].readJson(arr.optJSONObject(i))
        }
        val al = sAlarm.read()
        if (al != null) {
            alarmFinish.readJson(al.optJSONObject("finish"))
            alarmTarget.readJson(al.optJSONObject("target"))
            targetMs = al.optLong("targetMs", 0L).coerceAtLeast(0L)
        }
        prefs.readJson(sPrefs.read())
    }

    fun markDirty(flags: Int) {
        dirty = dirty or flags
        handler.removeCallbacks(flushRunnable)
        handler.postDelayed(flushRunnable, 350L)
        for (l in listeners) l.onModelChanged(flags)
    }

    /** Serializes on the caller's thread (cheap) and writes on the IO thread (never blocks the UI). */
    fun flushNow() {
        handler.removeCallbacks(flushRunnable)
        val f = dirty
        dirty = 0
        if (f == 0) return
        if (f and D_APPEARANCE != 0) {
            val arr = JSONArray()
            for (s in styles) arr.put(s.toJson())
            enqueue(sAppearance, JSONObject().put("styles", arr))
        }
        if (f and D_LAYOUT != 0) {
            val arr = JSONArray()
            for (s in layouts) arr.put(s.toJson())
            val o = JSONObject().put("layouts", arr).put("masterGlobal", masterIncludesGlobal)
            for (i in 0 until 2) {
                val m = masters[i]
                if (m != null) o.put("master$i", m.toJson())
            }
            enqueue(sLayout, o)
        }
        if (f and D_BACKGROUND != 0) enqueue(sBackground, background.toJson())
        if (f and D_HAPTICS != 0) {
            val arr = JSONArray()
            for (s in haptics) arr.put(s.toJson())
            enqueue(sHaptics, JSONObject().put("items", arr))
        }
        if (f and D_ALARM != 0) {
            enqueue(
                sAlarm,
                JSONObject().put("finish", alarmFinish.toJson()).put("target", alarmTarget.toJson()).put("targetMs", targetMs)
            )
        }
        if (f and D_PREFS != 0) enqueue(sPrefs, prefs.toJson())
    }

    private fun enqueue(store: JsonStore, data: JSONObject) {
        val text = store.serialize(data)
        io.execute { store.writeSync(text) }
    }
}
