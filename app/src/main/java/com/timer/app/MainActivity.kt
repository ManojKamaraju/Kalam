package com.timer.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import android.app.NotificationManager

class MainActivity : Activity(), ViewHost, MenuHost, CoreListener {
    private lateinit var core: Core
    private lateinit var root: FrameLayout
    private var view: TimerView? = null
    private var layer: MenuLayer? = null
    private var menus: Menus? = null
    private var userAskedNotifications = false

    companion object {
        private const val REQ_IMAGE = 101
        private const val REQ_AUDIO = 200 // + slot
        private const val REQ_NOTIF = 300
    }

    override val timerView: TimerView
        get() = view!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        core = applicationContext.core()
        core.listeners.add(this)

        window.setDecorFitsSystemWindows(false)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        applyOrientationPolicy()

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val v = TimerView(this, core, this)
        view = v
        root.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        core.setVisible(true)
        core.onOpen()
        root.post { checkMissingAlarmSound() }
    }

    override fun onStop() {
        closeMenu()
        core.onActivityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        core.listeners.remove(this)
        view?.release()
        core.onActivityDestroyed(isFinishing)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val c = window.insetsController ?: return
        c.hide(WindowInsets.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    @Deprecated("Back is handled by the menu first, otherwise the app moves to the background (never loses state)")
    override fun onBackPressed() {
        val l = layer
        if (l != null) {
            l.pop()
        } else {
            moveTaskToBack(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tapping the notification brings the existing task forward; nothing to reset.
    }

    // ------------------------------------------------------------------ menus

    override fun openElementMenu(comp: Int, x: Float, y: Float) {
        openMenu(x, y) { m -> m.elementMenu(comp) }
    }

    override fun openBackgroundMenu(x: Float, y: Float) {
        openMenu(x, y) { m -> m.hubPage() }
    }

    private fun openMenu(x: Float, y: Float, page: (Menus) -> Page) {
        closeMenu()
        lateinit var m: Menus
        val l = MenuLayer(this) { closeMenu() }
        m = Menus(this, core, this, l)
        l.onDismissed = { m.stopPreview() }
        menus = m
        layer = l
        root.addView(l, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        l.open(page(m), x, y)
    }

    private fun closeMenu() {
        val l = layer ?: return
        layer = null
        menus?.stopPreview()
        menus = null
        root.removeView(l)
    }

    private fun refreshMenu() {
        layer?.refresh()
    }

    // ------------------------------------------------------------------ MenuHost

    override fun pickImage() {
        try {
            startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES), REQ_IMAGE)
        } catch (e: Throwable) {
            try {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                startActivityForResult(i, REQ_IMAGE)
            } catch (e2: Throwable) {
                core.message("No image picker available")
            }
        }
    }

    override fun pickAudio(slot: Int) {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
            startActivityForResult(i, REQ_AUDIO + slot)
        } catch (e: Throwable) {
            core.message("No file picker available")
        }
    }

    override fun closeApp() {
        core.explicitClose()
        finishAndRemoveTask()
    }

    override fun notificationsAllowed(): Boolean {
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val enabled = try {
            getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: granted
        } catch (e: Throwable) {
            granted
        }
        return granted && enabled
    }

    override fun requestNotifications() {
        userAskedNotifications = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (e: Throwable) {
        }
    }

    override fun applyOrientationPolicy() {
        requestedOrientation = if (core.model.prefs.followOrientation)
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR else ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!ok && userAskedNotifications) {
                core.message("Allow notifications in system settings to see the timer in the shade")
                openNotificationSettings()
            }
            userAskedNotifications = false
            refreshMenu()
        }
    }

    @Deprecated("Result of the system pickers")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        if (requestCode == REQ_IMAGE) {
            AppModel.io.execute {
                val name = MediaImport.importWallpaper(this, uri)
                runOnUiThread {
                    if (name == null) {
                        core.message("That image could not be used (unsupported or damaged file)")
                    } else {
                        val bg = core.model.background
                        bg.wallpaperFile = name
                        bg.type = 3
                        core.model.markDirty(AppModel.D_BACKGROUND)
                        refreshMenu()
                    }
                }
            }
        } else if (requestCode == REQ_AUDIO || requestCode == REQ_AUDIO + 1) {
            val slot = requestCode - REQ_AUDIO
            AppModel.io.execute {
                val res = MediaImport.importAudio(this, uri, slot)
                runOnUiThread {
                    if (res == null) {
                        core.message("That audio file could not be used")
                    } else {
                        val cfg = if (slot == 0) core.model.alarmFinish else core.model.alarmTarget
                        cfg.source = 1
                        cfg.customFile = res.first
                        cfg.customName = res.second
                        core.model.markDirty(AppModel.D_ALARM)
                        refreshMenu()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ CoreListener

    override fun onMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onTimerStarted() {
        val prefs = core.model.prefs
        if (prefs.runInBackground && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val meta = getSharedPreferences("meta", MODE_PRIVATE)
            if (!meta.getBoolean("askedNotifications", false)) {
                meta.edit().putBoolean("askedNotifications", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }
    }

    override fun onClosed() {
        if (!isFinishing) finishAndRemoveTask()
    }

    /** If a selected custom alarm file has disappeared, ask the user for a replacement instead of silently failing. */
    private fun checkMissingAlarmSound() {
        if (layer != null) return
        val m = core.model
        for (which in 0..1) {
            val cfg = if (which == 0) m.alarmFinish else m.alarmTarget
            if (cfg.enabled && cfg.source == 1 && !MediaImport.exists(this, cfg.customFile)) {
                core.message("Your alarm sound is missing. Please choose another.")
                val cx = root.width / 2f
                val cy = root.height / 2f
                openMenu(cx, cy) { mm -> mm.alarmPage(which) }
                return
            }
        }
    }
}
