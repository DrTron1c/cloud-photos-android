package io.github.drtron1c.cloudphotos.ui

import android.view.ViewGroup
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Size
import androidx.media3.common.VideoSize
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.drtron1c.cloudphotos.photos.AlbumKind
import io.github.drtron1c.cloudphotos.photos.AlbumSpec
import io.github.drtron1c.cloudphotos.photos.Asset
import io.github.drtron1c.cloudphotos.photos.Version
import kotlinx.coroutines.delay
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
private fun newPlayer(context: android.content.Context, http: OkHttpClient, url: String): ExoPlayer =
    ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http)))
        .build()
        .apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: MainViewModel, spec: AlbumSpec, startIndex: Int, onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val list = vm.list(spec)
    val count = list.count.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (count - 1).coerceAtLeast(0))) { count }
    var chrome by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var liveTrigger by remember { mutableIntStateOf(0) }
    val flick = rememberFlickToDismissState(dismissThresholdRatio = 0.12f, rotateOnDrag = false)
    val close by rememberUpdatedState(onClose)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(flick.gestureState) {
        if (flick.gestureState is FlickToDismissState.GestureState.Dismissed) close()
    }
    LaunchedEffect(list.count) { if (list.count == 0) close() }
    DisposableEffect(Unit) {
        onDispose { vm.lastViewed = spec.key to pager.currentPage }
    }

    // Tap to hide bars → true full screen, like iPhone.
    val view = LocalView.current
    LaunchedEffect(chrome) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        if (chrome) c.show(WindowInsetsCompat.Type.systemBars())
        else {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = view.context.findActivity()?.window
            if (window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val baseBg by animateColorAsState(if (chrome) p.background else Color.Black, label = "viewerBg")
    val fade = 1f - (flick.offsetFraction * 1.6f).coerceIn(0f, 1f)
    val dragging = flick.gestureState !is FlickToDismissState.GestureState.Idle

    Box(Modifier.fillMaxSize().background(baseBg.copy(alpha = fade))) {
        FlickToDismiss(state = flick, modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 1,
                pageSpacing = 16.dp,
                key = { it },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val asset = list[page]
                LaunchedEffect(page, asset == null) { list.ensure(page) }
                when {
                    asset == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                    asset.isVideo -> VideoPage(
                        vm = vm,
                        asset = asset,
                        active = pager.settledPage == page,
                        chrome = chrome && !dragging,
                        controlsBottom = navBottom + 56.dp,
                        onTap = { chrome = !chrome },
                    )
                    else -> PhotoPage(
                        vm = vm,
                        asset = asset,
                        liveTrigger = if (pager.currentPage == page) liveTrigger else 0,
                        onTap = { chrome = !chrome },
                        onLongPress = { if (asset.isLive) liveTrigger++ },
                    )
                }
            }
        }

        val current = list[pager.currentPage]

        // Top bar
        AnimatedVisibility(
            visible = chrome && !dragging,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(Modifier.fillMaxWidth().background(p.bar).padding(top = statusTop)) {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = p.accent)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(formatDay(current?.date ?: 0), color = p.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(formatTime(current?.date ?: 0), color = p.secondaryLabel, fontSize = 12.sp)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = current != null) {
                            Icon(Icons.Outlined.MoreHoriz, "More", tint = p.accent)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (current != null) {
                                DropdownMenuItem(
                                    text = { Text("Save to Device") },
                                    leadingIcon = { Icon(Icons.Outlined.Download, null) },
                                    onClick = { menu = false; vm.saveToDevice(listOf(current)) },
                                )
                                if (current.isLive) {
                                    DropdownMenuItem(
                                        text = { Text("Play Live Photo") },
                                        leadingIcon = { Icon(Icons.Filled.MotionPhotosOn, null) },
                                        onClick = { menu = false; liveTrigger++ },
                                    )
                                }
                                if (spec.kind != AlbumKind.Deleted) {
                                    DropdownMenuItem(
                                        text = { Text(if (current.isHidden || spec.kind == AlbumKind.Hidden) "Unhide" else "Hide") },
                                        leadingIcon = {
                                            Icon(if (spec.kind == AlbumKind.Hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null)
                                        },
                                        onClick = {
                                            menu = false
                                            vm.setHidden(listOf(current), spec.kind != AlbumKind.Hidden)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (current?.isLive == true) {
                    Row(Modifier.padding(start = 14.dp, bottom = 8.dp)) {
                        Row(
                            Modifier
                                .background(p.card, RoundedCornerShape(8.dp))
                                .clickable { liveTrigger++ }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.MotionPhotosOn, null, tint = p.label, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("LIVE", color = p.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Bottom toolbar
        AnimatedVisibility(
            visible = chrome && !dragging,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(p.bar)
                    .padding(bottom = navBottom)
                    .height(56.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val enabled = current != null
                if (spec.kind == AlbumKind.Deleted) {
                    TextButton(onClick = { current?.let { vm.recover(listOf(it)) } }, enabled = enabled) {
                        Text("Recover", fontSize = 17.sp)
                    }
                    IconButton(onClick = { showInfo = true }, enabled = enabled) {
                        Icon(Icons.Outlined.Info, "Info", tint = p.accent)
                    }
                } else {
                    IconButton(onClick = { current?.let { a -> vm.share(listOf(a)) { context.startActivity(it) } } }, enabled = enabled) {
                        Icon(Icons.Outlined.Share, "Share", tint = p.accent)
                    }
                    IconButton(onClick = { current?.let { vm.toggleFavorite(it) } }, enabled = enabled) {
                        if (current?.isFavorite == true) Icon(Icons.Filled.Favorite, "Unfavourite", tint = p.accent)
                        else Icon(Icons.Outlined.FavoriteBorder, "Favourite", tint = p.accent)
                    }
                    IconButton(onClick = { showInfo = true }, enabled = enabled) {
                        Icon(Icons.Outlined.Info, "Info", tint = p.accent)
                    }
                    IconButton(onClick = { confirmDelete = true }, enabled = enabled) {
                        Icon(Icons.Outlined.Delete, "Delete", tint = p.accent)
                    }
                }
            }
        }
    }

    val current = list[pager.currentPage]
    if (showInfo && current != null) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }, containerColor = p.card) {
            InfoSheet(current)
        }
    }
    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (current.isVideo) "Delete video?" else "Delete photo?") },
            text = { Text("It will be removed from iCloud Photos on all your devices and kept in Recently Deleted for 30 days.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(listOf(current)) }) {
                    Text(if (current.isVideo) "Delete Video" else "Delete Photo", color = p.destructive)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PhotoPage(vm: MainViewModel, asset: Asset, liveTrigger: Int, onTap: () -> Unit, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val image = asset.displayImage
    Box(Modifier.fillMaxSize()) {
        if (image == null) {
            Text(
                "This item can't be shown here.",
                color = LocalPalette.current.secondaryLabel,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        val request = remember(image.checksum) {
            ImageRequest.Builder(context)
                .data(image.url)
                .memoryCacheKey(image.checksum)
                .diskCacheKey(image.checksum)
                .placeholderMemoryCacheKey(asset.thumbnail?.checksum)
                .build()
        }
        ZoomableAsyncImage(
            model = request,
            contentDescription = asset.filename,
            imageLoader = vm.imageLoader,
            modifier = Modifier.fillMaxSize(),
            onClick = { onTap() },
            onLongClick = { onLongPress() },
        )
        val motion = asset.liveVideo
        if (motion != null && liveTrigger > 0) {
            LivePlayback(vm, motion, liveTrigger)
        }
    }
}

/** Plays a Live Photo's motion once over the still, then fades back. */
@OptIn(UnstableApi::class)
@Composable
private fun LivePlayback(vm: MainViewModel, motion: Version, trigger: Int) {
    val context = LocalContext.current
    var playing by remember(trigger) { mutableStateOf(true) }
    if (!playing) return
    val player = remember(trigger) { newPlayer(context, vm.http, motion.url).apply { playWhenReady = true } }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playing = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                this.player = player
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    vm: MainViewModel,
    asset: Asset,
    active: Boolean,
    chrome: Boolean,
    controlsBottom: androidx.compose.ui.unit.Dp,
    onTap: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val video = asset.playbackVideo
    Box(Modifier.fillMaxSize()) {
        val poster = asset.medium ?: asset.thumbnail
        if (poster != null) {
            AsyncImage(
                model = remember(poster.checksum) {
                    ImageRequest.Builder(context).data(poster.url).memoryCacheKey(poster.checksum).diskCacheKey(poster.checksum)
                        .placeholderMemoryCacheKey(asset.thumbnail?.checksum).build()
                },
                contentDescription = asset.filename,
                imageLoader = vm.imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (video == null) {
            Text("This video can't be played here.", color = p.secondaryLabel, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        if (active) {
            val player = remember(asset.id) { newPlayer(context, vm.http, video.url).apply { playWhenReady = true } }
            // Start from the stored dimensions; the decoder's real (rotation-corrected) size replaces it.
            var videoSize by remember(asset.id) {
                mutableStateOf(
                    if (asset.width > 0 && asset.height > 0) Size(asset.width.toFloat(), asset.height.toFloat())
                    else Size(16f, 9f)
                )
            }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onVideoSizeChanged(size: VideoSize) {
                        if (size.width > 0 && size.height > 0) {
                            videoSize = Size(size.width * size.pixelWidthHeightRatio, size.height.toFloat())
                        }
                    }
                }
                player.addListener(listener)
                onDispose {
                    player.removeListener(listener)
                    player.release()
                }
            }
            // Pinch / double-tap to zoom the playing video, same gestures as photos.
            val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 6f))
            LaunchedEffect(videoSize) {
                zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(videoSize))
            }
            Box(Modifier.fillMaxSize().zoomable(zoom, onClick = { onTap() })) {
                // A TextureView (unlike PlayerView's default SurfaceView) can be scaled and moved by Compose.
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                    modifier = Modifier.align(Alignment.Center).aspectRatio(videoSize.width / videoSize.height),
                )
            }
            VideoControls(player, visible = chrome, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = controlsBottom + 10.dp))
        } else {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) })
            Box(
                Modifier.align(Alignment.Center).size(64.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(38.dp))
            }
        }
    }
}

@Composable
private fun VideoControls(player: ExoPlayer, visible: Boolean, modifier: Modifier) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            buffering = player.playbackState == Player.STATE_BUFFERING
            delay(200)
        }
    }
    Box(modifier.fillMaxWidth()) {
        if (buffering) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).padding(bottom = 200.dp).size(34.dp),
                color = Color.White, strokeWidth = 3.dp,
            )
        }
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .padding(horizontal = 14.dp)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (player.playbackState == Player.STATE_ENDED) {
                        player.seekTo(0); player.playWhenReady = true
                    } else player.playWhenReady = !player.playWhenReady
                }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White)
                }
                Text(formatDuration((if (scrub >= 0) scrub * duration else position.toFloat()) / 1000.0).ifEmpty { "0:00" },
                    color = Color.White, fontSize = 12.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
                Slider(
                    value = if (scrub >= 0) scrub else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        if (duration > 0 && scrub >= 0) player.seekTo((scrub * duration).toLong())
                        scrub = -1f
                    },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f),
                )
                Text(formatDuration(duration / 1000.0).ifEmpty { "0:00" }, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
                IconButton(onClick = { muted = !muted; player.volume = if (muted) 0f else 1f }) {
                    Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Mute", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun InfoSheet(asset: Asset) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
        Text(formatFullDate(asset.date), color = p.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        InfoLine("Name", asset.filename)
        val mp = if (asset.width > 0 && asset.height > 0) asset.width.toLong() * asset.height / 1_000_000.0 else 0.0
        if (asset.width > 0) InfoLine("Size", "${asset.width} × ${asset.height}" + if (!asset.isVideo && mp >= 0.5) " • %.0f MP".format(mp) else "")
        InfoLine("File size", formatBytes(asset.original?.size ?: 0))
        InfoLine("Type", buildString {
            append(Asset.extensionFor(asset.original?.fileType ?: asset.itemType, if (asset.isVideo) "Video" else "Photo"))
            if (asset.isLive) append(" • Live Photo")
            if (asset.isVideo && asset.durationSec != null) append(" • " + formatDuration(asset.durationSec))
        })
        if (asset.edited != null) InfoLine("Edited", "Yes — sharing and saving use the edited version")
        if (asset.isFavorite) InfoLine("Favourite", "Yes")
        if (asset.addedDate > 0) InfoLine("Added to iCloud", formatFullDate(asset.addedDate))
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = p.secondaryLabel, fontSize = 13.sp)
        Text(value, color = p.label, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(thickness = 0.5.dp, color = p.separator)
    }
}
