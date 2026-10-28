package io.github.drtron1c.cloudphotos.photos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A lazily-loaded, position-addressed view of one album. Position 0 is the oldest item,
 * like the iPhone grid (newest at the bottom). Cells ask for their index and the list
 * fetches the surrounding run of items on demand, so huge libraries scroll and scrub
 * without loading everything.
 */
class AssetList(
    val spec: AlbumSpec,
    private val api: PhotosApi,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit,
) {
    var count by mutableIntStateOf(-1)
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set

    private val items = mutableStateMapOf<Int, Asset>()
    private val loadedAt = HashMap<Int, Long>()
    private val inFlight = HashSet<Int>()
    private val mutex = Mutex()
    private var generation = 0
    private var countJob: Job? = null

    operator fun get(index: Int): Asset? = items[index]

    fun indexOf(assetId: String): Int = items.entries.firstOrNull { it.value.id == assetId }?.key ?: -1

    fun start() {
        if (count >= 0 || countJob?.isActive == true) return
        loadCount()
    }

    private fun loadCount() {
        loading = true
        countJob = scope.launch {
            try {
                count = api.count(spec)
            } catch (e: Throwable) {
                if (count < 0) count = 0
                onError(e)
            } finally {
                loading = false
            }
        }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                val gen = ++generation
                val newCount = api.count(spec)
                mutex.withLock {
                    if (gen != generation) return@withLock
                    items.clear()
                    loadedAt.clear()
                    inFlight.clear()
                    count = newCount
                }
            } catch (e: Throwable) {
                onError(e)
            } finally {
                refreshing = false
            }
        }
    }

    /** Make sure [index] (and its neighbours) are loaded and their download links are fresh. */
    fun ensure(index: Int) {
        val c = count
        if (c <= 0 || index < 0 || index >= c) return
        val now = System.currentTimeMillis()
        val have = items[index]
        val stamp = loadedAt[index] ?: 0L
        if (have != null && now - stamp < STALE_MS) return
        if (inFlight.contains(index)) return

        // Start a little before the requested cell so scrolling either way finds data ready.
        var start = index
        while (start > 0 && index - start < LOOK_BEHIND && !isFresh(start - 1, now) && !inFlight.contains(start - 1)) start--
        val end = minOf(c, start + PAGE)
        for (i in start until end) inFlight.add(i)
        val gen = generation
        scope.launch {
            try {
                val page = api.page(spec, start, PAGE)
                mutex.withLock {
                    if (gen != generation) return@withLock
                    val t = System.currentTimeMillis()
                    page.forEachIndexed { i, asset ->
                        items[start + i] = asset
                        loadedAt[start + i] = t
                    }
                    if (page.isEmpty() && start < count) {
                        // The count was optimistic (items removed elsewhere); trim it.
                        count = start
                    }
                }
            } catch (e: Throwable) {
                onError(e)
            } finally {
                for (i in start until end) inFlight.remove(i)
            }
        }
    }

    private fun isFresh(i: Int, now: Long): Boolean {
        val s = loadedAt[i] ?: return false
        return items[i] != null && now - s < STALE_MS
    }

    /** Replace an item after an edit (favourite etc.). */
    fun update(asset: Asset) {
        val i = indexOf(asset.id)
        if (i >= 0) items[i] = asset
    }

    /** Drop items that left this album (deleted, hidden, recovered…) and close the gaps. */
    fun removeAll(ids: Set<String>) {
        if (ids.isEmpty()) return
        val positions = items.entries.filter { it.value.id in ids }.map { it.key }.sorted()
        if (positions.isEmpty()) {
            refresh(); return
        }
        val snapshot = items.toMap()
        val stamps = HashMap(loadedAt)
        items.clear()
        loadedAt.clear()
        for ((idx, asset) in snapshot) {
            if (asset.id in ids) continue
            val shift = positions.count { it < idx }
            items[idx - shift] = asset
            stamps[idx]?.let { loadedAt[idx - shift] = it }
        }
        count = (count - positions.size).coerceAtLeast(0)
    }

    companion object {
        const val PAGE = 120
        const val LOOK_BEHIND = 40
        const val STALE_MS = 40 * 60 * 1000L
    }
}
