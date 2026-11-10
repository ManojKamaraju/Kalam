package com.timer.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The launcher icon is a hollow ring. Android cannot recolour an app icon at runtime, so the manifest declares one
 * launcher entry per ring colour and this object enables exactly one of them (the app keeps running; the launcher
 * refreshes the icon after a moment).
 */
object AppIcon {
    class Ring(val id: String, val name: String, val color: Int)

    val RINGS: List<Ring> = listOf(
        Ring("grey", "Grey", 0xFF9EA3A8.toInt()),
        Ring("white", "White", 0xFFFFFFFF.toInt()),
        Ring("red", "Red", 0xFFE5484D.toInt()),
        Ring("orange", "Orange", 0xFFF76B15.toInt()),
        Ring("yellow", "Yellow", 0xFFF5D90A.toInt()),
        Ring("green", "Green", 0xFF30A46C.toInt()),
        Ring("teal", "Teal", 0xFF12A594.toInt()),
        Ring("blue", "Blue", 0xFF3E63DD.toInt()),
        Ring("purple", "Purple", 0xFF8E4EC6.toInt()),
        Ring("pink", "Pink", 0xFFE93D82.toInt())
    )
    private const val DEFAULT_ID = "grey"

    private fun component(ctx: Context, id: String) = ComponentName(ctx.packageName, "com.timer.app.Icon_" + id)

    fun current(ctx: Context): String {
        val pm = ctx.packageManager
        for (r in RINGS) {
            val s = try { pm.getComponentEnabledSetting(component(ctx, r.id)) } catch (e: Exception) { continue }
            if (s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return r.id
        }
        return DEFAULT_ID
    }

    fun set(ctx: Context, id: String): Boolean {
        if (RINGS.none { it.id == id }) return false
        val pm = ctx.packageManager
        return try {
            pm.setComponentEnabledSetting(component(ctx, id), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (r in RINGS) if (r.id != id) {
                pm.setComponentEnabledSetting(component(ctx, r.id), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
