package io.github.drtron1c.cloudphotos.photos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Anything the grid and viewer can show: position-addressed (0 = first cell), lazily loaded.
 * [key] identifies the list in the view model's registry (routes refer to lists by key).
 */
interface MediaList {
    val key: String
    val spec: AlbumSpec
    /** -1 while the size is still unknown. */
    val count: Int
    val refreshing: Boolean
    operator fun get(index: Int): Asset?
    fun ensure(index: Int)
    fun indexOf(assetId: String): Int
    fun update(asset: Asset)
    fun removeAll(ids: Set<String>)
    fun refresh()
    fun start() {}
    fun checkForChanges() {}
}

/** A list whose items are fetched all at once (Shared Albums, offline albums). */
class StaticList(
    override val key: String,
    override val spec: AlbumSpec,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit,
    /** Reload when older than this (download links expire). 0 = never. */
    private val staleMs: Long = 40 * 60 * 1000L,
    private val loader: suspend () -> List<Asset>,
) : MediaList {
    private val items = mutableStateListOf<Asset>()
    override var count by mutableIntStateOf(-1)
        private set
    override var refreshing by mutableStateOf(false)
        private set
    private var loadedAt = 0L
    private var job: Job? = null

    override fun start() {
        if (count < 0 && job?.isActive != true) load()
    }

    private fun load() {
        refreshing = true
        job = scope.launch {
            try {
                val list = loader()
                items.clear()
                items.addAll(list)
                count = list.size
                loadedAt = System.currentTimeMillis()
            } catch (e: Throwable) {
                if (count < 0) count = 0
                onError(e)
            } finally {
                refreshing = false
            }
        }
    }

    override fun get(index: Int): Asset? = items.getOrNull(index)

    override fun ensure(index: Int) {
        if (count >= 0 && staleMs > 0 && System.currentTimeMillis() - loadedAt > staleMs && job?.isActive != true) load()
    }

    override fun indexOf(assetId: String): Int = items.indexOfFirst { it.id == assetId }

    override fun update(asset: Asset) {
        val i = indexOf(asset.id)
        if (i >= 0) items[i] = asset
    }

    override fun removeAll(ids: Set<String>) {
        if (items.removeAll { it.id in ids }) count = items.size
    }

    override fun refresh() {
        if (job?.isActive != true) load()
    }
}

/**
 * A filtered view over another list (search results, Favourites/Videos filters, Duplicates,
 * Recently Edited / Viewed). Holds the base positions plus asset ids so it stays correct
 * even when the base list shifts after edits.
 */
class SubsetList(
    override val key: String,
    override val spec: AlbumSpec,
    private val base: MediaList,
    positions: List<Int>,
    ids: List<String>,
    /** Called when a photo isn't where it was expected (the library changed since the index was made). */
    private val onMoved: () -> Unit = {},
) : MediaList {
    private val pos = positions.toMutableList()
    private val idList = ids.toMutableList()
    private var version by mutableIntStateOf(0)
    private var reported = false

    override val count: Int get() = if (version >= 0) idList.size else 0
    override val refreshing: Boolean get() = base.refreshing

    override fun get(index: Int): Asset? {
        if (version < 0 || index !in idList.indices) return null
        val id = idList[index]
        val a = base[pos[index]]
        if (a != null && a.id == id) return a
        val j = base.indexOf(id)
        if (j >= 0) {
            pos[index] = j
            return base[j]?.takeIf { it.id == id }
        }
        if (a != null) {
            // Something else is in its place: never show the neighbour. Load around it and let the
            // owner rebuild its index.
            if (!reported) { reported = true; onMoved() }
            val c = base.count
            for (d in intArrayOf(-150, 150, -300, 300)) { val q = pos[index] + d; if (q in 0 until c) base.ensure(q) }
        }
        return null
    }

    override fun ensure(index: Int) {
        if (index in pos.indices) base.ensure(pos[index])
    }

    override fun indexOf(assetId: String): Int = idList.indexOf(assetId)

    override fun update(asset: Asset) {
        version++
    }

    override fun removeAll(ids: Set<String>) {
        var changed = false
        for (i in idList.indices.reversed()) {
            if (idList[i] in ids) {
                idList.removeAt(i); pos.removeAt(i); changed = true
            }
        }
        if (changed) version++
    }

    override fun refresh() = base.refresh()
}
