package io.github.drtron1c.cloudphotos.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import io.github.drtron1c.cloudphotos.photos.MediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** What the progress bar's frame strip and frame-by-frame buttons need from a video or a GIF. */
interface FrameSource {
    /** Identifies the picture (for keeping its strip frames). */
    val key: String
    /** Width ÷ height of the picture as shown. */
    val aspect: Float
    /** Where the frame [frames] away from the one showing at [positionMs] starts. */
    fun step(positionMs: Long, frames: Int, durationMs: Long): Long
    /** Make seeks land on the exact frame (used while scrubbing and stepping). */
    fun exact() {}
    /** The frames showing at [timesMs], about [px] tall, each handed over as soon as it's ready. */
    suspend fun pictures(timesMs: List<Long>, px: Int, onPicture: suspend (Int, Bitmap) -> Unit)
}

/** Strip frames already made, so showing the controls again doesn't decode them again. */
object FrameCache {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private fun k(source: String, t: Long, px: Int) = "$source@$t@$px"
    fun get(source: String, t: Long, px: Int): Bitmap? = cache.get(k(source, t, px))
    fun put(source: String, t: Long, px: Int, b: Bitmap) { cache.put(k(source, t, px), b) }
}

/** Scales [b] to about [px] tall (never up). */
internal fun Bitmap.scaledTo(px: Int): Bitmap {
    if (height <= px || height <= 0) return this
    val w = max(1, (width * px.toFloat() / height).roundToInt())
    return Bitmap.createScaledBitmap(this, w, px, true)
}

/** Order that fills a strip evenly as frames arrive: ends and middle first, then the gaps. */
internal fun fillOrder(n: Int): List<Int> {
    if (n <= 0) return emptyList()
    val out = ArrayList<Int>(n)
    val seen = BooleanArray(n)
    var step = Integer.highestOneBit(maxOf(1, n - 1)).coerceAtLeast(1)
    fun add(i: Int) { if (i in 0 until n && !seen[i]) { seen[i] = true; out += i } }
    add(0); add(n - 1)
    while (step >= 1) {
        var i = 0
        while (i < n) { add(i); i += step }
        step /= 2
    }
    for (i in 0 until n) add(i)
    return out
}

/** A video's frames: exact frames from Android's own frame reader, read through the cache. */
@OptIn(UnstableApi::class)
class VideoFrames(
    private val player: ExoPlayer,
    private val uri: Uri,
    private val cacheKey: String,
    private val sizeBytes: Long,
    private val cache: MediaCache,
) : FrameSource {
    override val key: String get() = "video:$cacheKey"

    override val aspect: Float
        get() {
            val v = player.videoSize
            return if (v.width > 0 && v.height > 0) v.width * v.pixelWidthHeightRatio / v.height else 16f / 9f
        }

    override fun step(positionMs: Long, frames: Int, durationMs: Long): Long {
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 30f
        // Aim into the middle of the target frame so rounding never lands on the old one.
        val target = positionMs + ((frames + if (frames > 0) 0.5f else -0.5f) * 1000f / fps).toLong()
        return target.coerceIn(0L, durationMs.coerceAtLeast(0L))
    }

    override fun exact() = player.setSeekParameters(SeekParameters.EXACT)

    override suspend fun pictures(timesMs: List<Long>, px: Int, onPicture: suspend (Int, Bitmap) -> Unit) = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(cache.mediaDataSource(uri, cacheKey, sizeBytes))
            val box = px * 2
            for (i in fillOrder(timesMs.size)) {
                ensureActive()
                // OPTION_CLOSEST: the exact frame at that moment, not the nearest keyframe (which made
                // neighbouring pictures repeat).
                val b = runCatching { r.getScaledFrameAtTime(timesMs[i] * 1000, MediaMetadataRetriever.OPTION_CLOSEST, box, box) }.getOrNull()
                    ?: runCatching { r.getScaledFrameAtTime(timesMs[i] * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, box, box) }.getOrNull()
                    ?: continue
                onPicture(i, b.scaledTo(px))
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { r.release() }
        }
    }
}
