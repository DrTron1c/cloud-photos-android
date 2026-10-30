package io.github.drtron1c.cloudphotos.ui

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.TextureView
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Slideshow
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.drtron1c.cloudphotos.photos.AlbumKind
import io.github.drtron1c.cloudphotos.photos.Asset
import io.github.drtron1c.cloudphotos.photos.MediaList
import io.github.drtron1c.cloudphotos.photos.Version
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import okhttp3.OkHttpClient
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How far photos and videos zoom (× the fitted size). Full-resolution detail is loaded as you go in. */
const val MAX_ZOOM = 16f

/**
 * Plays local (offline) files and iCloud links through the same encrypted-DNS client; streamed
 * videos go through the temporary cache ([cache], keyed by [cacheKey]) when it's on.
 */
@OptIn(UnstableApi::class)
fun newPlayer(
    context: android.content.Context,
    http: OkHttpClient,
    uri: Uri,
    loop: Boolean = false,
    muted: Boolean = false,
    cache: io.github.drtron1c.cloudphotos.photos.MediaCache? = null,
    cacheKey: String? = null,
): ExoPlayer =
    ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(cache?.factoryFor(uri) ?: DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http))))
        .build()
        .apply {
            setMediaItem(MediaItem.Builder().setUri(uri).apply { if (cacheKey != null) setCustomCacheKey(cacheKey) }.build())
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (muted) 0f else 1f
            prepare()
        }

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    vm: MainViewModel,
    list: MediaList,
    startIndex: Int,
    covered: Boolean = false,
    level: Int = 1,
    inSplit: Boolean = false,
    /** Open with the bars hidden (full screen). */
    startFullScreen: Boolean = false,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    val s = vm.settings
    val context = LocalContext.current
    val spec = list.spec
    val count = list.count.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (count - 1).coerceAtLeast(0))) { count }
    var chrome by remember { mutableStateOf(!startFullScreen) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var addToAlbum by remember { mutableStateOf<Asset?>(null) }
    var liveTrigger by remember { mutableIntStateOf(0) }
    // Live Text: the photo's text laid over it, selectable (long-press, T or the Text button).
    var liveText by remember { mutableStateOf<Pair<String, io.github.drtron1c.cloudphotos.ai.TextLayout>?>(null) }
    var resetZoom by remember { mutableIntStateOf(0) }
    // The globe button: where this was taken, on the map.
    var mapFor by remember { mutableStateOf<Asset?>(null) }
    // Shared Albums: likes and comments.
    val sharedAlbum = remember(list.key) { vm.sharedAlbumOf(list) }
    var commentsOpen by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf<List<io.github.drtron1c.cloudphotos.photos.SharedComment>?>(null) }
    var commentsFor by remember { mutableStateOf<String?>(null) }
    // Keyboard: space / P go to the video on screen.
    var videoKey by remember { mutableStateOf<((Int) -> Boolean)?>(null) }
    val focus = LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val flick = rememberFlickToDismissState(dismissThresholdRatio = 0.12f, rotateOnDrag = false)
    val close by rememberUpdatedState(onClose)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val online = !vm.offlineMode
    val editable = spec.editable && online

    // A hardware keyboard drives the viewer, not a search field left focused underneath.
    LaunchedEffect(Unit) { runCatching { focus.clearFocus(force = true) } }
    LaunchedEffect(flick.gestureState) {
        if (flick.gestureState is FlickToDismissState.GestureState.Dismissed) close()
    }
    LaunchedEffect(list.count) { if (list.count == 0) close() }
    DisposableEffect(Unit) {
        onDispose { vm.lastViewed = list.key to pager.currentPage }
    }
    // Settled on a new item: remember it (if enabled) and give Live Photos a quick play, like iPhone.
    LaunchedEffect(pager.settledPage, list[pager.settledPage]?.id) {
        val a = list[pager.settledPage] ?: return@LaunchedEffect
        if (spec.editable) vm.noteViewed(a)
        if (inSplit) vm.viewingId = a.id
        if (a.isLive && s.livePhotoAutoplay) liveTrigger++
    }
    DisposableEffect(inSplit) { onDispose { if (inSplit) vm.viewingId = null } }

    fun readText(a: Asset, fromLongPress: Boolean = false) {
        // A long-press on a Live Photo plays it unless there's text to select.
        val quiet = fromLongPress && a.isLive && !vm.textReaderReady
        vm.readText(a, quiet = quiet) { layout ->
            when {
                layout != null && layout.lines.isNotEmpty() -> {
                    resetZoom++
                    chrome = false
                    liveText = a.id to layout
                }
                fromLongPress && a.isLive -> liveTrigger++
                layout != null -> vm.toast("No text found in this photo")
            }
        }
    }

    KeyShortcuts(vm, level, enabled = !covered && !vm.inPip) { e ->
        val a = list[pager.currentPage]
        val code = e.keyCode
        when {
            code == KeyEvent.KEYCODE_ESCAPE -> { if (liveText != null) liveText = null else onClose(); true }
            code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (pager.currentPage < count - 1) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }; true
            }
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_PAGE_UP -> {
                if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }; true
            }
            code == KeyEvent.KEYCODE_MOVE_HOME -> { scope.launch { pager.scrollToPage(0) }; true }
            code == KeyEvent.KEYCODE_MOVE_END -> { scope.launch { pager.scrollToPage((count - 1).coerceAtLeast(0)) }; true }
            a == null -> false
            e.ctrl && code == KeyEvent.KEYCODE_C -> { vm.copyToClipboard(a); true }
            e.ctrl && e.isShiftPressed && code == KeyEvent.KEYCODE_S -> { vm.share(listOf(a)) { context.startActivity(it) }; true }
            e.ctrl && code == KeyEvent.KEYCODE_S -> { if (online) vm.saveToDevice(listOf(a)); true }
            e.ctrl && code == KeyEvent.KEYCODE_ENTER -> { vm.open(Route.Slideshow(list.key, pager.currentPage)); true }
            !e.plain -> false
            code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_P || code == KeyEvent.KEYCODE_M -> {
                if (a.isVideo || a.isGif) videoKey?.invoke(code) ?: false
                else if (code == KeyEvent.KEYCODE_SPACE) { if (a.isLive) liveTrigger++ else chrome = !chrome; true } else false
            }
            code == KeyEvent.KEYCODE_F || code == KeyEvent.KEYCODE_PERIOD -> { if (editable) vm.toggleFavorite(a); true }
            code == KeyEvent.KEYCODE_I -> { showInfo = !showInfo; true }
            code == KeyEvent.KEYCODE_E -> { if (editable && !a.isVideo) vm.open(Route.Editor(list.key, pager.currentPage)); true }
            code == KeyEvent.KEYCODE_T -> { if (!a.isVideo) readText(a); true }
            code == KeyEvent.KEYCODE_L -> { if (a.isLive) liveTrigger++; true }
            code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (editable && spec.kind != AlbumKind.Deleted) { if (s.confirmDelete) confirmDelete = true else vm.delete(listOf(a)) }; true
            }
            else -> false
        }
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

    val showChrome = chrome && !vm.inPip
    val baseBg by animateColorAsState(if (showChrome) p.background else Color.Black, label = "viewerBg")
    val fade = 1f - (flick.offsetFraction * 1.6f).coerceIn(0f, 1f)
    val dragging = flick.gestureState !is FlickToDismissState.GestureState.Idle

    // The photo is the backdrop for the glass bars.
    val viewerGlass = if (LocalGlassEnabled.current) rememberGlassBackdrop() else null
    val glassOn = viewerGlass != null
    CompositionLocalProvider(LocalGlassBackdrop provides viewerGlass) {
    Box(Modifier.fillMaxSize().background(baseBg.copy(alpha = fade))) {
        FlickToDismiss(state = flick, modifier = Modifier.fillMaxSize().glassSource(viewerGlass)) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !vm.inPip,
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
                    asset.isVideo || asset.isGif -> VideoPage(
                        vm = vm,
                        asset = asset,
                        active = pager.settledPage == page && !covered,
                        chrome = showChrome && !dragging,
                        controlsBottom = navBottom + 56.dp,
                        editable = editable,
                        onTrim = { vm.open(Route.Trim(list.key, page)) },
                        onKeyTarget = { if (pager.settledPage == page) videoKey = it },
                        onTap = { chrome = !chrome },
                        onZoomIn = { chrome = false },
                    )
                    else -> PhotoPage(
                        vm = vm,
                        asset = asset,
                        liveTrigger = if (pager.currentPage == page) liveTrigger else 0,
                        resetZoom = if (pager.currentPage == page) resetZoom else 0,
                        onTap = { chrome = !chrome },
                        onZoomIn = { chrome = false },
                        onLongPress = {
                            // Side by side with another app: long-press drags the photo into it;
                            // otherwise it selects the text in the photo (or plays a Live Photo).
                            if (vm.canDragOut(context)) vm.startDragOut(view, asset)
                            else readText(asset, fromLongPress = true)
                        },
                    )
                }
            }
        }

        val current = list[pager.currentPage]

        val shownText = liveText?.takeIf { it.first == current?.id }?.second
        if (shownText != null) {
            LiveTextOverlay(vm, shownText, statusTop) { liveText = null }
        }

        // Top bar
        AnimatedVisibility(
            visible = showChrome && !dragging,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            GlassSurface(
                if (glassOn) Modifier.padding(top = statusTop + 6.dp, start = 10.dp, end = 10.dp).fillMaxWidth() else Modifier.fillMaxWidth(),
                cornerRadius = if (glassOn) 26.dp else 0.dp,
                elevation = if (glassOn) 8.dp else 0.dp,
            ) {
            Column(Modifier.fillMaxWidth().padding(top = if (glassOn) 0.dp else statusTop)) {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = p.accent)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(formatDay(current?.date ?: 0), color = p.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(formatTime(current?.date ?: 0), color = p.secondaryLabel, fontSize = 12.sp)
                    }
                    if (current != null && current.latitude != null && current.longitude != null) {
                        IconButton(onClick = { mapFor = current }) {
                            Icon(Icons.Outlined.Public, "Show on map", tint = p.accent)
                        }
                    }
                    if (current != null && !current.isVideo && vm.settings.aiText) {
                        IconButton(onClick = { readText(current) }) {
                            Icon(Icons.Outlined.TextFields, "Text in photo", tint = p.accent)
                        }
                    }
                    if (current != null && !current.isVideo && editable) {
                        TextButton(onClick = { vm.open(Route.Editor(list.key, pager.currentPage)) }) { Text("Edit", fontSize = 16.sp) }
                    }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = current != null) {
                            Icon(Icons.Outlined.MoreHoriz, "More", tint = p.accent)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (current != null) {
                                DropdownMenuItem(
                                    text = { Text("Slideshow") },
                                    leadingIcon = { Icon(Icons.Outlined.Slideshow, null) },
                                    onClick = { menu = false; vm.open(Route.Slideshow(list.key, pager.currentPage)) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = { menu = false; vm.copyToClipboard(current) },
                                )
                                if (!current.isVideo) {
                                    DropdownMenuItem(
                                        text = { Text("Text in Photo") },
                                        leadingIcon = { Icon(Icons.Outlined.TextFields, null) },
                                        onClick = { menu = false; readText(current) },
                                    )
                                }
                                if (online) {
                                    DropdownMenuItem(
                                        text = { Text("Save to Device") },
                                        leadingIcon = { Icon(Icons.Outlined.Download, null) },
                                        onClick = { menu = false; vm.saveToDevice(listOf(current)) },
                                    )
                                }
                                if (editable) {
                                    DropdownMenuItem(
                                        text = { Text("Add to Album") },
                                        leadingIcon = { Icon(Icons.Outlined.Collections, null) },
                                        onClick = { menu = false; addToAlbum = current },
                                    )
                                }
                                if (!current.isVideo) {
                                    DropdownMenuItem(
                                        text = { Text("Use as App Icon") },
                                        leadingIcon = { Icon(Icons.Outlined.Apps, null) },
                                        onClick = { menu = false; vm.useAsAppIcon(current) },
                                    )
                                }
                                if (current.isLive) {
                                    DropdownMenuItem(
                                        text = { Text("Play Live Photo") },
                                        leadingIcon = { Icon(Icons.Filled.MotionPhotosOn, null) },
                                        onClick = { menu = false; liveTrigger++ },
                                    )
                                }
                                if (editable && spec.kind != AlbumKind.Deleted) {
                                    val hidden = spec.kind == AlbumKind.Hidden
                                    DropdownMenuItem(
                                        text = { Text(if (hidden) "Unhide" else "Hide") },
                                        leadingIcon = { Icon(if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null) },
                                        onClick = { menu = false; vm.setHidden(listOf(current), !hidden) },
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
                if (!current?.caption.isNullOrBlank()) {
                    Text(
                        current?.caption ?: "",
                        color = p.label, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    )
                }
            }
            }
        }

        // Bottom toolbar
        AnimatedVisibility(
            visible = showChrome && !dragging,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            GlassBar(navBottom) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val enabled = current != null
                when {
                    !online -> {
                        IconButton(onClick = { showInfo = true }, enabled = enabled) {
                            Icon(Icons.Outlined.Info, "Info", tint = p.accent)
                        }
                    }
                    sharedAlbum != null -> {
                        val liked = comments?.any { it.isLike && it.byMe } == true && commentsFor == current?.id
                        LaunchedEffect(current?.id) {
                            val a = current ?: return@LaunchedEffect
                            comments = null; commentsFor = a.id
                            vm.loadComments(sharedAlbum, a) { if (commentsFor == a.id) comments = it }
                        }
                        IconButton(onClick = { current?.let { a -> vm.share(listOf(a)) { context.startActivity(it) } } }, enabled = enabled) {
                            Icon(Icons.Outlined.Share, "Share", tint = p.accent)
                        }
                        IconButton(onClick = {
                            val a = current ?: return@IconButton
                            vm.toggleLike(sharedAlbum, a, comments.orEmpty()) { vm.loadComments(sharedAlbum, a) { comments = it } }
                        }, enabled = enabled && comments != null) {
                            if (liked) Icon(Icons.Filled.Favorite, "Unlike", tint = p.accent) else Icon(Icons.Outlined.FavoriteBorder, "Like", tint = p.accent)
                        }
                        val n = comments?.count { !it.isLike } ?: 0
                        TextButton(onClick = { commentsOpen = true }, enabled = enabled) {
                            Text(if (n > 0) "Comments ($n)" else "Comment", fontSize = 15.sp)
                        }
                        IconButton(onClick = { showInfo = true }, enabled = enabled) {
                            Icon(Icons.Outlined.Info, "Info", tint = p.accent)
                        }
                    }
                    spec.kind == AlbumKind.Deleted -> {
                        TextButton(onClick = { current?.let { vm.recover(listOf(it)) } }, enabled = enabled) {
                            Text("Recover", fontSize = 17.sp)
                        }
                        IconButton(onClick = { showInfo = true }, enabled = enabled) {
                            Icon(Icons.Outlined.Info, "Info", tint = p.accent)
                        }
                    }
                    else -> {
                        IconButton(onClick = { current?.let { a -> vm.share(listOf(a)) { context.startActivity(it) } } }, enabled = enabled) {
                            Icon(Icons.Outlined.Share, "Share", tint = p.accent)
                        }
                        if (editable) {
                            IconButton(onClick = { current?.let { vm.toggleFavorite(it) } }, enabled = enabled) {
                                if (current?.isFavorite == true) Icon(Icons.Filled.Favorite, "Unfavourite", tint = p.accent)
                                else Icon(Icons.Outlined.FavoriteBorder, "Favourite", tint = p.accent)
                            }
                        }
                        IconButton(onClick = { showInfo = true }, enabled = enabled) {
                            Icon(Icons.Outlined.Info, "Info", tint = p.accent)
                        }
                        if (editable) {
                            IconButton(onClick = {
                                if (s.confirmDelete) confirmDelete = true else current?.let { vm.delete(listOf(it)) }
                            }, enabled = enabled) {
                                Icon(Icons.Outlined.Delete, "Delete", tint = p.accent)
                            }
                        }
                    }
                }
            }
            }
        }
    }
    }

    val current = list[pager.currentPage]
    if (showInfo && current != null) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }, containerColor = p.card) {
            InfoSheet(vm, current, editable)
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
    addToAlbum?.let { a ->
        AddToAlbumDialog(vm, listOf(a), onDismiss = { addToAlbum = null }, onDone = { addToAlbum = null })
    }
    mapFor?.let { a ->
        // A dialog rather than a sheet, so dragging the map doesn't drag the sheet.
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { mapFor = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier.padding(16.dp).widthIn(max = 760.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)).background(p.card).padding(top = 14.dp),
            ) {
                LocationSheet(vm, a) { mapFor = null }
            }
        }
    }
    if (commentsOpen && current != null && sharedAlbum != null) {
        ModalBottomSheet(onDismissRequest = { commentsOpen = false }, containerColor = p.card) {
            CommentsSheet(vm, sharedAlbum, current, comments.orEmpty()) { vm.loadComments(sharedAlbum, current) { comments = it } }
        }
    }
}

/** Live Text: each line of the photo's text in place over it — drag to select, or Copy All. */
@Composable
private fun LiveTextOverlay(vm: MainViewModel, layout: io.github.drtron1c.cloudphotos.ai.TextLayout, top: Dp, onClose: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
    ) {
        val bw = constraints.maxWidth.toFloat()
        val bh = constraints.maxHeight.toFloat()
        val fitW = min(bw, bh * layout.aspect)
        val fitH = fitW / layout.aspect
        val ox = (bw - fitW) / 2
        val oy = (bh - fitH) / 2
        androidx.compose.foundation.text.selection.SelectionContainer {
            Box(Modifier.fillMaxSize()) {
                for (l in layout.lines) {
                    val x = ox + l.x * fitW
                    val y = oy + l.y * fitH
                    val w = (l.w * fitW).coerceAtLeast(8f)
                    val h = (l.h * fitH).coerceAtLeast(8f)
                    Box(
                        Modifier
                            .offset { androidx.compose.ui.unit.IntOffset(x.roundToInt(), y.roundToInt()) }
                            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                            .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            l.text, color = Color.Black,
                            fontSize = with(density) { (h * 0.62f).toSp() },
                            maxLines = 1, softWrap = false,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(top = top + 12.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(text = "Copy All", onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(layout.lines.joinToString("\n") { it.text }))
                vm.toast("Text copied")
            })
            Pill(text = "Done", onClick = onClose)
        }
        Text(
            "Long-press a word, then drag to select",
            color = Color.White, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Where a photo or video was taken: its place name and a map with it on (globe button). */
@Composable
private fun LocationSheet(vm: MainViewModel, asset: Asset, onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val lat = asset.latitude ?: return
    val lon = asset.longitude ?: return
    val s = vm.settings
    val place = remember(lat, lon) { vm.placeLabel(lat, lon) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(place ?: "Location", color = p.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("%.5f, %.5f".format(lat, lon), color = p.secondaryLabel, fontSize = 13.sp)
            }
            TextButton(onClick = onClose) { Text("Done") }
        }
        Spacer(Modifier.height(10.dp))
        if (s.placesMap) {
            val thumb = thumbRequest(vm, asset)
            io.github.drtron1c.cloudphotos.places.OsmMap(
                points = remember(asset.id) { listOf(io.github.drtron1c.cloudphotos.places.MapPoint(asset.id, lat, lon)) },
                geo = vm.geoOrNull(),
                imageLoader = vm.imageLoader,
                thumbnail = { thumb },
                onOpen = { },
                modifier = Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(14.dp)),
            )
        } else {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.placeholder).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Show a map?", color = p.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "The map pictures come from Esri (ArcGIS): their servers see which map areas you look at — never your photos. Place names are in English.",
                    color = p.secondaryLabel, fontSize = 13.sp,
                )
                TextButton(onClick = { s.placesMap = true }) { Text("Show Map") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = {
                val near = vm.photosNear(lat, lon)
                if (near.isNotEmpty()) { onClose(); vm.idsList("near:${asset.id}", place ?: "Nearby", near, "place", keepOrder = false)?.let { vm.openList(it) } }
                else vm.toast("Still organising your library…")
            }) { Text("Photos Taken Here") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                vm.expectingExternal = true
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))) }
                    .onFailure { vm.toast("No maps app installed") }
            }) { Text("Open in Maps App") }
        }
    }
}

/** Shared Album comments and likes on one photo. */
@Composable
private fun CommentsSheet(
    vm: MainViewModel,
    album: io.github.drtron1c.cloudphotos.photos.SharedAlbum,
    asset: Asset,
    comments: List<io.github.drtron1c.cloudphotos.photos.SharedComment>,
    reload: () -> Unit,
) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
        Text("Comments", color = p.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        val likes = comments.filter { it.isLike }
        if (likes.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("♥ Liked by " + likes.joinToString { if (it.byMe) "you" else it.name.ifBlank { "someone" } }, color = p.secondaryLabel, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            val texts = comments.filter { !it.isLike }
            if (texts.isEmpty()) Text("No comments yet.", color = p.secondaryLabel, fontSize = 15.sp)
            for (c in texts) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(if (c.byMe) "You" else c.name.ifBlank { "Someone" }, color = p.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(c.text, color = p.label, fontSize = 15.sp)
                    }
                    if (c.byMe || c.canDelete) {
                        TextButton(onClick = { vm.deleteComment(album, asset, c, reload) }) { Text("Delete", color = p.destructive, fontSize = 13.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Add a comment") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextButton(enabled = text.isNotBlank(), onClick = { vm.addComment(album, asset, text) { text = ""; reload() } }) { Text("Post") }
        }
    }
}

/** Live Text: the words found in the photo, selectable, with Copy All. */
@Composable
private fun TextInPhotoSheet(vm: MainViewModel, lines: List<String>) {
    val p = LocalPalette.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Text in Photo", color = p.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (lines.isNotEmpty()) {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(lines.joinToString("\n")))
                    vm.toast("Text copied")
                }) { Text("Copy All") }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (lines.isEmpty()) {
            Text("No text found in this photo.", color = p.secondaryLabel, fontSize = 15.sp)
        } else {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    for (line in lines) Text(line, color = p.label, fontSize = 16.sp, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Long-press to select part of it. Read on this device.", color = p.secondaryLabel, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PhotoPage(
    vm: MainViewModel,
    asset: Asset,
    liveTrigger: Int,
    resetZoom: Int,
    onTap: () -> Unit,
    onZoomIn: () -> Unit,
    onLongPress: () -> Unit,
) {
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
                .data(vm.model(image))
                .memoryCacheKey(image.checksum)
                .diskCacheKey(image.checksum)
                .placeholderMemoryCacheKey(asset.thumbnail?.checksum)
                .build()
        }
        // Zoom far in; past the screen's resolution the full-size original is loaded in tiles.
        val zoomable = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
        val zoom = rememberZoomableImageState(zoomable)
        HideBarsWhenZoomed(zoomable, onZoomIn)
        // Live Text lines up with the photo as it fits the screen.
        LaunchedEffect(resetZoom) { if (resetZoom > 0) runCatching { zoomable.resetZoom() } }
        ZoomableAsyncImage(
            model = request,
            contentDescription = asset.filename,
            imageLoader = vm.imageLoader,
            state = zoom,
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
    val player = remember(trigger) {
        newPlayer(context, vm.http, vm.mediaUri(motion), muted = vm.settings.startMuted, cache = vm.mediaCache, cacheKey = motion.checksum).apply { playWhenReady = true }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) playing = false
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
    controlsBottom: Dp,
    editable: Boolean,
    onTrim: () -> Unit,
    onKeyTarget: (((Int) -> Boolean)?) -> Unit,
    onTap: () -> Unit,
    onZoomIn: () -> Unit,
) {
    val p = LocalPalette.current
    val s = vm.settings
    val context = LocalContext.current
    // GIFs play like videos (their original file). A video copy kept offline plays from the
    // device; otherwise it streams from iCloud (through the temporary cache).
    val gif = asset.isGif
    val video = if (gif) asset.original else asset.offlineVideo?.takeIf { vm.mediaUri(it).scheme == "file" } ?: asset.playbackVideo
    // Shown until the video's own first frame is up, then hidden so it never shows behind or around
    // the playing video. For a cropped video, only a still with the crop's shape (never the original).
    var pictureUp by remember(asset.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        val poster = if (gif) asset.medium ?: asset.thumbnail else asset.stillFor(video)
        if (poster != null && !(active && pictureUp)) {
            AsyncImage(
                model = remember(poster.checksum) {
                    ImageRequest.Builder(context).data(vm.model(poster)).memoryCacheKey(poster.checksum).diskCacheKey(poster.checksum)
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
            val player: Player = remember(asset.id) {
                if (gif) {
                    val uri = vm.mediaUri(video)
                    GifPlayer(video.checksum) { vm.mediaCache.readAll(uri, video.checksum) }
                        .apply { playWhenReady = true; prepare() }
                } else {
                    newPlayer(context, vm.http, vm.mediaUri(video), loop = s.loopVideos, muted = s.startMuted, cache = vm.mediaCache, cacheKey = video.checksum)
                        .apply { playWhenReady = s.autoplayVideos }
                }
            }
            DisposableEffect(player) { onDispose { player.release() } }
            DisposableEffect(player) {
                val l = object : Player.Listener {
                    override fun onRenderedFirstFrame() { pictureUp = true }
                }
                player.addListener(l)
                onDispose { player.removeListener(l); pictureUp = false }
            }
            // The frame strip and frame-by-frame buttons.
            val frames: FrameSource = remember(player) {
                player as? GifPlayer ?: VideoFrames(player as ExoPlayer, vm.mediaUri(video), video.checksum, video.size, vm.mediaCache)
            }
            // The picture's shape on screen, once the decoder reports it (used for picture-in-picture).
            var videoSize by remember(asset.id) {
                mutableStateOf(if (asset.width > 0 && asset.height > 0) Size(asset.width.toFloat(), asset.height.toFloat()) else Size(16f, 9f))
            }
            // Picture-in-picture while it plays; keyboard space / P / M.
            var playingNow by remember(asset.id) { mutableStateOf(false) }
            LaunchedEffect(player) {
                while (true) {
                    playingNow = player.isPlaying
                    delay(300)
                }
            }
            LaunchedEffect(playingNow, videoSize) {
                vm.pipCandidate(playingNow, videoSize.width.roundToInt(), videoSize.height.roundToInt())
            }
            DisposableEffect(asset.id) {
                onKeyTarget { code ->
                    when (code) {
                        KeyEvent.KEYCODE_SPACE -> {
                            if (player.playbackState == Player.STATE_ENDED) { player.seekTo(0); player.playWhenReady = true }
                            else player.playWhenReady = !player.playWhenReady
                            true
                        }
                        KeyEvent.KEYCODE_P -> { vm.enterPip(); true }
                        KeyEvent.KEYCODE_M -> { player.volume = if (player.volume > 0f) 0f else 1f; true }
                        else -> false
                    }
                }
                onDispose {
                    onKeyTarget(null)
                    vm.pipCandidate(false, 0, 0)
                }
            }
            // Pinch / double-tap to zoom the playing video, same gestures as photos.
            val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
            HideBarsWhenZoomed(zoom, onZoomIn)
            var texture by remember(asset.id) { mutableStateOf<TextureView?>(null) }
            // Always the whole video at rest, whatever its shape; pinch out returns here.
            FittedVideo(
                player = player,
                zoom = zoom,
                fallback = videoSize,
                onTap = onTap,
                onShape = { videoSize = it },
                onTexture = { texture = it },
            )
            VideoControls(
                vm = vm,
                player = player,
                visible = chrome,
                startMuted = s.startMuted && !gif,
                canTrim = editable && vm.canUpload && !gif,
                onTrim = onTrim,
                onSaveFrame = { toLibrary ->
                    val bmp = texture?.bitmap
                    if (bmp == null) vm.toast("The video isn't showing yet") else vm.saveFrame(asset, bmp, player.currentPosition, toLibrary)
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = controlsBottom + 10.dp),
                frames = frames,
                hasSound = !gif,
            )
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

/** Zooming in clears the bars away (like iPhone); tap to bring them back. */
@Composable
private fun HideBarsWhenZoomed(state: me.saket.telephoto.zoomable.ZoomableState, onZoomIn: () -> Unit) {
    val hide by rememberUpdatedState(onZoomIn)
    LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { (state.zoomFraction ?: 0f) > 0.02f }
            .collect { zoomed -> if (zoomed) hide() }
    }
}

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun speedLabel(x: Float) = (if (x == x.toInt().toFloat()) x.toInt().toString() else x.toString().trimEnd('0')) + "×"

@Composable
private fun VideoControls(
    vm: MainViewModel,
    player: Player,
    visible: Boolean,
    startMuted: Boolean,
    canTrim: Boolean,
    onTrim: () -> Unit,
    onSaveFrame: (toLibrary: Boolean) -> Unit,
    modifier: Modifier,
    frames: FrameSource? = null,
    hasSound: Boolean = true,
) {
    var speed by remember { mutableFloatStateOf(1f) }
    var speedMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(startMuted) }
    var scrub by remember { mutableFloatStateOf(-1f) }
    // Dragging the bar or the frame strip moves the picture with your finger, frame by frame.
    var resumeAfter by remember { mutableStateOf(false) }
    var lastSeek by remember { mutableLongStateOf(0L) }
    fun scrubTo(ms: Long) {
        if (scrub < 0f) {
            resumeAfter = player.playWhenReady
            player.playWhenReady = false
            frames?.exact()
        }
        scrub = if (duration > 0) (ms.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val now = System.currentTimeMillis()
        if (now - lastSeek > 45) { player.seekTo(ms); lastSeek = now }
    }
    fun scrubEnd() {
        if (scrub >= 0f && duration > 0) player.seekTo((scrub * duration).toLong())
        scrub = -1f
        if (resumeAfter) player.playWhenReady = true
        resumeAfter = false
    }
    fun step(by: Int) {
        player.playWhenReady = false
        frames?.exact()
        val from = player.currentPosition
        player.seekTo(frames?.step(from, by, duration) ?: (from + by * 33L).coerceIn(0L, duration.coerceAtLeast(0L)))
    }
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
        if (buffering && playing) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).padding(bottom = 200.dp).size(34.dp),
                color = Color.White, strokeWidth = 3.dp,
            )
        }
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier
                    .padding(horizontal = 14.dp)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
            if (frames != null && duration > 0) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!playing) {
                        IconButton(onClick = { step(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous frame", tint = Color.White) }
                    }
                    FrameStrip(
                        frames, duration,
                        positionMs = if (scrub >= 0) (scrub * duration).toLong() else position,
                        onScrub = { scrubTo(it) }, onScrubEnd = { scrubEnd() },
                        modifier = Modifier.weight(1f),
                    )
                    if (!playing) {
                        IconButton(onClick = { step(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next frame", tint = Color.White) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                    onValueChange = { scrubTo((it * duration).toLong()) },
                    onValueChangeFinished = { scrubEnd() },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f),
                )
                Text(formatDuration(duration / 1000.0).ifEmpty { "0:00" }, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
                Box {
                    TextButton(onClick = { speedMenu = true }) {
                        Text(speedLabel(speed), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        for (x in SPEEDS) {
                            DropdownMenuItem(
                                text = { Text((if (x == speed) "✓  " else "     ") + speedLabel(x)) },
                                onClick = { speedMenu = false; speed = x; player.setPlaybackSpeed(x) },
                            )
                        }
                    }
                }
                if (hasSound) {
                    IconButton(onClick = { muted = !muted; player.volume = if (muted) 0f else 1f }) {
                        Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Mute", tint = Color.White)
                    }
                }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreHoriz, "More", tint = Color.White) }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(
                            text = { Text("Picture in Picture") },
                            leadingIcon = { Icon(Icons.Outlined.PictureInPictureAlt, null) },
                            onClick = { more = false; player.playWhenReady = true; vm.enterPip() },
                        )
                        DropdownMenuItem(
                            text = { Text("Save Frame to Device") },
                            leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) },
                            onClick = { more = false; onSaveFrame(false) },
                        )
                        if (vm.canUpload) {
                            DropdownMenuItem(
                                text = { Text("Save Frame to Library") },
                                leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) },
                                onClick = { more = false; onSaveFrame(true) },
                            )
                        }
                        if (canTrim) {
                            DropdownMenuItem(
                                text = { Text("Trim…") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCut, null) },
                                onClick = { more = false; player.playWhenReady = false; onTrim() },
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun InfoSheet(vm: MainViewModel, asset: Asset, editable: Boolean) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var caption by remember(asset.id) { mutableStateOf(asset.caption ?: "") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
        if (editable) {
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                placeholder = { Text("Add a Caption") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (caption.trim() != (asset.caption ?: "")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.setCaption(asset, caption) }) { Text("Save Caption") }
                }
            }
            Spacer(Modifier.height(10.dp))
        } else if (!asset.caption.isNullOrBlank()) {
            Text(asset.caption, color = p.label, fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
        }
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
        val lat = asset.latitude
        val lon = asset.longitude
        if (lat != null && lon != null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("Location", color = p.secondaryLabel, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, null, tint = p.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    val place = remember(lat, lon) { vm.placeLabel(lat, lon) }
                    Column(Modifier.weight(1f)) {
                        if (place != null) Text(place, color = p.label, fontSize = 16.sp)
                        Text("%.5f, %.5f".format(lat, lon), color = if (place != null) p.secondaryLabel else p.label, fontSize = if (place != null) 13.sp else 16.sp)
                    }
                    TextButton(onClick = {
                        vm.expectingExternal = true
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")))
                        }.onFailure { vm.toast("No maps app installed") }
                    }) { Text("Open in Maps") }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(thickness = 0.5.dp, color = p.separator)
            }
        }
        val names = remember(asset.id) { vm.namesIn(asset.id) }
        if (names.isNotEmpty()) InfoLine("People", names.joinToString(", "))
        if (asset.isEdited) InfoLine("Edited", "Yes — sharing and saving use the edited version")
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
