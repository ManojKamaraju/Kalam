package com.timer.app

import android.content.Context
import android.graphics.Typeface
import org.json.JSONObject
import java.util.TreeSet
import kotlin.math.abs

class FontFile(val file: String, val italic: Boolean, val variable: Boolean, val weight: Int, val wmin: Int, val wmax: Int)

class FontFamilyInfo(val id: String, val name: String, val category: String, val files: List<FontFile>) {
    fun hasItalic(): Boolean {
        for (f in files) if (f.italic) return true
        return false
    }

    /** Weights this family can really render (static weights, or the steps of a variable axis). */
    fun weights(): List<Int> {
        val s = TreeSet<Int>()
        var pool = ArrayList<FontFile>()
        for (f in files) if (!f.italic) pool.add(f)
        if (pool.isEmpty()) pool = ArrayList(files)
        for (f in pool) {
            if (f.variable) {
                var w = ((f.wmin + 99) / 100) * 100
                var added = false
                while (w <= f.wmax) {
                    s.add(w)
                    added = true
                    w += 100
                }
                if (!added) s.add(f.wmin)
            } else {
                s.add(f.weight)
            }
        }
        if (s.isEmpty()) s.add(400)
        return ArrayList(s)
    }
}

/** Bundled font library (SIL OFL / Apache 2.0 families, see assets/fonts/LICENSES.txt). */
object Fonts {
    var families: List<FontFamilyInfo> = emptyList()
        private set
    private val byId = HashMap<String, FontFamilyInfo>()
    private val cache = HashMap<String, Typeface>()

    fun load(ctx: Context) {
        try {
            val text = ctx.assets.open("fonts/manifest.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val arr = root.getJSONArray("families")
            val list = ArrayList<FontFamilyInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val fa = o.getJSONArray("files")
                val files = ArrayList<FontFile>()
                for (j in 0 until fa.length()) {
                    val f = fa.getJSONObject(j)
                    files.add(
                        FontFile(
                            f.getString("f"), f.optInt("i", 0) == 1, f.optInt("v", 0) == 1,
                            f.optInt("w", 400), f.optInt("wmin", 400), f.optInt("wmax", 400)
                        )
                    )
                }
                if (files.isNotEmpty()) list.add(FontFamilyInfo(o.getString("id"), o.getString("name"), o.optString("category", "Other"), files))
            }
            families = list
            byId.clear()
            for (f in list) byId[f.id] = f
        } catch (e: Exception) {
            families = emptyList() // fonts unavailable: the system default typeface is used
        }
    }

    val categories: List<String>
        get() {
            val out = ArrayList<String>()
            out.add("All")
            for (f in families) if (!out.contains(f.category)) out.add(f.category)
            return out
        }

    fun family(id: String): FontFamilyInfo? = byId[id] ?: (if (families.isNotEmpty()) families[0] else null)

    @Synchronized
    fun typeface(ctx: Context, fontId: String, weight: Int, italic: Boolean): Typeface {
        val fam = family(fontId) ?: return Typeface.DEFAULT
        val wantItalic = italic && fam.hasItalic()
        val key = fam.id + "|" + weight + "|" + wantItalic
        val hit = cache[key]
        if (hit != null) return hit
        var pool = ArrayList<FontFile>()
        for (f in fam.files) if (f.italic == wantItalic) pool.add(f)
        if (pool.isEmpty()) pool = ArrayList(fam.files)
        var tf: Typeface? = null
        try {
            var variableFile: FontFile? = null
            for (f in pool) if (f.variable) {
                variableFile = f
                break
            }
            if (variableFile != null) {
                val w = weight.coerceIn(variableFile.wmin, variableFile.wmax)
                tf = Typeface.Builder(ctx.assets, "fonts/" + variableFile.file)
                    .setFontVariationSettings("'wght' $w")
                    .setWeight(w)
                    .build()
            } else {
                var best: FontFile? = null
                for (f in pool) {
                    val b = best
                    if (b == null || abs(f.weight - weight) < abs(b.weight - weight)) best = f
                }
                if (best != null) tf = Typeface.createFromAsset(ctx.assets, "fonts/" + best.file)
            }
        } catch (e: Exception) {
            tf = null
        }
        val result = tf ?: Typeface.DEFAULT
        cache[key] = result
        return result
    }
}
