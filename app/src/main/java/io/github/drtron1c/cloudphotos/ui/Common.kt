package io.github.drtron1c.cloudphotos.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.drtron1c.cloudphotos.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Fingerprint / face / PIN check. Lets the user through if the device has no screen lock at all. */
fun FragmentActivity.requireUnlock(title: String, onSuccess: () -> Unit) {
    val auths = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(this).canAuthenticate(auths) != BiometricManager.BIOMETRIC_SUCCESS) {
        onSuccess(); return
    }
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(auths)
            .build()
    )
}

@Composable
fun AppIcon(size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.23f))
            .background(Brush.verticalGradient(listOf(Color(0xFF5AC8FA), Color(0xFF0A6CFF))))
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
    }
}

/** Translucent capsule button that sits on top of photos, like iOS 17's Photos header. */
@Composable
fun Pill(
    text: String? = null,
    icon: ImageVector? = null,
    onDark: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val bg = if (onDark) Color.Black.copy(alpha = 0.38f) else p.card.copy(alpha = 0.9f)
    val fg = if (onDark) Color.White else p.accent
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(if (text != null) PaddingValues(horizontal = 14.dp, vertical = 7.dp) else PaddingValues(7.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, text, tint = fg, modifier = Modifier.size(20.dp))
        if (text != null) Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val zone: ZoneId get() = ZoneId.systemDefault()

fun formatDay(millis: Long, withYear: Boolean = true): String {
    if (millis <= 0) return ""
    val d = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        !withYear || d.year == today.year -> d.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))
        else -> d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
    }
}

fun formatMonth(millis: Long): String {
    if (millis <= 0) return ""
    return Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
}

fun formatShortDay(millis: Long): String {
    if (millis <= 0) return ""
    return Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
}

fun formatTime(millis: Long): String {
    if (millis <= 0) return ""
    return Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
}

fun formatFullDate(millis: Long): String {
    if (millis <= 0) return "Unknown date"
    return Instant.ofEpochMilli(millis).atZone(zone)
        .format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy • HH:mm", Locale.getDefault()))
}

fun formatDuration(seconds: Double?): String {
    if (seconds == null || seconds <= 0) return ""
    val s = seconds.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
