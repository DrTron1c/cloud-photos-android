package io.github.drtron1c.cloudphotos.ui

import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import io.github.drtron1c.cloudphotos.identity.CustomLauncher
import io.github.drtron1c.cloudphotos.identity.IconArt

class MainActivity : FragmentActivity(), PipHost {
    companion object {
        /** Intent extra: what to open ("search", "favourites", "memories", "onthisday", "places", "upload", "vpn"). */
        const val EXTRA_OPEN = "io.github.drtron1c.cloudphotos.OPEN"
    }

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyRecentsPrivacy(vm.settings.hideInRecents)
        applyRefreshRate(vm.settings.highRefresh)
        vm.pipHost = java.lang.ref.WeakReference(this)
        handleLaunchIntent(intent)
        setContent {
            val s = vm.settings
            val dark = isDarkTheme(s.themeMode)
            LaunchedEffect(dark) {
                // Status/navigation bar icons follow the chosen theme, not just the system one.
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            LaunchedEffect(s.hideInRecents) { applyRecentsPrivacy(s.hideInRecents) }
            LaunchedEffect(s.customName, vm.identityVersion) { applyTaskIdentity(s.customName.trim()) }
            LaunchedEffect(s.highRefresh) { applyRefreshRate(s.highRefresh) }
            CloudPhotosTheme(themeMode = s.themeMode, accentIndex = s.accent) {
                CompositionLocalProvider(LocalGlassEnabled provides (s.liquidGlass && glassSupported())) { AppRoot(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    /** App shortcuts (long-press the icon), the On This Day notification and the Quick Settings tile. */
    private fun handleLaunchIntent(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_OPEN) ?: return
        intent.removeExtra(EXTRA_OPEN)
        vm.openFromOutside(target)
    }

    /** Smooth motion: ask for the screen's fastest refresh rate (e.g. 120 or 144 Hz) at the same resolution. */
    private fun applyRefreshRate(high: Boolean) {
        val d = display ?: return
        val current = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
        val lp = window.attributes
        lp.preferredDisplayModeId = if (high && best != null) best.modeId else 0
        window.attributes = lp
    }

    // ---------- hardware keyboard ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !vm.inPip) {
            val typing = getSystemService(InputMethodManager::class.java)?.isAcceptingText == true
            if (vm.handleKey(event, typing)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** The list Android shows for Meta+/ (or Ctrl+/ in some launchers). */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        data.addAll(systemShortcutGroups())
    }

    // ---------- picture-in-picture (videos) ----------

    private var pipParams: PictureInPictureParams? = null

    /** A video is playing ([w]×[h]): leaving the app shrinks it into a floating window. */
    override fun setPipCandidate(playing: Boolean, w: Int, h: Int) {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val params = if (playing) {
            val ratio = if (w > 0 && h > 0) Rational(w, h) else Rational(16, 9)
            val clamped = when {
                ratio.toFloat() > 2.39f -> Rational(239, 100)
                ratio.toFloat() < 1 / 2.39f -> Rational(100, 239)
                else -> ratio
            }
            PictureInPictureParams.Builder().setAspectRatio(clamped).apply {
                if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(true).setSeamlessResizeEnabled(true)
            }.build()
        } else null
        pipParams = params
        runCatching {
            setPictureInPictureParams(
                params ?: PictureInPictureParams.Builder().apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(false) }.build()
            )
        }
    }

    override fun enterPipNow() {
        val params = pipParams ?: return
        vm.expectingExternal = true
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters on its own (auto-enter); older versions need asking.
        if (Build.VERSION.SDK_INT < 31 && pipParams != null && !vm.expectingExternal) enterPipNow()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
        // While floating, leaving doesn't lock the app; back to full screen, normal rules again.
        vm.expectingExternal = isInPictureInPictureMode
    }

    /** Your own name and picture in the recent-apps list. */
    private fun applyTaskIdentity(name: String) {
        if (name.isEmpty()) return
        val icon = CustomLauncher.savedIcon(this)?.let {
            val inset = (IconArt.SIZE - IconArt.VISIBLE) / 2
            rounded(Bitmap.createBitmap(it, inset, inset, IconArt.VISIBLE, IconArt.VISIBLE))
        }
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(name, icon))
    }

    /** App-icon shape (rounded corners, like every other icon in Recents). */
    private fun rounded(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
        val r = src.width * 0.225f
        c.drawRoundRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), r, r, paint)
        return out
    }

    /** Keep photos out of the app-switcher thumbnail. */
    private fun applyRecentsPrivacy(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(!hide)
        } else if (hide) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onResume() {
        super.onResume()
        vm.onResumed()
    }

    override fun onStop() {
        super.onStop()
        // Closing the floating video window counts as leaving the app.
        if (vm.inPip) {
            vm.inPip = false
            vm.expectingExternal = false
        }
        if (!isChangingConfigurations) {
            vm.applyLauncherChanges()
            vm.onLeftApp()
            vm.onBackgrounded()
        }
    }

    override fun onDestroy() {
        // "Until the app closes": closing the app (not just rotating it) empties the video cache.
        if (isFinishing && !isChangingConfigurations && vm.settings.mediaCacheKeep == "session") vm.clearMediaCacheQuietly()
        super.onDestroy()
    }
}
