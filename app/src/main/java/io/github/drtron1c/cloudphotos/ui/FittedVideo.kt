package io.github.drtron1c.cloudphotos.ui

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A playing video, always shown whole: at rest it is fitted inside its space (black bars at the
 * sides or top and bottom, never cropped), whatever the shape of the video or the screen.
 * Pinch or double-tap zooms into the real pixels; pinching back out returns to the whole picture.
 *
 * The picture is laid out at exactly its fitted size, and the zoom library is told so
 * ([ZoomableContentLocation.scaledToFitAndCenterAligned]), so 1× is always the whole video. (Telling
 * it the raw pixel size made it enlarge videos with fewer pixels than the screen a second time.)
 * Used for GIFs too ([GifPlayer] draws onto the same kind of view).
 */
@OptIn(UnstableApi::class)
@Composable
internal fun FittedVideo(
    player: Player,
    zoom: ZoomableState,
    fallback: Size,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onShape: (Size) -> Unit = {},
    onTexture: (TextureView) -> Unit = {},
) {
    // The real shape comes from the decoder (stored sizes can be sideways for phone videos).
    // Nothing is drawn until it's known, so the video never shows stretched or zoomed.
    var frameSize by remember(player) { mutableStateOf<Size?>(null) }
    var rotation by remember(player) { mutableIntStateOf(0) }
    fun take(v: VideoSize) {
        if (v.width > 0 && v.height > 0) {
            frameSize = Size(v.width * v.pixelWidthHeightRatio, v.height.toFloat())
            rotation = ((v.unappliedRotationDegrees % 360) + 360) % 360
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = take(videoSize)
        }
        player.addListener(listener)
        take(player.videoSize)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (frameSize == null) { take(player.videoSize); delay(100) }
    }
    val sideways = rotation == 90 || rotation == 270
    val known = frameSize != null
    // The picture as it appears on screen (after any rotation).
    val shown = frameSize?.let { if (sideways) Size(it.height, it.width) else it }
        ?: fallback.takeIf { it.width > 0f && it.height > 0f } ?: Size(16f, 9f)
    val shape by rememberUpdatedState(onShape)
    LaunchedEffect(shown) { shape(shown) }
    LaunchedEffect(shown, known) {
        zoom.setContentLocation(ZoomableContentLocation.scaledToFitAndCenterAligned(shown))
        if (known) runCatching { zoom.resetZoom() }
    }
    Box(modifier.fillMaxSize().zoomable(zoom, onClick = { onTap() })) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Laid out exactly at its fitted size. Zooming stays sharp: the GPU draws the video's own
            // full-resolution frames under the zoom, it never enlarges a screen-sized copy.
            val fit = min(constraints.maxWidth / shown.width, constraints.maxHeight / shown.height)
            val w = (shown.width * fit).roundToInt().coerceIn(1, constraints.maxWidth.coerceAtLeast(1))
            val h = (shown.height * fit).roundToInt().coerceIn(1, constraints.maxHeight.coerceAtLeast(1))
            // Only on old Android does the app turn sideways frames itself.
            val viewW = if (sideways) h else w
            val viewH = if (sideways) w else h
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it); onTexture(it) } },
                modifier = Modifier.align(Alignment.Center).layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints.fixed(viewW, viewH))
                    layout(w, h) {
                        placeable.placeWithLayer((w - viewW) / 2, (h - viewH) / 2) {
                            rotationZ = rotation.toFloat()
                            alpha = if (known) 1f else 0f
                        }
                    }
                },
            )
        }
    }
}
