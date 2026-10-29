package io.github.drtron1c.cloudphotos.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat
import io.github.drtron1c.cloudphotos.R
import io.github.drtron1c.cloudphotos.identity.CustomLauncher
import io.github.drtron1c.cloudphotos.ui.MainActivity

/** The app's name and icon on the home screen. */
object AppIdentity {

    /**
     * Switch the launcher / app-drawer name to one of the presets. Android only lets an app
     * pick between names declared up front, so free-text names go through [pinShortcut].
     */
    fun setLauncherName(context: Context, alias: String) {
        applyAliases(context, alias)
    }

    /** Which launcher entry should show (null = none, because your own app replaces it). */
    fun desiredAlias(context: Context, settings: AppSettings): String? =
        if (settings.hideOriginalIcon && CustomLauncher.isInstalled(context)) null else settings.launcherName

    /** Switches on the entry that should show. Always safe, even while the app is open. */
    fun showWanted(context: Context, settings: AppSettings) {
        val alias = desiredAlias(context, settings) ?: return
        setAlias(context, alias, true)
    }

    /** Brings every entry in line, including switching old ones off. */
    fun applyAll(context: Context, settings: AppSettings) {
        applyAliases(context, desiredAlias(context, settings))
    }

    /** Makes sure the original icon is there (e.g. before removing your own app). */
    fun showOriginalNow(context: Context, settings: AppSettings) {
        setAlias(context, settings.launcherName, true)
    }

    private fun setAlias(context: Context, alias: String, on: Boolean) {
        val pm = context.packageManager
        val component = ComponentName(context.packageName, "${context.packageName}.alias.$alias")
        val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(component) != want) pm.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP)
    }

    /** Enables exactly the [alias] launcher entry (none when null). */
    private fun applyAliases(context: Context, alias: String?) {
        val pm = context.packageManager
        for ((a, _) in AppSettings.LAUNCHER_NAMES) {
            val component = ComponentName(context.packageName, "${context.packageName}.alias.$a")
            val want = if (a == alias) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(component) != want) {
                pm.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP)
            }
        }
    }

    fun canPinShortcuts(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Adds a home-screen shortcut with any name the user typed and the chosen icon colour. */
    fun pinShortcut(context: Context, name: String, color: Int): Boolean = pinShortcut(context, name, iconBitmap(context, color))

    /** Same, with any adaptive-icon picture (432×432, see IconArt). */
    fun pinShortcut(context: Context, name: String, icon: Bitmap): Boolean {
        val label = name.trim().ifEmpty { "Cloud Photos" }.take(30)
        val shortcut = ShortcutInfoCompat.Builder(context, "custom-" + label.hashCode())
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon))
            .setIntent(Intent(Intent.ACTION_MAIN).setClass(context, MainActivity::class.java))
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /** Adaptive-icon bitmap: white cloud on a gradient of [color]. */
    fun iconBitmap(context: Context, color: Int): Bitmap {
        val size = 432
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val light = ColorUtils.blendARGB(color, 0xFFFFFFFF.toInt(), 0.35f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, size.toFloat(), light, color, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.let {
            it.setBounds(0, 0, size, size)
            it.draw(canvas)
        }
        return bmp
    }
}
