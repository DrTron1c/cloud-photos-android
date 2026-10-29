package io.github.drtron1c.cloudphotos.ui

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Hardware-keyboard shortcuts. Each screen registers a handler at its depth in the navigation
 * stack; the deepest (top-most) visible screen gets keys first, then the app-wide ones run.
 */
class KeyRouter {
    private class Entry(val level: Int, val handler: (KeyEvent) -> Boolean)
    private val entries = ArrayList<Entry>()

    fun add(level: Int, handler: (KeyEvent) -> Boolean): Any = Entry(level, handler).also { entries += it }
    fun remove(token: Any) { entries.remove(token) }

    fun dispatch(e: KeyEvent): Boolean =
        entries.sortedByDescending { it.level }.any { it.handler(e) }

    /** Unit tests: routing without a real key. */
    internal fun dispatchForTest(): Boolean =
        entries.sortedByDescending { it.level }.any { it.handler(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)) }
}

/** Registers [handler] while this composable is shown (and [enabled]). */
@Composable
fun KeyShortcuts(vm: MainViewModel, level: Int, enabled: Boolean = true, handler: (KeyEvent) -> Boolean) {
    val current by rememberUpdatedState(handler)
    DisposableEffect(enabled, level) {
        val token = if (enabled) vm.keys.add(level) { current(it) } else null
        onDispose { token?.let { vm.keys.remove(it) } }
    }
}

val KeyEvent.ctrl get() = isCtrlPressed || isMetaPressed
val KeyEvent.plain get() = !isCtrlPressed && !isMetaPressed && !isAltPressed

/** One line of the shortcut list (shown in Settings and in Android's Meta+/ helper). */
data class Shortcut(val label: String, val keys: String, val keyCode: Int, val modifiers: Int = 0)

val SHORTCUT_GROUPS: List<Pair<String, List<Shortcut>>> = listOf(
    "Anywhere" to listOf(
        Shortcut("Search", "Ctrl+F or /", KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON),
        Shortcut("Library", "Ctrl+1", KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON),
        Shortcut("Albums", "Ctrl+2", KeyEvent.KEYCODE_2, KeyEvent.META_CTRL_ON),
        Shortcut("Search tab", "Ctrl+3", KeyEvent.KEYCODE_3, KeyEvent.META_CTRL_ON),
        Shortcut("Memories", "Ctrl+M", KeyEvent.KEYCODE_M, KeyEvent.META_CTRL_ON),
        Shortcut("Settings", "Ctrl+,", KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON),
        Shortcut("Upload photos", "Ctrl+U", KeyEvent.KEYCODE_U, KeyEvent.META_CTRL_ON),
        Shortcut("Refresh", "Ctrl+R or F5", KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON),
        Shortcut("Back", "Esc or Alt+←", KeyEvent.KEYCODE_ESCAPE),
        Shortcut("Keyboard shortcuts", "Ctrl+/", KeyEvent.KEYCODE_SLASH, KeyEvent.META_CTRL_ON),
    ),
    "Photo grid" to listOf(
        Shortcut("Select all", "Ctrl+A", KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON),
        Shortcut("Bigger / smaller thumbnails", "Ctrl+= / Ctrl+-", KeyEvent.KEYCODE_EQUALS, KeyEvent.META_CTRL_ON),
        Shortcut("Delete selected", "Delete", KeyEvent.KEYCODE_FORWARD_DEL),
        Shortcut("Stop selecting", "Esc", KeyEvent.KEYCODE_ESCAPE),
    ),
    "Photo viewer" to listOf(
        Shortcut("Previous / next", "← / →", KeyEvent.KEYCODE_DPAD_RIGHT),
        Shortcut("Play / pause video", "Space", KeyEvent.KEYCODE_SPACE),
        Shortcut("Favourite", "F or .", KeyEvent.KEYCODE_PERIOD),
        Shortcut("Info", "I", KeyEvent.KEYCODE_I),
        Shortcut("Edit", "E", KeyEvent.KEYCODE_E),
        Shortcut("Text in photo", "T", KeyEvent.KEYCODE_T),
        Shortcut("Copy photo", "Ctrl+C", KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
        Shortcut("Save to device", "Ctrl+S", KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON),
        Shortcut("Share", "Ctrl+Shift+S", KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON),
        Shortcut("Delete", "Delete or Backspace", KeyEvent.KEYCODE_DEL),
        Shortcut("Slideshow", "Ctrl+Enter", KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON),
        Shortcut("Picture-in-picture (videos)", "P", KeyEvent.KEYCODE_P),
        Shortcut("Close", "Esc", KeyEvent.KEYCODE_ESCAPE),
    ),
    "Slideshow & memories" to listOf(
        Shortcut("Pause / play", "Space", KeyEvent.KEYCODE_SPACE),
        Shortcut("Previous / next", "← / →", KeyEvent.KEYCODE_DPAD_RIGHT),
        Shortcut("Close", "Esc", KeyEvent.KEYCODE_ESCAPE),
    ),
)

fun systemShortcutGroups(): List<KeyboardShortcutGroup> = SHORTCUT_GROUPS.map { (title, list) ->
    KeyboardShortcutGroup(title, list.map { KeyboardShortcutInfo(it.label, it.keyCode, it.modifiers) })
}

@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keyboard Shortcuts") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                for ((title, list) in SHORTCUT_GROUPS) {
                    Text(title, color = p.label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    for (s in list) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(s.label, color = p.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Text(s.keys, color = p.secondaryLabel, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
