package io.github.drtron1c.cloudphotos.photos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A lazily-loaded, position-addressed view of one CloudKit album.
 *
 * iCloud ranks items newest-first (rank 0 = most recent), and data is stored by rank.
 * The public index is the *display* position: oldest first like the iPhone grid
 * (newest at the bottom) unless [newestOnTop]. Cells ask for their index and the list
 * fetches the surrounding run of items on demand, so huge libraries scroll and scrub
 * without loading everything.
 */
class AssetList(
    override val key: String,
    override val spec: AlbumSpec,
    private val api: PhotosApi,
    private val scope: CoroutineScope,
    private val newestOnTop: Boolean,
    private val onError: (Throwable) -> Unit,
) : MediaList {
    override var count by mutableIntStateOf(-1)
        private set
    override var refreshing by mutableStateOf(false)
        private set

    private val items = mutableStateMapOf<Int, Asset>() // keyed by rank
    private val loadedAt = HashMap<Int, Long>()
    private val inFlight = HashSet<Int>()
    private var generation = 0
    private var countJob: Job? = null

    private fun rankFor(index: Int, c: Int = count) = if (newestOnTop) index else c - 1 - index
    private fun indexFor(rank: Int, c: Int = count) = if (newestOnTop) rank else c - 1 - rank

    /** Display position for iCloud rank [rank]. */
    fun positionOfRank(rank: Int): Int = indexFor(rank)

    /** Item at display position [index]. */
    override operator fun get(index: Int): Asset? {
        val c = count
        return if (c <= 0 || index < 0 || index >= c) null else items[rankFor(index, c)]
    }

    private fun rankOf(assetId: String): Int = items.entries.firstOrNull { it.value.id == assetId }?.key ?: -1

    /** Display position of an asset, or -1 if it isn't loaded. */
    override fun indexOf(assetId: String): Int {
        val r = rankOf(assetId)
        return if (r < 0) -1 else indexFor(r)
    }

    override fun start() {
        if (count >= 0 || countJob?.isActive == true) return
        countJob = scope.launch {
            try {
                count = api.count(spec)
            } catch (e: Throwable) {
                if (count < 0) count = 0
                onError(e)
            }
        }
    }

    override fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                val gen = ++generation
                val newCount = api.count(spec)
                if (gen == generation) {
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

    /** Cheap check (one count query); reloads only if the album changed size. */
    override fun checkForChanges() {
        if (count < 0 || refreshing) return
        scope.launch {
            try {
                val n = api.count(spec)
                if (n != count) refresh()
            } catch (_: Throwable) {
            }
        }
    }

    /** Make sure display position [index] (and its neighbours) are loaded with fresh download links. */
    override fun ensure(index: Int) {
        val c = count
        if (c <= 0 || index < 0 || index >= c) return
        ensureRank(rankFor(index, c))
    }

    private fun ensureRank(rank: Int) {
        val c = count
        if (rank < 0 || rank >= c) return
        val now = System.currentTimeMillis()
        val have = items[rank]
        val stamp = loadedAt[rank] ?: 0L
        if (have != null && now - stamp < STALE_MS) return
        if (inFlight.contains(rank)) return

        // Start a little "newer" than the requested cell so scrolling either way finds data ready.
        var start = rank
        while (start > 0 && rank - start < LOOK_BEHIND && !isFresh(start - 1, now) && !inFlight.contains(start - 1)) start--
        val end = minOf(c, start + PAGE)
        for (i in start until end) inFlight.add(i)
        val gen = generation
        scope.launch {
            try {
                val page = api.page(spec, start, PAGE)
                if (gen == generation) {
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
    override fun update(asset: Asset) {
        val r = rankOf(asset.id)
        if (r >= 0) items[r] = asset
    }

    /** Drop items that left this album (deleted, hidden, recovered…) and close the gaps. */
    override fun removeAll(ids: Set<String>) {
        if (ids.isEmpty()) return
        val ranks = items.entries.filter { it.value.id in ids }.map { it.key }.sorted()
        if (ranks.isEmpty()) {
            refresh(); return
        }
        val snapshot = items.toMap()
        val stamps = HashMap(loadedAt)
        items.clear()
        loadedAt.clear()
        for ((r, asset) in snapshot) {
            if (asset.id in ids) continue
            val shift = ranks.count { it < r }
            items[r - shift] = asset
            stamps[r]?.let { loadedAt[r - shift] = it }
        }
        count = (count - ranks.size).coerceAtLeast(0)
    }

    companion object {
        /** CloudKit answers at most 200 records per query; each photo is 2 (asset + master). */
        const val PAGE = 100
        const val LOOK_BEHIND = 40
        const val STALE_MS = 40 * 60 * 1000L
    }
}
