package io.github.drtron1c.cloudphotos.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The strip of frames above a video's (or GIF's) progress bar, like iPhone: one picture per slot,
 * each the exact frame at that point, shaped like the video and as many as fit across. Drag along
 * it to go through the video frame by frame. Frames are made on the device and kept while the app
 * runs, so showing the controls again is instant.
 */
@Composable
fun FrameStrip(
    source: FrameSource,
    durationMs: Long,
    positionMs: Long,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(48.dp)
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
        val heightPx = constraints.maxHeight.coerceAtLeast(1)
        val widthPx = constraints.maxWidth.coerceAtLeast(1)
        // As many frames as fit across when each keeps the video's shape.
        val aspect = source.aspect.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.3f, 3.5f) ?: (16f / 9f)
        val gapPx = with(LocalDensity.current) { 1.dp.roundToPx() }
        val n = ceil(widthPx / (heightPx * aspect + gapPx)).toInt().coerceIn(2, 60)
        val times = remember(n, durationMs) { List(n) { (durationMs * (it + 0.5) / n).toLong() } }
        val pics = remember(source.key, n, durationMs) {
            mutableStateListOf<Bitmap?>().apply { for (t in times) add(FrameCache.get(source.key, t, heightPx)) }
        }
        LaunchedEffect(source.key, n, durationMs) {
            if (durationMs <= 0) return@LaunchedEffect
            val missing = times.indices.filter { pics.getOrNull(it) == null }
            if (missing.isEmpty()) return@LaunchedEffect
            source.pictures(missing.map { times[it] }, heightPx) { j, b ->
                val i = missing[j]
                FrameCache.put(source.key, times[i], heightPx, b)
                withContext(Dispatchers.Main) { if (i < pics.size) pics[i] = b }
            }
        }
        // A hairline between frames so each one reads as its own picture.
        Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            for (i in 0 until n) {
                val b = pics.getOrNull(i)
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black.copy(alpha = 0.3f))) {
                    if (b != null) {
                        Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight())
                    }
                }
            }
        }
        // Where you are.
        val x = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) * widthPx else 0f
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt() - 3.dp.roundToPx(), 0) }
                .width(6.dp)
                .fillMaxHeight()
                .border(2.dp, Color.White, RoundedCornerShape(3.dp)),
        )
    }
}

/** Not a real file? (Offline copies are plain files.) */
fun Uri.localFile(): File? = if (scheme == "file") path?.let(::File) else null
