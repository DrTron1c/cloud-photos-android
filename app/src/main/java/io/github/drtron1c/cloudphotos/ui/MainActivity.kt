package io.github.drtron1c.cloudphotos.ui

import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import io.github.drtron1c.cloudphotos.identity.CustomLauncher
import io.github.drtron1c.cloudphotos.identity.IconArt

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyRecentsPrivacy(vm.settings.hideInRecents)
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
            CloudPhotosTheme(themeMode = s.themeMode, accentIndex = s.accent) { AppRoot(vm) }
        }
    }

    /** Your own name and picture in the recent-apps list. */
    private fun applyTaskIdentity(name: String) {
        if (name.isEmpty()) return
        val icon = CustomLauncher.savedIcon(this)?.let {
            val inset = (IconArt.SIZE - IconArt.VISIBLE) / 2
            Bitmap.createBitmap(it, inset, inset, IconArt.VISIBLE, IconArt.VISIBLE)
        }
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(name, icon))
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
        if (!isChangingConfigurations) {
            vm.applyLauncherChanges()
            vm.onBackgrounded()
        }
    }
}
