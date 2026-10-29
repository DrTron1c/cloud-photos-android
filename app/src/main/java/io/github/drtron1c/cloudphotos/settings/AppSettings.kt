package io.github.drtron1c.cloudphotos.settings

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Preference keys, shared with background workers and the widget. */
object Keys {
    const val GRID = "grid_level"
    const val LOCK_APP = "lock_app"
    const val LOCK_PRIVATE = "lock_private_albums"
    const val ZONE = "zone"
    const val THEME = "theme_mode"
    const val ACCENT = "accent"
    const val NEWEST_TOP = "newest_on_top"
    const val ASPECT_GRID = "aspect_grid"
    const val SHOW_DURATIONS = "show_durations"
    const val SHOW_FAV_BADGE = "show_fav_badge"
    const val START_TAB = "start_tab"
    const val LIBRARY_VIEW = "library_view"
    const val AUTOPLAY = "autoplay_videos"
    const val LOOP = "loop_videos"
    const val MUTED = "start_muted"
    const val LIVE_AUTOPLAY = "live_autoplay"
    const val SLIDE_SECONDS = "slideshow_seconds"
    const val SLIDE_KEN_BURNS = "slideshow_ken_burns"
    const val SLIDE_SHUFFLE = "slideshow_shuffle"
    const val WIDGET_MODE = "widget_mode"
    const val DNS = "dns_provider"
    const val HIDE_RECENTS = "hide_in_recents"
    const val RECENTLY_VIEWED = "recently_viewed_enabled"
    const val RECENTLY_VIEWED_IDS = "recently_viewed_ids"
    const val AUTO_UPLOAD = "auto_upload"
    const val AUTO_UPLOAD_WIFI = "auto_upload_wifi"
    const val AUTO_UPLOAD_SCREENSHOTS = "auto_upload_screenshots"
    const val AUTO_UPLOAD_SINCE = "auto_upload_since"
    const val AUTO_UPLOAD_STATUS = "auto_upload_status"
    const val OFFLINE_ALBUMS = "offline_albums"
    const val OFFLINE_VIDEOS = "offline_videos"
    const val OFFLINE_STATUS = "offline_status"
    const val LAUNCHER_NAME = "launcher_name"
    const val TAB_LABELS = "tab_labels"
    const val HAPTICS = "haptics"
    const val CONFIRM_DELETE = "confirm_delete"
    const val LIBRARY_FILTER = "library_filter"
    const val CUSTOM_NAME = "custom_name"
    const val HIDE_ORIGINAL_ICON = "hide_original_icon"
    const val COMPANION_STATUS = "companion_status"
}

/**
 * Observable app settings: reading one in a composable recomposes when it changes,
 * and every change is written straight to SharedPreferences.
 */
class AppSettings(private val prefs: SharedPreferences) {

    private inner class Pref<T>(
        private val key: String,
        default: T,
        read: SharedPreferences.(String, T) -> T,
        private val write: SharedPreferences.Editor.(String, T) -> Unit,
    ) : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(prefs.read(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            val editor = prefs.edit()
            write(editor, key, value)
            editor.apply()
        }
    }

    private fun bool(key: String, def: Boolean): ReadWriteProperty<Any?, Boolean> =
        Pref(key, def, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

    private fun int(key: String, def: Int): ReadWriteProperty<Any?, Int> =
        Pref(key, def, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })

    private fun str(key: String, def: String): ReadWriteProperty<Any?, String> =
        Pref(key, def, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })

    var gridLevel by int(Keys.GRID, 1)
    var lockApp by bool(Keys.LOCK_APP, false)
    var lockPrivateAlbums by bool(Keys.LOCK_PRIVATE, true)
    var zone by str(Keys.ZONE, "PrimarySync")

    /** "system", "light" or "dark". */
    var themeMode by str(Keys.THEME, "system")
    var accent by int(Keys.ACCENT, 0)
    var newestOnTop by bool(Keys.NEWEST_TOP, false)
    var aspectGrid by bool(Keys.ASPECT_GRID, false)
    var showDurations by bool(Keys.SHOW_DURATIONS, true)
    var showFavouriteBadges by bool(Keys.SHOW_FAV_BADGE, true)
    var tabLabels by bool(Keys.TAB_LABELS, true)
    var haptics by bool(Keys.HAPTICS, true)
    var confirmDelete by bool(Keys.CONFIRM_DELETE, true)

    /** "library", "albums" or "search". */
    var startTab by str(Keys.START_TAB, "library")
    /** "all", "days", "months" or "years". */
    var libraryView by str(Keys.LIBRARY_VIEW, "all")
    /** "all", "favourites", "edited", "photos" or "videos". */
    var libraryFilter by str(Keys.LIBRARY_FILTER, "all")

    var autoplayVideos by bool(Keys.AUTOPLAY, true)
    var loopVideos by bool(Keys.LOOP, false)
    var startMuted by bool(Keys.MUTED, false)
    var livePhotoAutoplay by bool(Keys.LIVE_AUTOPLAY, true)

    var slideshowSeconds by int(Keys.SLIDE_SECONDS, 4)
    var slideshowKenBurns by bool(Keys.SLIDE_KEN_BURNS, true)
    var slideshowShuffle by bool(Keys.SLIDE_SHUFFLE, false)

    /** "random", "onthisday" or "favourites". */
    var widgetMode by str(Keys.WIDGET_MODE, "onthisday")

    /** "cloudflare", "quad9", "google" or "system". */
    var dnsProvider by str(Keys.DNS, "cloudflare")
    var hideInRecents by bool(Keys.HIDE_RECENTS, true)
    var recentlyViewedEnabled by bool(Keys.RECENTLY_VIEWED, false)

    var autoUpload by bool(Keys.AUTO_UPLOAD, false)
    var autoUploadWifiOnly by bool(Keys.AUTO_UPLOAD_WIFI, true)
    var autoUploadScreenshots by bool(Keys.AUTO_UPLOAD_SCREENSHOTS, true)

    var offlineVideos by bool(Keys.OFFLINE_VIDEOS, true)

    /** Launcher label preset (activity-alias name). */
    var launcherName by str(Keys.LAUNCHER_NAME, "CloudPhotos")

    /** Your own name for the app (companion app, Recents, lock screen). Empty = "Cloud Photos". */
    var customName by str(Keys.CUSTOM_NAME, "")
    /** Hide the original icon while your companion app is installed. */
    var hideOriginalIcon by bool(Keys.HIDE_ORIGINAL_ICON, false)

    val appName: String get() = customName.trim().ifEmpty { "Cloud Photos" }

    fun raw(): SharedPreferences = prefs

    companion object {
        val ACCENTS = listOf(
            "Blue" to 0xFF007AFF,
            "Purple" to 0xFFAF52DE,
            "Pink" to 0xFFFF2D55,
            "Red" to 0xFFFF3B30,
            "Orange" to 0xFFFF9500,
            "Yellow" to 0xFFFFB800,
            "Green" to 0xFF34C759,
            "Teal" to 0xFF30B0C7,
            "Indigo" to 0xFF5856D6,
            "Graphite" to 0xFF8E8E93,
        )

        /** Launcher name presets → activity-alias class suffix (see AndroidManifest). */
        val LAUNCHER_NAMES = listOf(
            "CloudPhotos" to "Cloud Photos",
            "Photos" to "Photos",
            "Gallery" to "Gallery",
            "Pictures" to "Pictures",
            "Memories" to "Memories",
            "Album" to "Album",
            "Cloud" to "Cloud",
        )
    }
}
