package io.github.drtron1c.cloudphotos.photos

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.drtron1c.cloudphotos.settings.AppSettings
import okhttp3.OkHttpClient
import java.io.File
import java.util.TreeSet

/**
 * A temporary, size-limited cache for videos and GIFs, so watching again, scrubbing back and the
 * frame strip don't download the same bytes twice. It lives in the app's private cache folder
 * (nothing else can read it, and Android may clear it when space runs low) and is emptied when the
 * app closes or after the time chosen in Settings. Entries are keyed by the file's checksum, since
 * iCloud's download links change every session.
 */
@OptIn(UnstableApi::class)
class MediaCache(
    context: Context,
    private val settings: AppSettings,
    private val http: OkHttpClient,
    dirName: String = "media",
    /** Tests: where downloads come from instead of the network. */
    private val upstream: DataSource.Factory? = null,
) {
    private val app = context.applicationContext
    private val dir = File(app.cacheDir, dirName)
    private val lock = Any()
    private val evictor = Evictor { settings.mediaCacheMb.coerceAtLeast(64) * 1024L * 1024L }
    @Volatile private var cache: SimpleCache? = null

    val enabled: Boolean get() = settings.mediaCacheMb > 0

    private fun open(): SimpleCache = cache ?: synchronized(lock) {
        cache ?: SimpleCache(dir, evictor, StandaloneDatabaseProvider(app)).also { cache = it }
    }

    /** Plain reads straight from iCloud (or the device), through the app's own connection. */
    fun direct(): DataSource.Factory = upstream ?: DefaultDataSource.Factory(app, OkHttpDataSource.Factory(http))

    /** Reads of streamed media: through the cache when it's on. */
    fun dataSourceFactory(): DataSource.Factory {
        val upstream = direct()
        if (!enabled) return upstream
        return CacheDataSource.Factory()
            .setCache(open())
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.buildUpon().clearQuery().build().toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** The factory for [uri]: local files are read directly, never copied into the cache. */
    fun factoryFor(uri: Uri): DataSource.Factory =
        if (uri.scheme == "http" || uri.scheme == "https") dataSourceFactory() else direct()

    /** A whole file (a GIF), from the cache when it's there. */
    fun readAll(uri: Uri, key: String?): ByteArray {
        if (uri.scheme == "file") return File(requireNotNull(uri.path)).readBytes()
        val spec = DataSpec.Builder().setUri(uri).setKey(key).build()
        return DataSourceInputStream(factoryFor(uri).createDataSource(), spec).use { it.readBytes() }
    }

    /** Lets Android's frame reader read a streamed video through the cache and the app's connection. */
    fun mediaDataSource(uri: Uri, key: String?, knownSize: Long): MediaDataSource = Reader(factoryFor(uri), uri, key, knownSize)

    /** Tests: let go of the folder. */
    fun release() = synchronized(lock) { cache?.release(); cache = null }

    /** Bytes kept now. */
    fun used(): Long = if (!dir.exists()) 0L else runCatching { open().cacheSpace }.getOrDefault(0L)

    /** Empty the cache. */
    fun clear() {
        if (!dir.exists()) return
        val c = runCatching { open() }.getOrNull() ?: return
        for (k in c.keys.toList()) runCatching { c.removeResource(k) }
    }

    /** Applies the settings: drops what's older than the chosen time and keeps within the size limit. */
    fun trim(appStarting: Boolean = false) {
        if (!dir.exists()) return
        if (!enabled || (appStarting && settings.mediaCacheKeep == "session")) { clear(); return }
        val days = when (settings.mediaCacheKeep) { "day" -> 1L; "month" -> 30L; "session" -> 3650L; else -> 7L }
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val c = runCatching { open() }.getOrNull() ?: return
        for (k in c.keys.toList()) {
            for (span in c.getCachedSpans(k)) if (span.lastTouchTimestamp < cutoff) runCatching { c.removeSpan(span) }
        }
        synchronized(c) { evictor.evict(c, 0) }
    }

    /** Least recently used goes first; the limit is read each time so a new setting applies at once. */
    private class Evictor(private val maxBytes: () -> Long) : CacheEvictor {
        private val lru = TreeSet<CacheSpan> { a, b ->
            if (a.lastTouchTimestamp == b.lastTouchTimestamp) a.compareTo(b) else a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        }
        private var size = 0L

        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() {}
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
        }
        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            lru.add(span)
            size += span.length
            evict(cache, 0)
        }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            if (lru.remove(span)) size -= span.length
        }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            onSpanRemoved(cache, oldSpan)
            onSpanAdded(cache, newSpan)
        }
        fun evict(cache: Cache, need: Long) {
            while (size + need > maxBytes() && lru.isNotEmpty()) cache.removeSpan(lru.first())
        }
    }

    /** Random-access reads in 256 KB blocks (a few kept in memory), each fetched through [factory]. */
    private class Reader(
        private val factory: DataSource.Factory,
        private val uri: Uri,
        private val key: String?,
        private val known: Long,
    ) : MediaDataSource() {
        private val block = 256 * 1024
        private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 48
        }
        private var total = known

        override fun getSize(): Long {
            if (total > 0) return total
            runCatching {
                val ds = factory.createDataSource()
                try { total = ds.open(DataSpec.Builder().setUri(uri).setKey(key).build()) } finally { ds.close() }
            }
            return total
        }

        private fun blockAt(index: Long): ByteArray? {
            blocks[index]?.let { return it }
            val from = index * block
            val len = getSize().let { if (it > 0) minOf(block.toLong(), it - from) else block.toLong() }
            if (len <= 0) return null
            return runCatching {
                val ds = factory.createDataSource()
                try {
                    ds.open(DataSpec.Builder().setUri(uri).setKey(key).setPosition(from).setLength(len).build())
                    val out = ByteArray(len.toInt())
                    var n = 0
                    while (n < out.size) {
                        val r = ds.read(out, n, out.size - n)
                        if (r == C.RESULT_END_OF_INPUT) break
                        n += r
                    }
                    if (n == out.size) out else out.copyOf(n)
                } finally {
                    ds.close()
                }
            }.getOrNull()?.also { blocks[index] = it }
        }

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            val len = getSize()
            if (len in 1..position) return -1
            var done = 0
            while (done < size) {
                val p = position + done
                if (len > 0 && p >= len) break
                val b = blockAt(p / block) ?: break
                val inBlock = (p % block).toInt()
                if (inBlock >= b.size) break
                val n = minOf(size - done, b.size - inBlock)
                System.arraycopy(b, inBlock, buffer, offset + done, n)
                done += n
            }
            return if (done == 0) -1 else done
        }

        override fun close() { blocks.clear() }
    }
}
