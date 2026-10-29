package io.github.drtron1c.cloudphotos.photos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.drtron1c.cloudphotos.net.SecureFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Just enough about each library item to group, filter and search on the phone. */
data class IndexEntry(
    val id: String,
    val date: Long,
    val added: Long,
    val name: String,
    val video: Boolean,
    val favourite: Boolean,
    val edited: Boolean,
    val checksum: String,
    val size: Long,
    val lat: Double?,
    val lon: Double?,
) {
    fun toJson(): JSONArray = JSONArray().put(id).put(date).put(added).put(name).put(video).put(favourite)
        .put(edited).put(checksum).put(size).put(lat ?: JSONObject.NULL).put(lon ?: JSONObject.NULL)

    companion object {
        fun fromJson(a: JSONArray) = IndexEntry(
            a.optString(0), a.optLong(1), a.optLong(2), a.optString(3), a.optBoolean(4), a.optBoolean(5),
            a.optBoolean(6), a.optString(7), a.optLong(8),
            if (a.isNull(9)) null else a.optDouble(9), if (a.isNull(10)) null else a.optDouble(10),
        )

        fun of(a: Asset) = IndexEntry(
            a.id, a.date, a.addedDate, a.filename, a.isVideo, a.isFavorite, a.isEdited,
            a.original?.checksum ?: "", a.original?.size ?: 0L, a.latitude, a.longitude,
        )
    }
}

/**
 * The whole Library's dates / names / flags in iCloud rank order (index 0 = newest),
 * fetched in the background with a lightweight query and cached encrypted on the device.
 */
class LibraryIndex(
    private val api: PhotosApi,
    private val scope: CoroutineScope,
    private val dir: File,
) {
    var entries by mutableStateOf<List<IndexEntry>>(emptyList())
        private set
    var building by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    /** True once [entries] matches the library (or a cached copy is loaded). */
    var ready by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var zone = ""
    private var job: Job? = null

    private fun file(z: String) = SecureFile(File(dir, "index-" + z.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".bin"))

    /** Load the cached index for [z] (if any). */
    fun open(z: String) {
        if (z == zone && (ready || building)) return
        job?.cancel()
        zone = z
        entries = emptyList()
        ready = false
        building = false
        scope.launch {
            val cached = withContext(Dispatchers.IO) { readCached(dir, z) }
            if (zone == z && cached != null) {
                entries = cached
                ready = true
            }
        }
    }

    /** Bring the index up to date with a library of [count] items. */
    fun sync(count: Int) {
        if (count < 0 || building) return
        if (ready && entries.size == count) return
        val z = zone
        building = true
        error = null
        progress = 0f
        job = scope.launch {
            try {
                val cached = entries
                var updated: List<IndexEntry>? = null
                // Fast path: new photos arrived at the newest end.
                if (cached.isNotEmpty() && count > cached.size && count - cached.size <= 3000) {
                    val diff = count - cached.size
                    val fresh = ArrayList<IndexEntry>()
                    var start = 0
                    while (start <= diff) {
                        val page = api.indexPage(start)
                        if (page.isEmpty()) break
                        fresh.addAll(page.map { IndexEntry.of(it) })
                        start += page.size
                    }
                    if (fresh.size > diff && fresh[diff].id == cached[0].id) {
                        updated = fresh.take(diff) + cached
                    }
                }
                if (updated == null) {
                    val all = ArrayList<IndexEntry>(count)
                    var start = 0
                    while (start < count) {
                        val page = api.indexPage(start)
                        if (page.isEmpty()) break
                        all.addAll(page.map { IndexEntry.of(it) })
                        start += page.size
                        progress = (start.toFloat() / count).coerceIn(0f, 1f)
                    }
                    updated = all
                }
                if (zone == z) {
                    entries = updated
                    ready = true
                    save(z, updated)
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Couldn't organise your library."
            } finally {
                building = false
            }
        }
    }

    /** Forget everything (sign-out): stops any build and deletes the cached copies. */
    fun reset() {
        job?.cancel()
        job = null
        zone = ""
        entries = emptyList()
        building = false
        progress = 0f
        ready = false
        error = null
        dir.listFiles()?.forEach { it.delete() }
    }

    fun remove(ids: Set<String>) {
        if (ids.isEmpty() || entries.none { it.id in ids }) return
        entries = entries.filterNot { it.id in ids }
        persist()
    }

    fun update(asset: Asset) {
        val i = entries.indexOfFirst { it.id == asset.id }
        if (i < 0) return
        entries = entries.toMutableList().also { it[i] = IndexEntry.of(asset) }
        persist()
    }

    private fun persist() {
        val z = zone
        val snapshot = entries
        scope.launch { save(z, snapshot) }
    }

    private suspend fun save(z: String, list: List<IndexEntry>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        for (e in list) arr.put(e.toJson())
        runCatching { file(z).write(JSONObject().put("v", 1).put("entries", arr).toString()) }
    }

    companion object {
        /** Read a cached index without a running app (widget / background jobs). */
        fun readCached(dir: File, zone: String): List<IndexEntry>? {
            val text = SecureFile(File(dir, "index-" + zone.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".bin")).read() ?: return null
            return runCatching {
                val arr = JSONObject(text).getJSONArray("entries")
                List(arr.length()) { IndexEntry.fromJson(arr.getJSONArray(it)) }
            }.getOrNull()
        }
    }
}
