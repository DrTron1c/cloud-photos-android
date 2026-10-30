package io.github.drtron1c.cloudphotos.ui

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import io.github.drtron1c.cloudphotos.photos.Asset
import kotlinx.coroutines.delay

/** Trim a video: pick the start and end, preview it, save it as a new clip (the original stays as it is). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(vm: MainViewModel, asset: Asset, onClose: () -> Unit) {
    val context = LocalContext.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val video = asset.playbackVideo
    if (video == null) {
        LaunchedEffect(Unit) { vm.toast("This video can't be trimmed"); onClose() }
        return
    }
    val player = remember { newPlayer(context, vm.http, vm.mediaUri(video), cache = vm.mediaCache, cacheKey = video.checksum).apply { playWhenReady = true } }
    var duration by remember { mutableLongStateOf(((asset.durationSec ?: 0.0) * 1000).toLong()) }
    var range by remember { mutableStateOf(0f..1f) }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var ratio by remember { mutableStateOf(if (asset.width > 0 && asset.height > 0) asset.width.toFloat() / asset.height else 16f / 9f) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    // Play only the chosen part, over and over.
    LaunchedEffect(player) {
        while (true) {
            if (player.duration > 0) duration = player.duration
            position = player.currentPosition
            playing = player.isPlaying
            val end = (range.endInclusive * duration).toLong()
            val start = (range.start * duration).toLong()
            if (duration > 0 && (position >= end || position < start - 300)) player.seekTo(start)
            delay(80)
        }
    }
    val startMs = (range.start * duration).toLong()
    val endMs = (range.endInclusive * duration).toLong()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().padding(top = top + 8.dp, bottom = bottom + 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Cancel", color = Color.White, fontSize = 17.sp) }
                Text("Trim", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.widthIn(min = 80.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                    modifier = Modifier.aspectRatio(ratio.coerceIn(0.2f, 5f)),
                )
            }
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.playWhenReady = !player.playWhenReady }) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White)
                    }
                    Text(
                        "${formatDuration(startMs / 1000.0).ifEmpty { "0:00" }} – ${formatDuration(endMs / 1000.0).ifEmpty { "0:00" }}  ·  ${formatDuration((endMs - startMs) / 1000.0).ifEmpty { "0:00" }}",
                        color = Color.White, fontSize = 14.sp,
                    )
                }
                RangeSlider(
                    value = range,
                    onValueChange = { r ->
                        val minGap = if (duration > 0) 500f / duration else 0.05f
                        range = if (r.endInclusive - r.start < minGap) range else r
                        player.seekTo((range.start * duration).toLong())
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (vm.canUpload) "The trimmed clip is added to your library as a new video; the original stays as it is."
                    else "The trimmed clip is saved to this device (Movies › Cloud Photos).",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                )
                Spacer(Modifier.padding(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(
                        enabled = duration > 0 && (range.start > 0f || range.endInclusive < 1f),
                        onClick = { player.playWhenReady = false; vm.trimVideo(asset, startMs, endMs) { onClose() } },
                    ) { Text("Save as New Clip") }
                }
            }
        }
    }
}
