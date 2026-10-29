package io.github.drtron1c.cloudphotos.ui

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.roundToInt

/**
 * The strip of frames above a video's progress bar (like iPhone): drag along it to go through the
 * video frame by frame. Frames are read on the device; streamed videos are read through the app's
 * own connection (encrypted DNS, VPN), never the system's.
 */
@Composable
fun FrameStrip(
    vm: MainViewModel,
    uri: Uri,
    sizeBytes: Long,
    durationMs: Long,
    positionMs: Long,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frames = remember(uri) { mutableStateListOf<Bitmap?>() }
    val count = 10
    LaunchedEffect(uri, durationMs) {
        if (durationMs <= 0) return@LaunchedEffect
        frames.clear()
        repeat(count) { frames += null }
        withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(HttpDataSource(vm.http, uri.toString(), sizeBytes))
                for (i in 0 until count) {
                    if (!isActive) break
                    val t = (durationMs * (i + 0.5) / count * 1000).toLong()
                    val b = runCatching { r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160) }.getOrNull()
                    withContext(Dispatchers.Main) { if (i < frames.size) frames[i] = b }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { r.release() }
            }
        }
    }
    DisposableEffect(uri) { onDispose { frames.clear() } }
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(onDragEnd = { end() }, onDragCancel = { end() }) { change, _ ->
                    change.consume()
                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                    scrub((f * durationMs).toLong())
                }
            }
            .pointerInput(durationMs) {
                detectTapGestures { pos ->
                    scrub(((pos.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()); end()
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            for (i in 0 until count) {
                val b = frames.getOrNull(i)
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black.copy(alpha = 0.3f))) {
                    if (b != null) {
                        Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight())
                    }
                }
            }
        }
        // Where you are.
        val x = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) * constraints.maxWidth else 0f
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt() - 3, 0) }
                .width(6.dp)
                .fillMaxHeight()
                .border(2.dp, Color.White, RoundedCornerShape(3.dp)),
        )
    }
}

/** Lets Android's frame reader read a streamed video through the app's own HTTP client. */
private class HttpDataSource(private val http: OkHttpClient, private val url: String, private val known: Long) : MediaDataSource() {
    private val block = 256 * 1024
    private val cache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 48
    }
    private var total = known

    override fun getSize(): Long {
        if (total > 0) return total
        runCatching {
            http.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { r ->
                total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
            }
        }
        return total
    }

    private fun blockAt(index: Long): ByteArray? {
        cache[index]?.let { return it }
        val from = index * block
        val to = from + block - 1
        return runCatching {
            http.newCall(Request.Builder().url(url).header("Range", "bytes=$from-$to").build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.bytes()
            }
        }.getOrNull()?.also { cache[index] = it }
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

    override fun close() { cache.clear() }
}

/** Not a real file? (Offline copies are plain files.) */
fun Uri.localFile(): File? = if (scheme == "file") path?.let(::File) else null
