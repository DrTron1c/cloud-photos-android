package io.github.drtron1c.cloudphotos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.drtron1c.cloudphotos.photos.Asset
import io.github.drtron1c.cloudphotos.photos.MediaList
import io.github.drtron1c.cloudphotos.memories.Memory
import io.github.drtron1c.cloudphotos.memories.MemorySoundtrack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Full-screen slideshow of the photos in an album, with optional Ken Burns pan-and-zoom. A
 * [memory] plays once, in order, with its title and a soundtrack.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun SlideshowScreen(
    vm: MainViewModel,
    list: MediaList,
    startIndex: Int,
    level: Int = 1,
    memory: Memory? = null,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val s = vm.settings
    val view = LocalView.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val window = view.context.findActivity()?.window
        val c = window?.let { WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            c?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val order = remember(list.key) {
        val n = list.count.coerceAtLeast(0)
        val start = startIndex.coerceIn(0, (n - 1).coerceAtLeast(0))
        if (n == 0) emptyList()
        else if (memory != null) (0 until n).toList()
        else if (s.slideshowShuffle) listOf(start) + (0 until n).filter { it != start }.shuffled()
        else (start until n).toList() + (0 until start).toList()
    }
    var position by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<Asset?>(null) }
    var paused by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    // Bumped by the skip buttons to cut the current wait short.
    var skip by remember { mutableIntStateOf(0) }
    var direction by remember { mutableIntStateOf(1) }
    // Memories: title card first, music throughout, "the end" after the last photo.
    var showTitle by remember { mutableStateOf(memory != null) }
    var finished by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    var musicOn by remember { mutableStateOf(memory != null && s.memoryMusic != "none") }
    val music = remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    if (memory != null) {
        LaunchedEffect(Unit) {
            val uri = MemorySoundtrack.uri(context, s)
            if (uri != null) {
                music.value = androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
                    repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                    volume = 0f
                    prepare()
                    playWhenReady = musicOn
                }
            }
        }
        DisposableEffect(Unit) { onDispose { music.value?.release() } }
        // Fade the music in, follow pause / mute, fade out at the end.
        LaunchedEffect(music.value, paused, musicOn, finished) {
            val player = music.value ?: return@LaunchedEffect
            val target = if (musicOn && !paused && !finished) 0.85f else 0f
            if (target > 0f) player.playWhenReady = true
            val from = player.volume
            for (i in 1..15) { player.volume = from + (target - from) * i / 15f; delay(100) }
            if (target == 0f) player.playWhenReady = false
        }
        LaunchedEffect(replay) { showTitle = true; delay(3200); showTitle = false }
    }
    KeyShortcuts(vm, level) { e ->
        when (e.keyCode) {
            android.view.KeyEvent.KEYCODE_ESCAPE -> { onClose(); true }
            android.view.KeyEvent.KEYCODE_SPACE -> { paused = !paused; true }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { direction = 1; skip++; true }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { direction = -1; skip++; true }
            else -> false
        }
    }

    LaunchedEffect(order, skip, replay) {
        if (order.isEmpty()) {
            vm.toast("No photos to show"); onClose(); return@LaunchedEffect
        }
        // A skip button restarts this effect: move straight away in the chosen direction.
        if (skip > 0) position = Math.floorMod(position + direction, order.size)
        var misses = 0
        while (true) {
            val idx = order[position]
            list.ensure(idx)
            var a = list[idx]
            var tries = 0
            while (a == null && tries < 60) {
                delay(100); tries++; a = list[idx]
            }
            val image = a?.displayImage
            if (a == null || a.isVideo || image == null) {
                // Slideshows show photos only, like iPhone; step over videos.
                misses++
                if (misses >= order.size.coerceAtMost(400)) {
                    if (current == null) {
                        vm.toast("No photos to show"); onClose()
                    }
                    return@LaunchedEffect
                }
                position = Math.floorMod(position + direction, order.size)
                continue
            }
            misses = 0
            // Load before showing so the cross-fade never reveals a blank frame.
            runCatching {
                vm.imageLoader.execute(
                    ImageRequest.Builder(context).data(vm.model(image)).memoryCacheKey(image.checksum).diskCacheKey(image.checksum).build()
                )
            }
            current = a
            direction = 1
            val waitMs = s.slideshowSeconds.coerceIn(2, 20) * 1000L
            var waited = 0L
            while (waited < waitMs || paused) {
                delay(100)
                if (!paused) waited += 100
            }
            if (memory != null && position == order.size - 1) {
                finished = true
                return@LaunchedEffect
            }
            position = Math.floorMod(position + 1, order.size)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }) },
    ) {
        val shown = current
        if (shown == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(30.dp), color = Color.White, strokeWidth = 2.5.dp)
        } else {
            Crossfade(targetState = shown, animationSpec = tween(900), label = "slide") { a ->
                Slide(vm, a, kenBurns = s.slideshowKenBurns || memory != null, seconds = s.slideshowSeconds)
            }
        }

        if (memory != null) {
            AnimatedVisibility(visible = showTitle && !finished, enter = fadeIn(tween(900)), exit = fadeOut(tween(900)), modifier = Modifier.align(Alignment.Center)) {
                Column(
                    Modifier.background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(horizontal = 30.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(memory.title, color = Color.White, fontSize = 34.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(memory.subtitle, color = Color.White.copy(alpha = 0.9f), fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (finished) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(memory.title, color = Color.White, fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            androidx.compose.material3.Button(onClick = { finished = false; position = 0; replay++ }) { Text("Play Again") }
                            androidx.compose.material3.OutlinedButton(onClick = { onClose(); vm.memoryList(memory)?.let { vm.openList(it) } }) { Text("Show Photos", color = Color.White) }
                        }
                        androidx.compose.material3.TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                    }
                }
            }
        }
        AnimatedVisibility(visible = (controls || paused) && !finished, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.TopStart).padding(top = top + 8.dp, start = 12.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                ) { Icon(Icons.Filled.Close, "Close slideshow", tint = Color.White) }
                if (shown != null) {
                    Column(
                        Modifier.align(Alignment.TopCenter).padding(top = top + 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(formatDay(shown.date), color = Color.White, fontSize = 15.sp)
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 28.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { direction = -1; skip++ }) { Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White) }
                    IconButton(onClick = { paused = !paused }) {
                        Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Play" else "Pause", tint = Color.White)
                    }
                    IconButton(onClick = { direction = 1; skip++ }) { Icon(Icons.Filled.SkipNext, "Next", tint = Color.White) }
                    if (memory != null && music.value != null) {
                        IconButton(onClick = { musicOn = !musicOn }) {
                            Icon(
                                if (musicOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                if (musicOn) "Music off" else "Music on", tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Slide(vm: MainViewModel, asset: Asset, kenBurns: Boolean, seconds: Int) {
    val context = LocalContext.current
    val image = asset.displayImage ?: return
    val progress = remember(asset.id) { Animatable(0f) }
    // Each slide drifts in a different direction.
    val dir = remember(asset.id) { Random.nextInt(4) }
    LaunchedEffect(asset.id, kenBurns) {
        if (kenBurns) progress.animateTo(1f, tween((seconds.coerceIn(2, 20) * 1000) + 1800, easing = LinearEasing))
    }
    AsyncImage(
        model = remember(image.checksum) {
            ImageRequest.Builder(context).data(vm.model(image)).memoryCacheKey(image.checksum).diskCacheKey(image.checksum).build()
        },
        contentDescription = asset.filename,
        imageLoader = vm.imageLoader,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .graphicsLayer {
                if (kenBurns) {
                    val t = progress.value
                    val scale = if (dir % 2 == 0) 1.0f + 0.14f * t else 1.14f - 0.14f * t
                    scaleX = scale
                    scaleY = scale
                    val dx = size.width * 0.035f * (if (dir < 2) t else -t)
                    val dy = size.height * 0.025f * (if (dir == 1 || dir == 2) t else -t)
                    translationX = dx
                    translationY = dy
                }
            },
    )
}
