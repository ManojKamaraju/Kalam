package com.timer.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.abs

interface MenuHost {
    val timerView: TimerView
    fun pickImage()
    fun pickAudio(slot: Int)
    fun closeApp()
    fun requestNotifications()
    fun notificationsAllowed(): Boolean
    fun applyOrientationPolicy()
}

/** Builds every contextual menu. Each element exposes only the settings that apply to it. */
class Menus(private val ctx: Context, private val core: Core, private val host: MenuHost, private val menu: MenuLayer) {
    private val model = core.model
    private val prefs = model.prefs
    private val preview = AlarmPlayer(ctx)
    private var confirmReset = false

    companion object {
        val NAMES = arrayOf("Hours", "Minutes", "Seconds", "Milliseconds")
        private val WEIGHT_NAMES = mapOf(
            100 to "Thin", 200 to "Extra light", 300 to "Light", 400 to "Regular", 500 to "Medium",
            600 to "Semibold", 700 to "Bold", 800 to "Extra bold", 900 to "Black"
        )
    }

    private fun dp(v: Float) = Ui.dp(ctx, v)

    fun stopPreview() {
        preview.stop()
    }

    private fun appearanceChanged() {
        model.markDirty(AppModel.D_APPEARANCE)
    }

    private fun prefsChanged() {
        model.markDirty(AppModel.D_PREFS)
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    // ================================================================== element menu

    fun elementMenu(i: Int): Page = Page(NAMES[i]) { b ->
        val st = model.styles[i]
        val fam = Fonts.family(st.fontId)
        b.header("Typography")
        b.nav("Font", fam?.name ?: "Default") { menu.push(fontPage(i)) }
        b.nav("Weight", WEIGHT_NAMES[st.weight] ?: ("Weight " + st.weight)) { menu.push(weightPage(i)) }
        b.nav("Style", arrayOf("Normal", "Italic", "Oblique")[st.fontStyle]) { menu.push(stylePage(i)) }
        b.nav("Text rendering", arrayOf("Fill", "Outline only", "Fill + outline")[st.drawMode]) { menu.push(renderPage(i)) }
        b.header("Colour")
        if (st.drawMode != 1) {
            b.colorRow("Fill colour", st.fillColor) {
                menu.push(colorPage("Fill colour", st.fillColor, true) { c -> st.fillColor = c; appearanceChanged() })
            }
        }
        if (st.drawMode != 0) {
            b.colorRow("Outline colour", st.outlineColor) {
                menu.push(colorPage("Outline colour", st.outlineColor, true) { c -> st.outlineColor = c; appearanceChanged() })
            }
            b.slider("Outline thickness", (st.outlineWidth * 500f).toInt().coerceIn(1, 100), 100, { p -> "%.1f%%".format(p / 5f) }) { p ->
                st.outlineWidth = max1(p) / 500f
                appearanceChanged()
            }
        }
        if (i < 3) {
            b.header("Layout")
            val l = host.timerView.layout()
            b.toggle("Separator after this number", l.seps[i], if (i == 2) "Decimal point" else "Colon") { v ->
                l.seps[i] = v
                model.markDirty(AppModel.D_LAYOUT)
            }
        }
        b.header("Rollover")
        when (i) {
            0 -> b.note("Hours have no automatic next component and no maximum.")
            else -> {
                val idx = when (i) { 3 -> 0; 2 -> 1; else -> 2 }
                val target = when (i) { 3 -> "seconds"; 2 -> "minutes"; else -> "hours" }
                b.toggle("Carry into $target", prefs.rollover[idx], "When off, this number wraps inside its own range") { v ->
                    prefs.rollover[idx] = v
                    prefsChanged()
                }
            }
        }
        b.header("Haptics")
        b.nav("Haptic feedback", onOff(model.haptics[i].enabled)) { menu.push(hapticPage(i)) }
        b.divider()
        b.action("Apply this style to all numbers") {
            for (k in 0 until 4) if (k != i) model.styles[k].copyFrom(st)
            appearanceChanged()
            core.message("Style applied to all numbers")
        }
        b.nav("All settings", null) { menu.push(hubPage()) }
    }

    private fun max1(p: Int) = if (p < 1) 1f else p.toFloat()

    private fun fontPage(i: Int) = Page("Font") { b ->
        val st = model.styles[i]
        val categories = Fonts.categories
        var cat = "All"
        val shown = ArrayList<FontFamilyInfo>()
        val lv = ListView(ctx)
        val chips = LinearLayout(ctx)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.setPadding(dp(12f), dp(4f), dp(12f), dp(8f))
        val chipViews = ArrayList<TextView>()

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val f = shown[position]
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.minimumHeight = dp(56f)
                row.setPadding(dp(18f), dp(4f), dp(18f), dp(4f))
                val selected = f.id == st.fontId
                val name = TextView(ctx)
                name.text = f.name
                name.textSize = 14f
                name.setTextColor(if (selected) Ui.ACCENT else Ui.DIM)
                row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val sample = TextView(ctx)
                sample.text = "01:23.456"
                sample.textSize = 22f
                sample.setTextColor(if (selected) Ui.ACCENT else Ui.TEXT)
                sample.typeface = Fonts.typeface(ctx, f.id, 400, false)
                row.addView(sample)
                return row
            }
        }
        lv.adapter = adapter
        lv.divider = null
        lv.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }

        fun refill() {
            shown.clear()
            for (f in Fonts.families) if (cat == "All" || f.category == cat) shown.add(f)
            adapter.notifyDataSetChanged()
            for (k in chipViews.indices) {
                val on = categories[k] == cat
                chipViews[k].setTextColor(if (on) Color.BLACK else Ui.TEXT)
                chipViews[k].background = Ui.rounded(if (on) Ui.ACCENT else 0x33FFFFFF, dp(18f).toFloat())
            }
        }
        for (c in categories) {
            val t = TextView(ctx)
            t.text = c
            t.textSize = 14f
            t.gravity = Gravity.CENTER
            t.setPadding(dp(16f), 0, dp(16f), 0)
            t.setOnClickListener {
                cat = c
                refill()
            }
            chipViews.add(t)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36f))
            lp.rightMargin = dp(8f)
            chips.addView(t, lp)
        }
        refill()
        lv.setOnItemClickListener { _, _, pos, _ ->
            val f = shown[pos]
            st.fontId = f.id
            val ws = f.weights()
            if (!ws.contains(st.weight)) {
                var best = ws[0]
                for (w in ws) if (abs(w - st.weight) < abs(best - st.weight)) best = w
                st.weight = best
            }
            appearanceChanged()
            adapter.notifyDataSetChanged()
        }
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(chips)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.addView(hs)
        box.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380f)))
        b.custom(box, 0)
        // warm the typeface cache off the UI thread so scrolling stays smooth
        Thread {
            for (f in Fonts.families) Fonts.typeface(ctx, f.id, 400, false)
            lv.post { adapter.notifyDataSetChanged() }
        }.start()
    }

    private fun weightPage(i: Int) = Page("Weight") { b ->
        val st = model.styles[i]
        val fam = Fonts.family(st.fontId)
        val weights = fam?.weights() ?: listOf(400)
        b.note("Available weights depend on the selected font.")
        for (w in weights) {
            val tf = Fonts.typeface(ctx, st.fontId, w, st.fontStyle == 1)
            b.radio((WEIGHT_NAMES[w] ?: "Weight $w") + "   01:23", st.weight == w, tf, 20f) {
                st.weight = w
                appearanceChanged()
                menu.refresh()
            }
        }
    }

    private fun stylePage(i: Int) = Page("Style") { b ->
        val st = model.styles[i]
        val fam = Fonts.family(st.fontId)
        val italicNative = fam?.hasItalic() ?: false
        val names = arrayOf("Normal", "Italic", "Oblique")
        for (k in 0 until 3) {
            val label = if (k == 1 && !italicNative) "Italic (slanted automatically)" else names[k]
            b.radio(label, st.fontStyle == k) {
                st.fontStyle = k
                appearanceChanged()
                menu.refresh()
            }
        }
    }

    private fun renderPage(i: Int) = Page("Text rendering") { b ->
        val st = model.styles[i]
        b.radio("Fill", st.drawMode == 0) { st.drawMode = 0; appearanceChanged(); menu.pop() }
        b.radio("Outline only (transparent inside)", st.drawMode == 1) { st.drawMode = 1; appearanceChanged(); menu.pop() }
        b.radio("Fill + outline", st.drawMode == 2) { st.drawMode = 2; appearanceChanged(); menu.pop() }
        b.note("Outline mode draws real stroked contours, so the inside of each digit shows the background.")
    }

    fun colorPage(title: String, initial: Int, withAlpha: Boolean, onChange: (Int) -> Unit): Page = Page(title) { b ->
        val picker = ColorPickerView(ctx, initial, withAlpha) { c -> onChange(c) }
        b.custom(picker, 0)
        b.field("Hex colour", ColorPickerView.toHex(initial, withAlpha), if (withAlpha) "#AARRGGBB" else "#RRGGBB", false) { text ->
            val c = ColorPickerView.parseHex(text, withAlpha)
            if (c != null) picker.setColor(c, true) else core.message("Not a valid colour")
        }
        val quick = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFE6E6E6.toInt(), 0xFF9E9E9E.toInt(), 0xFF000000.toInt(), 0xFFFF5252.toInt(), 0xFFFFA726.toInt(),
            0xFFFFEE58.toInt(), 0xFF66BB6A.toInt(), 0xFF26C6DA.toInt(), 0xFF42A5F5.toInt(), 0xFF7E57C2.toInt(), 0xFFEC407A.toInt()
        )
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(14f), dp(6f), dp(14f), dp(14f))
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        for (q in quick) {
            val v = View(ctx)
            val g = Ui.rounded(q, dp(22f).toFloat())
            g.setStroke(dp(1.5f), 0x66FFFFFF)
            v.background = g
            v.setOnClickListener {
                val keep = if (withAlpha) q else q
                picker.setColor(keep, true)
            }
            val lp = LinearLayout.LayoutParams(dp(44f), dp(44f))
            lp.rightMargin = dp(10f)
            row.addView(v, lp)
        }
        hs.addView(row)
        b.custom(hs, 0)
    }

    private fun hapticPage(i: Int) = Page(NAMES[i] + " haptics") { b ->
        val hc = model.haptics[i]
        fun changed() {
            model.markDirty(AppModel.D_HAPTICS)
            core.onTimingPreferenceChanged()
        }
        if (!prefs.hapticsMaster) b.note("Haptics are switched off globally in Settings > Haptic configuration.")
        b.toggle("Haptic feedback", hc.enabled) { v -> hc.enabled = v; changed(); menu.refresh() }
        b.field("Repeat every (seconds, any value such as 1, 5, 75.5, 86)", TimeFormat.secondsText(hc.intervalMs), "seconds", true) { text ->
            val ms = TimeFormat.parse(text)
            if (ms == null || ms < 20L) {
                core.message("Enter a time of at least 0.02 seconds")
            } else {
                hc.intervalMs = ms
                changed()
            }
        }
        b.slider("Intensity", hc.intensity, 255, { p -> (p * 100 / 255).toString() + "%" }) { p ->
            hc.intensity = if (p < 1) 1 else p
            model.markDirty(AppModel.D_HAPTICS)
        }
        b.slider("Duration", hc.durationMs, 1000, { p -> p.toString() + " ms" }) { p ->
            hc.durationMs = if (p < 5) 5 else p
            model.markDirty(AppModel.D_HAPTICS)
        }
        b.header("Pattern")
        for (k in Haptics.PATTERNS.indices) {
            b.radio(Haptics.PATTERNS[k], hc.pattern == k) {
                hc.pattern = k
                changed()
                HapticPlayer.play(ctx, hc)
                menu.refresh()
            }
        }
        b.action("Test vibration") { HapticPlayer.play(ctx, hc) }
        b.note("Intensity, duration and patterns depend on your phone's vibration hardware. In countdown mode, haptic events stop at the end of the countdown.")
    }

    // ================================================================== background menu / settings hub

    fun hubPage(): Page = Page("Settings") { b ->
        b.nav("Background", null) { menu.push(backgroundPage()) }
        b.nav("Master Layout", null) { menu.push(masterPage()) }
        b.nav("Timer behaviour", null) { menu.push(timerPage()) }
        b.nav("Persistence", null) { menu.push(persistencePage()) }
        b.nav("Background execution", null) { menu.push(execPage()) }
        b.nav("Notifications", null) { menu.push(notificationPage()) }
        b.nav("Gesture configuration", null) { menu.push(gesturePage()) }
        b.nav("Global appearance", null) { menu.push(appearancePage()) }
        b.nav("Haptic configuration", null) { menu.push(hapticHubPage()) }
        b.nav("Alarm configuration", null) { menu.push(alarmHubPage()) }
        b.nav("Advanced", null) { menu.push(advancedPage()) }
    }

    private fun backgroundPage() = Page("Background") { b ->
        val bg = model.background
        b.nav("Solid colour", if (bg.type == 0) "Active" else null) {
            menu.push(colorPage("Background colour", bg.solidColor, false) { c ->
                bg.type = 0
                bg.solidColor = c
                model.markDirty(AppModel.D_BACKGROUND)
            })
        }
        b.nav("Patterns", if (bg.type == 1) "Active" else null) { menu.push(patternsPage()) }
        b.nav("Animations", if (bg.type == 2) "Active" else null) { menu.push(animationsPage()) }
        b.nav("Wallpaper from gallery", if (bg.type == 3) "Active" else null) { menu.push(wallpaperPage()) }
    }

    private fun patternsPage() = Page("Patterns") { b ->
        val bg = model.background
        for (k in 0 until BackgroundFactory.PATTERN_COUNT) {
            b.radio(BackgroundFactory.PATTERN_NAMES[k], bg.type == 1 && bg.patternIndex == k) {
                bg.type = 1
                bg.patternIndex = k
                model.markDirty(AppModel.D_BACKGROUND)
                menu.refresh()
            }
        }
    }

    private fun animationsPage() = Page("Animations") { b ->
        val bg = model.background
        b.note("Animations run on the GPU and slow down to the frame rate set in Global appearance to save battery.")
        for (k in 0 until BackgroundFactory.ANIM_COUNT) {
            b.radio(BackgroundFactory.ANIM_NAMES[k], bg.type == 2 && bg.animIndex == k) {
                bg.type = 2
                bg.animIndex = k
                model.markDirty(AppModel.D_BACKGROUND)
                menu.refresh()
            }
        }
    }

    private fun wallpaperPage() = Page("Wallpaper") { b ->
        val bg = model.background
        b.action("Choose image from gallery") { host.pickImage() }
        if (bg.wallpaperFile != null) {
            b.radio("Use my wallpaper", bg.type == 3) {
                bg.type = 3
                model.markDirty(AppModel.D_BACKGROUND)
                menu.refresh()
            }
            b.slider("Dim", (bg.wallpaperDim * 100f).toInt(), 85, { p -> "$p%" }) { p ->
                bg.wallpaperDim = p / 100f
                model.markDirty(AppModel.D_BACKGROUND)
            }
            b.action("Remove wallpaper", true) {
                bg.type = 0
                bg.wallpaperFile = null
                model.markDirty(AppModel.D_BACKGROUND)
                menu.refresh()
            }
        }
        b.note("Your original image is never modified. KALAM keeps a private resized copy and needs no storage permission.")
    }

    private fun masterPage() = Page("Master Layout") { b ->
        val l = host.timerView.layout()
        b.header("Arrangements")
        for (k in Presets.NAMES.indices) {
            b.radio(Presets.NAMES[k], l.presetId == k) {
                host.timerView.applyPreset(k)
                menu.refresh()
            }
        }
        b.note("Presets are starting points. Drag, scale and rotate afterwards.")
        b.header("Your Master Layout")
        b.action("Save current arrangement as Master Layout") { host.timerView.saveMaster() }
        b.action("Restore Master Layout") {
            host.timerView.restoreMaster()
            menu.dismiss()
        }
        b.toggle("Include global size and rotation", model.masterIncludesGlobal) { v ->
            model.masterIncludesGlobal = v
            model.markDirty(AppModel.D_LAYOUT)
        }
        b.action("Reset global size and rotation") { host.timerView.resetGlobalTransform() }
        b.note("Triple-tap the background to restore your Master Layout at any time. Portrait and landscape keep separate layouts.")
    }

    private fun timerPage() = Page("Timer behaviour") { b ->
        b.note("One timer, two behaviours, no switch. Tap while all numbers are zero and it counts up like a stopwatch. Scroll any number and it becomes a countdown from zero plus what you scrolled; if it was running it immediately counts back down to 00:00:00.000, then rings. Double-tap resets to zero.")
        b.header("Editing")
        b.toggle("Allow editing while running", prefs.editWhileRunning) { v -> prefs.editWhileRunning = v; prefsChanged() }
        b.toggle("Keep screen on while running", prefs.keepScreenOn) { v -> prefs.keepScreenOn = v; prefsChanged(); core.notifyTimerChanged() }
        b.header("Rollover (independent switches)")
        b.toggle("Milliseconds → Seconds", prefs.rollover[0]) { v -> prefs.rollover[0] = v; prefsChanged() }
        b.toggle("Seconds → Minutes", prefs.rollover[1]) { v -> prefs.rollover[1] = v; prefsChanged() }
        b.toggle("Minutes → Hours", prefs.rollover[2]) { v -> prefs.rollover[2] = v; prefsChanged() }
        b.note("On: scrolling past a boundary carries into (or borrows from) the next number. Off: the number wraps inside its own range. Hours have no limit.")
    }

    private fun persistencePage() = Page("Persistence") { b ->
        b.toggle("Remember timer after closing", prefs.rememberAfterClosing, "Restores numbers and running state") { v -> prefs.rememberAfterClosing = v; prefsChanged() }
        b.toggle("Reset timer when reopening", prefs.resetOnOpen, "Takes priority over remembering") { v -> prefs.resetOnOpen = v; prefsChanged() }
        b.toggle("Reset timer when closing", prefs.resetOnClose, "Applies to the Close KALAM action") { v -> prefs.resetOnClose = v; prefsChanged() }
        b.note("Precedence: Reset when reopening always wins over Remember. Resets only ever touch the four numbers; layout, fonts, colours, background, haptics and alarms are never reset.")
    }

    private fun execPage() = Page("Background execution") { b ->
        b.toggle("Run timer in background", prefs.runInBackground, "Keeps timing and shows a notification") { v ->
            prefs.runInBackground = v
            prefsChanged()
            core.onTimingPreferenceChanged()
        }
        b.toggle("Count time while device is off", prefs.countTimeWhileOff, "After a restart, add the time the phone was powered off") { v ->
            prefs.countTimeWhileOff = v
            prefsChanged()
        }
        b.note("When off, the timer pauses as soon as you leave KALAM and stays paused when you return.")
    }

    private fun notificationPage() = Page("Notifications") { b ->
        b.toggle("Show time in notification", prefs.notificationShowsTime) { v ->
            prefs.notificationShowsTime = v
            prefsChanged()
            core.onTimingPreferenceChanged()
        }
        val ok = host.notificationsAllowed()
        b.nav("Notification permission", if (ok) "Allowed" else "Blocked") {
            host.requestNotifications()
        }
        b.note("Without notification permission the timer still runs, but you will not see it in the notification shade.")
    }

    private fun gesturePage() = Page("Gesture configuration") { b ->
        b.slider("Scroll distance per step", prefs.scrollStepDp.toInt(), 60, { p -> "$p dp" }) { p ->
            prefs.scrollStepDp = if (p < 6) 6f else p.toFloat()
            prefsChanged()
        }
        b.slider("Pick-up delay (hold before dragging)", prefs.pickupMs, 400, { p -> "$p ms" }) { p ->
            prefs.pickupMs = p.coerceIn(80, 400)
            prefsChanged()
        }
        b.slider("Long-press delay", prefs.longPressMs, 1200, { p -> "$p ms" }) { p ->
            prefs.longPressMs = p.coerceIn(250, 1200)
            prefsChanged()
        }
        b.slider("Multi-tap window", prefs.tapWindowMs, 600, { p -> "$p ms" }) { p ->
            prefs.tapWindowMs = p.coerceIn(150, 600)
            prefsChanged()
        }
        b.slider("Pinch sensitivity", (prefs.pinchSensitivity * 100f).toInt(), 300, { p -> "$p%" }) { p ->
            prefs.pinchSensitivity = (if (p < 30) 30 else p) / 100f
            prefsChanged()
        }
        b.slider("Rotation sensitivity", (prefs.rotateSensitivity * 100f).toInt(), 300, { p -> "$p%" }) { p ->
            prefs.rotateSensitivity = (if (p < 30) 30 else p) / 100f
            prefsChanged()
        }
        b.toggle("Gesture feedback (subtle vibration)", prefs.gestureFeedback) { v -> prefs.gestureFeedback = v; prefsChanged() }
        b.toggle("Tapping a number pauses/resumes", prefs.elementTapIsBackgroundTap, "Off: only taps on the background count") { v ->
            prefs.elementTapIsBackgroundTap = v
            prefsChanged()
        }
        b.action("Reset gesture settings") {
            val d = AppPrefs()
            prefs.scrollStepDp = d.scrollStepDp; prefs.pickupMs = d.pickupMs; prefs.longPressMs = d.longPressMs
            prefs.tapWindowMs = d.tapWindowMs; prefs.pinchSensitivity = d.pinchSensitivity
            prefs.rotateSensitivity = d.rotateSensitivity; prefs.gestureFeedback = d.gestureFeedback
            prefs.elementTapIsBackgroundTap = d.elementTapIsBackgroundTap
            prefsChanged()
            menu.refresh()
        }
        b.note("Increase the delays or lower the sensitivities if gestures trigger by accident. Lower scroll distance changes numbers faster.")
    }

    private fun appearancePage() = Page("Global appearance") { b ->
        b.toggle("Follow device orientation", prefs.followOrientation, "All four orientations, including upside down") { v ->
            prefs.followOrientation = v
            prefsChanged()
            host.applyOrientationPolicy()
        }
        b.header("Animated background frame rate")
        for (f in intArrayOf(15, 30, 60)) {
            b.radio("$f fps", prefs.animFps == f) {
                prefs.animFps = f
                prefsChanged()
                menu.refresh()
            }
        }
        b.header("Reset")
        b.action("Reset global size and rotation") { host.timerView.resetGlobalTransform() }
        b.action("Reset fonts and colours of all numbers") {
            model.resetStyles()
            appearanceChanged()
            core.message("Fonts and colours reset")
        }
    }

    private fun hapticHubPage() = Page("Haptic configuration") { b ->
        b.toggle("Haptics enabled (master switch)", prefs.hapticsMaster) { v ->
            prefs.hapticsMaster = v
            prefsChanged()
            core.onTimingPreferenceChanged()
        }
        b.header("Per number")
        for (i in 0 until 4) {
            b.nav(NAMES[i], onOff(model.haptics[i].enabled)) { menu.push(hapticPage(i)) }
        }
        b.note("Every number has its own interval, intensity, duration and pattern. They never affect each other.")
    }

    private fun alarmHubPage() = Page("Alarm configuration") { b ->
        b.nav("Countdown finished", onOff(model.alarmFinish.enabled)) { menu.push(alarmPage(0)) }
        b.nav("Stopwatch target reached", if (model.targetMs > 0L) TimeFormat.full(model.targetMs).substringBefore('.') else "Not set") {
            menu.push(alarmPage(1))
        }
        b.note("Tap anywhere on the screen, or use the notification, to stop a ringing alarm.")
    }

    fun alarmPage(which: Int) = Page(if (which == 0) "Countdown alarm" else "Stopwatch target alarm") { b ->
        val cfg = if (which == 0) model.alarmFinish else model.alarmTarget
        fun changed() = model.markDirty(AppModel.D_ALARM)
        b.toggle("Alarm enabled", cfg.enabled) { v -> cfg.enabled = v; changed() }
        if (which == 1) {
            b.field("Target time (for example 5:00 or 90). Use 0 for none.", if (model.targetMs > 0L) TimeFormat.secondsText(model.targetMs) else "0", "seconds or H:MM:SS", true) { text ->
                val ms = TimeFormat.parse(text)
                if (ms == null) core.message("Not a valid time") else core.setTargetMs(ms)
            }
        }
        b.header("Sound")
        for (p in Alarms.BUILTIN) {
            b.radio(p.second, cfg.source == 0 && cfg.builtinId == p.first) {
                cfg.source = 0
                cfg.builtinId = p.first
                changed()
                playPreview(cfg)
                menu.refresh()
            }
        }
        if (cfg.source == 1 || cfg.customFile != null) {
            val ok = MediaImport.exists(ctx, cfg.customFile)
            b.radio("My file: " + (cfg.customName ?: "audio") + if (ok) "" else "  (missing)", cfg.source == 1, null, 16f, ok) {
                cfg.source = 1
                changed()
                playPreview(cfg)
                menu.refresh()
            }
            if (!ok) b.note("That audio file is no longer available. Choose another sound.")
        }
        b.action("Choose audio file from device") { host.pickAudio(which) }
        b.slider("Volume", (cfg.volume * 100f).toInt(), 100, { p -> "$p%" }) { p ->
            cfg.volume = p / 100f
            changed()
        }
        b.slider("Ring for", cfg.loopSeconds.coerceAtMost(180), 180, { p -> if (p == 0) "Until stopped" else "$p s" }) { p ->
            cfg.loopSeconds = p
            changed()
        }
        b.toggle("Vibrate while ringing", cfg.vibrate) { v -> cfg.vibrate = v; changed() }
        b.action("Play sound") { playPreview(cfg) }
        b.action("Stop") { preview.stop() }
    }

    private fun playPreview(cfg: AlarmConfig) {
        preview.stop()
        preview.start(cfg, false, null)
    }

    private fun advancedPage() = Page("Advanced") { b ->
        b.nav("App icon ring colour", AppIcon.RINGS.firstOrNull { it.id == AppIcon.current(ctx) }?.name) { menu.push(iconPage()) }
        b.nav("About KALAM", null) { menu.push(aboutPage()) }
        confirmReset = false
        b.action("Reset all settings", true) {
            if (!confirmReset) {
                confirmReset = true
                core.message("Tap again to reset every setting (numbers are kept)")
            } else {
                confirmReset = false
                core.resetAllSettings()
                menu.dismiss()
            }
        }
        b.divider()
        b.action("Close KALAM") { host.closeApp() }
        b.note("Close stops background timing and removes the notification, following your Persistence settings.")
    }

    private fun iconPage() = Page("App icon ring colour") { b ->
        val cur = AppIcon.current(ctx)
        for (r in AppIcon.RINGS) {
            b.colorRow(if (r.id == cur) r.name + "  (current)" else r.name, r.color) {
                if (AppIcon.set(ctx, r.id)) core.message("Icon changed to " + r.name + ". Your launcher may take a few seconds to show it.")
                else core.message("This launcher did not allow the icon to change")
                menu.refresh()
            }
        }
        b.note("The home-screen icon is a hollow ring. Android can only switch between prepared colours, so pick one of these. If your phone shows a solid background behind the ring, turn off Themed icons in your home-screen settings.")
    }

    private fun aboutPage() = Page("About KALAM") { b ->
        b.note("KALAM 1.0.0\nA gesture-driven precision timer. It works entirely offline, needs no account, and collects no data.")
        b.note("Gestures: tap = pause/resume, double tap = reset numbers, triple tap = restore Master Layout, long-press a number or the background = menus, scroll over a number = change it, hold then drag = move, horizontal pinch = scale everything, vertical pinch = scale one number, twist = rotate.")
        b.note("Fonts are bundled under the SIL Open Font License 1.1 and Apache 2.0 (full texts are included in the app package: assets/fonts/LICENSES.txt).")
    }
}
