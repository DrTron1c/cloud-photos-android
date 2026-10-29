package io.github.drtron1c.cloudphotos.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.drtron1c.cloudphotos.R
import io.github.drtron1c.cloudphotos.ui.MainActivity

/** Long-press the app icon: Search, Favourites, Memories, Upload. */
object AppShortcuts {
    fun publish(context: Context) {
        fun item(id: String, label: String, icon: Int, rank: Int) = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_OPEN, id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            .setRank(rank)
            .build()
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    item("search", "Search", R.drawable.ic_shortcut_search, 0),
                    item("favourites", "Favourites", R.drawable.ic_shortcut_favourite, 1),
                    item("memories", "Memories", R.drawable.ic_shortcut_memories, 2),
                    item("upload", "Upload", R.drawable.ic_shortcut_upload, 3),
                ),
            )
        }
    }
}
