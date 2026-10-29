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
import io.github.drtron1c.cloudphotos.ui.MainActivity

/** The app's name and icon on the home screen. */
object AppIdentity {

    /**
     * Switch the launcher / app-drawer name to one of the presets. Android only lets an app
     * pick between names declared up front, so free-text names go through [pinShortcut].
     */
    fun setLauncherName(context: Context, alias: String) {
        val pm = context.packageManager
        for ((a, _) in AppSettings.LAUNCHER_NAMES) {
            val component = ComponentName(context.packageName, "${context.packageName}.alias.$a")
            pm.setComponentEnabledSetting(
                component,
                if (a == alias) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    fun canPinShortcuts(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Adds a home-screen shortcut with any name the user typed and the chosen icon colour. */
    fun pinShortcut(context: Context, name: String, color: Int): Boolean {
        val label = name.trim().ifEmpty { "Cloud Photos" }.take(30)
        val shortcut = ShortcutInfoCompat.Builder(context, "custom-" + label.hashCode())
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(iconBitmap(context, color)))
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
