package io.github.drtron1c.cloudphotos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.drtron1c.cloudphotos.photos.AlbumKind
import io.github.drtron1c.cloudphotos.photos.Asset
import io.github.drtron1c.cloudphotos.photos.MediaList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun gridColumnOptions(widthDp: Int): List<Int> =
    if (widthDp >= 600) listOf(4, 6, 9, 13, 18) else listOf(3, 5, 7, 11)

const val TAB_BAR_HEIGHT = 56

/** Thumbnail request keyed by content checksum, so it's cached across sessions. */
@Composable
fun thumbRequest(vm: MainViewModel, asset: Asset): ImageRequest? {
    val context = LocalContext.current
    val thumb = asset.thumbnail ?: return null
    return remember(thumb.checksum) {
        ImageRequest.Builder(context)
            .data(vm.model(thumb))
            .memoryCacheKey(thumb.checksum)
            .diskCacheKey(thumb.checksum)
            .build()
    }
}

/**
 * The photo grid used by Library, Days view and every album: pinch to change density,
 * tap to open, long-press (and drag) or Select to pick, swipe across cells to select runs.
 */
@Composable
fun PhotoGrid(
    vm: MainViewModel,
    list: MediaList,
    isRoot: Boolean,
    onBack: (() -> Unit)?,
    title: String = list.spec.title,
    sections: List<GridEntry>? = null,
    extraBottom: Dp = 0.dp,
    headerActions: @Composable RowScope.() -> Unit = {},
    onSelectingChanged: (Boolean) -> Unit = {},
    onUploadClick: (() -> Unit)? = null,
    jumpTo: Int? = null,
) {
    val p = LocalPalette.current
    val s = vm.settings
    val count = list.count
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val options = gridColumnOptions(widthDp)
    val level = s.gridLevel.coerceIn(0, options.lastIndex)
    val columns = options[level]
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomReserve = navBottom + (if (isRoot) TAB_BAR_HEIGHT.dp else 0.dp) + extraBottom
    val editable = list.spec.editable && !vm.offlineMode

    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Asset>() }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var addToAlbum by remember { mutableStateOf<List<Asset>?>(null) }
    fun endSelection() {
        selecting = false
        selected.clear()
        onSelectingChanged(false)
    }
    LaunchedEffect(selecting) { onSelectingChanged(selecting) }

    Box(Modifier.fillMaxSize().background(p.background)) {
        when {
            count < 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
            count == 0 -> EmptyAlbum(list)
            else -> {
                val entryCount = sections?.size ?: count
                val newestTop = s.newestOnTop
                val state = rememberSaveable(list.key, sections != null, saver = LazyGridState.Saver) {
                    LazyGridState(firstVisibleItemIndex = jumpTo ?: if (newestTop) 0 else (entryCount - 1).coerceAtLeast(0))
                }
                if (isRoot) {
                    LaunchedEffect(vm.scrollToNewest) {
                        if (vm.scrollToNewest > 0) state.animateScrollToItem(if (newestTop) 0 else (entryCount - 1).coerceAtLeast(0))
                    }
                }
                LaunchedEffect(jumpTo) { if (jumpTo != null) state.scrollToItem(jumpTo.coerceIn(0, (entryCount - 1).coerceAtLeast(0))) }

                // Coming back from the viewer: make sure the photo you ended on is on screen.
                LaunchedEffect(vm.lastViewed) {
                    val lv = vm.lastViewed ?: return@LaunchedEffect
                    if (lv.first != list.key) return@LaunchedEffect
                    val target = if (sections == null) lv.second
                    else sections.indexOfFirst { it is GridEntry.Item && it.index == lv.second }
                    val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
                    if (target >= 0 && target !in visible) state.scrollToItem(target.coerceIn(0, (entryCount - 1).coerceAtLeast(0)))
                    vm.lastViewed = null
                }

                // ---- swipe / long-press-drag selection ----
                var dragAnchor by remember { mutableIntStateOf(-1) }
                var dragAdds by remember { mutableStateOf(true) }
                var dragBase by remember { mutableStateOf<Map<String, Asset>>(emptyMap()) }
                var lastPointer by remember { mutableStateOf(Offset.Unspecified) }
                var gridHeight by remember { mutableIntStateOf(0) }
                val density = LocalDensity.current

                fun keyAt(pos: Offset): Int? {
                    val info = state.layoutInfo
                    val y = pos.y.roundToInt() + info.viewportStartOffset
                    val x = pos.x.roundToInt()
                    return info.visibleItemsInfo.firstOrNull {
                        it.key is Int && x >= it.offset.x && x < it.offset.x + it.size.width &&
                            y >= it.offset.y && y < it.offset.y + it.size.height
                    }?.key as? Int
                }

                fun applyDrag(to: Int) {
                    if (dragAnchor < 0) return
                    val next = HashMap(dragBase)
                    for (i in min(dragAnchor, to)..max(dragAnchor, to)) {
                        val a = list[i] ?: continue
                        if (dragAdds) next[a.id] = a else next.remove(a.id)
                    }
                    selected.clear()
                    selected.putAll(next)
                }

                fun beginDrag(pos: Offset): Boolean {
                    val k = keyAt(pos) ?: return false
                    val a = list[k] ?: return false
                    dragBase = selected.toMap()
                    dragAdds = !selected.containsKey(a.id)
                    dragAnchor = k
                    lastPointer = pos
                    applyDrag(k)
                    return true
                }

                fun endDrag() {
                    dragAnchor = -1
                    lastPointer = Offset.Unspecified
                }

                LaunchedEffect(dragAnchor >= 0) {
                    val edge = with(density) { 90.dp.toPx() }
                    while (dragAnchor >= 0) {
                        val pt = lastPointer
                        if (pt != Offset.Unspecified && gridHeight > 0) {
                            val delta = when {
                                pt.y < edge -> -(edge - pt.y) / 4f
                                pt.y > gridHeight - edge -> (pt.y - (gridHeight - edge)) / 4f
                                else -> 0f
                            }
                            if (delta != 0f) {
                                state.scrollBy(delta)
                                keyAt(pt)?.let { applyDrag(it) }
                            }
                        }
                        delay(16)
                    }
                }

                val levelNow by rememberUpdatedState(level)
                val selectingNow by rememberUpdatedState(selecting)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = state,
                    horizontalArrangement = Arrangement.spacedBy(if (columns >= 9) 1.dp else 2.dp),
                    verticalArrangement = Arrangement.spacedBy(if (columns >= 9) 1.dp else 2.dp),
                    contentPadding = PaddingValues(top = statusTop + 64.dp, bottom = bottomReserve + 12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { gridHeight = it.height }
                        .pointerInput(options.size) {
                            // Pinch to change how many photos fit across, like iPhone.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var zoom = 1f
                                var pinching = false
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.count { it.pressed } >= 2) {
                                        pinching = true
                                        zoom *= event.calculateZoom()
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                                if (pinching) {
                                    if (zoom > 1.2f && levelNow > 0) vm.changeGridLevel(levelNow - 1)
                                    else if (zoom < 0.83f && levelNow < options.lastIndex) vm.changeGridLevel(levelNow + 1)
                                }
                            }
                        }
                        .pointerInput(list.key) {
                            // Long-press a photo to start selecting, keep dragging to select more.
                            detectDragGesturesAfterLongPress(
                                onDragStart = { pos ->
                                    if (!selectingNow) selecting = true
                                    if (beginDrag(pos) && s.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    lastPointer = change.position
                                    keyAt(change.position)?.let { applyDrag(it) }
                                },
                                onDragEnd = { endDrag() },
                                onDragCancel = { endDrag() },
                            )
                        }
                        .then(
                            if (selecting) Modifier.pointerInput("swipe-" + list.key) {
                                // In Select mode, swipe sideways across photos to select a run (iPhone style).
                                detectHorizontalDragGestures(
                                    onDragStart = { pos -> beginDrag(pos) },
                                    onDragEnd = { endDrag() },
                                    onDragCancel = { endDrag() },
                                ) { change, _ ->
                                    change.consume()
                                    lastPointer = change.position
                                    keyAt(change.position)?.let { applyDrag(it) }
                                }
                            } else Modifier
                        ),
                ) {
                    if (sections == null) {
                        items(count = count, key = { it }) { index ->
                            val asset = list[index]
                            LaunchedEffect(index, asset == null) { list.ensure(index) }
                            GridCell(
                                vm = vm,
                                asset = asset,
                                dense = columns >= 9,
                                selecting = selecting,
                                isSelected = asset != null && selected.containsKey(asset.id),
                                onClick = {
                                    if (asset == null) return@GridCell
                                    if (selecting) {
                                        if (selected.containsKey(asset.id)) selected.remove(asset.id) else selected[asset.id] = asset
                                    } else {
                                        vm.open(Route.Viewer(list.key, index))
                                    }
                                },
                            )
                        }
                    } else {
                        items(
                            count = sections.size,
                            key = { i ->
                                when (val e = sections[i]) {
                                    is GridEntry.Header -> e.key
                                    is GridEntry.Item -> e.index
                                }
                            },
                            span = { i -> if (sections[i] is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                        ) { i ->
                            when (val e = sections[i]) {
                                is GridEntry.Header -> DayHeader(e)
                                is GridEntry.Item -> {
                                    val asset = list[e.index]
                                    LaunchedEffect(e.index, asset == null) { list.ensure(e.index) }
                                    GridCell(
                                        vm = vm,
                                        asset = asset,
                                        dense = columns >= 9,
                                        selecting = selecting,
                                        isSelected = asset != null && selected.containsKey(asset.id),
                                        onClick = {
                                            if (asset == null) return@GridCell
                                            if (selecting) {
                                                if (selected.containsKey(asset.id)) selected.remove(asset.id) else selected[asset.id] = asset
                                            } else {
                                                vm.open(Route.Viewer(list.key, e.index))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { FooterCount(list) }
                }

                Scrubber(
                    state = state,
                    count = entryCount,
                    label = { i ->
                        val idx = if (sections == null) i else (sections.getOrNull(i) as? GridEntry.Item)?.index
                        idx?.let { list[it] }?.let { formatMonth(it.date) }
                    },
                    top = statusTop + 70.dp,
                    bottom = bottomReserve + 16.dp,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )

                // Header: date of what's on screen (Library) or the album title.
                val firstDate by remember(state, sections) {
                    derivedStateOf {
                        val first = state.firstVisibleItemIndex
                        val idx = if (sections == null) first else {
                            var k = first
                            while (k < sections.size && sections[k] !is GridEntry.Item) k++
                            (sections.getOrNull(k) as? GridEntry.Item)?.index ?: -1
                        }
                        list[idx]?.date ?: 0L
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(statusTop + 96.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.18f), Color.Transparent)
                            )
                        )
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = statusTop + 8.dp, start = 14.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        Pill(icon = Icons.AutoMirrored.Filled.ArrowBackIos, onClick = onBack)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        val heading = if (isRoot) {
                            if (columns >= 9) formatMonth(firstDate) else formatShortDay(firstDate)
                        } else title
                        Text(
                            heading.ifEmpty { title },
                            color = Color.White,
                            fontSize = if (isRoot) 26.sp else 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.4f), blurRadius = 6f)),
                        )
                        if (!isRoot) {
                            Text(
                                formatMonth(firstDate),
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 14.sp,
                                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.4f), blurRadius = 6f)),
                            )
                        }
                    }
                    if (selecting) {
                        Pill(text = "Cancel", onClick = { endSelection() })
                    } else {
                        if (isRoot && onUploadClick != null && vm.canUpload) {
                            Pill(icon = Icons.Filled.Add, onClick = onUploadClick)
                            Spacer(Modifier.width(8.dp))
                        }
                        headerActions()
                        Pill(text = "Select", onClick = { selecting = true })
                        if (isRoot) {
                            Spacer(Modifier.width(8.dp))
                            Pill(icon = Icons.Filled.Settings, onClick = { vm.open(Route.Settings) })
                        }
                    }
                }
            }
        }

        if (count == 0 && onBack != null) {
            Row(Modifier.padding(top = statusTop + 8.dp, start = 14.dp)) {
                Pill(icon = Icons.AutoMirrored.Filled.ArrowBackIos, onDark = false, onClick = onBack)
            }
        }

        // Selection toolbar replaces the tab bar.
        AnimatedVisibility(
            visible = selecting,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val chosen = selected.values.toList()
            val tint = if (chosen.isNotEmpty()) p.accent else p.secondaryLabel
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(p.bar)
                    .padding(bottom = navBottom)
                    .height(TAB_BAR_HEIGHT.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    list.spec.kind == AlbumKind.Deleted -> {
                        TextButton(onClick = { vm.recover(chosen) { endSelection() } }, enabled = chosen.isNotEmpty()) {
                            Text("Recover", fontSize = 16.sp)
                        }
                        SelectedLabel(chosen.size, Modifier.weight(1f))
                        Spacer(Modifier.width(80.dp))
                    }
                    vm.offlineMode -> {
                        SelectedLabel(chosen.size, Modifier.weight(1f))
                    }
                    else -> {
                        IconButton(onClick = { vm.share(chosen) { context.startActivity(it) } }, enabled = chosen.isNotEmpty()) {
                            Icon(Icons.Outlined.Share, "Share", tint = tint)
                        }
                        SelectedLabel(chosen.size, Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }, enabled = chosen.isNotEmpty()) {
                                Icon(Icons.Outlined.MoreHoriz, "More", tint = tint)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (editable) {
                                    val allFav = chosen.all { it.isFavorite }
                                    DropdownMenuItem(
                                        text = { Text(if (allFav) "Unfavourite" else "Favourite") },
                                        leadingIcon = { Icon(if (allFav) Icons.Outlined.FavoriteBorder else Icons.Filled.Favorite, null) },
                                        onClick = { menu = false; vm.setFavorite(chosen, !allFav) { endSelection() } },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add to Album") },
                                        leadingIcon = { Icon(Icons.Outlined.Collections, null) },
                                        onClick = { menu = false; addToAlbum = chosen },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Save to Device") },
                                    leadingIcon = { Icon(Icons.Outlined.Download, null) },
                                    onClick = { menu = false; vm.saveToDevice(chosen); endSelection() },
                                )
                                if (editable) {
                                    if (list.spec.kind == AlbumKind.Hidden) {
                                        DropdownMenuItem(
                                            text = { Text("Unhide") },
                                            leadingIcon = { Icon(Icons.Outlined.Visibility, null) },
                                            onClick = { menu = false; vm.setHidden(chosen, false) { endSelection() } },
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text("Hide") },
                                            leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) },
                                            onClick = { menu = false; vm.setHidden(chosen, true) { endSelection() } },
                                        )
                                    }
                                }
                            }
                        }
                        if (editable) {
                            IconButton(onClick = {
                                if (s.confirmDelete) confirmDelete = true else vm.delete(chosen) { endSelection() }
                            }, enabled = chosen.isNotEmpty()) {
                                Icon(Icons.Outlined.Delete, "Delete", tint = tint)
                            }
                        } else {
                            Spacer(Modifier.width(48.dp))
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (n == 1) "Delete this item?" else "Delete $n items?") },
            text = { Text("They'll be removed from iCloud Photos on all your devices and kept in Recently Deleted for 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(selected.values.toList()) { endSelection() }
                }) { Text("Delete", color = p.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    addToAlbum?.let { assets ->
        AddToAlbumDialog(vm, assets, onDismiss = { addToAlbum = null }, onDone = { addToAlbum = null; endSelection() })
    }
}

@Composable
private fun DayHeader(e: GridEntry.Header) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 6.dp)) {
        Text(e.title, color = p.label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (e.subtitle != null) Text(e.subtitle, color = p.secondaryLabel, fontSize = 13.sp)
    }
}

@Composable
private fun SelectedLabel(n: Int, modifier: Modifier) {
    val p = LocalPalette.current
    Text(
        if (n == 0) "Select Items" else if (n == 1) "1 Item Selected" else "$n Items Selected",
        modifier = modifier,
        color = p.label,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun GridCell(
    vm: MainViewModel,
    asset: Asset?,
    dense: Boolean,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val s = vm.settings
    Box(
        Modifier
            .aspectRatio(1f)
            .background(if (s.aspectGrid) p.background else p.placeholder)
            .clickable(onClick = onClick)
    ) {
        if (asset != null) {
            val req = thumbRequest(vm, asset)
            if (req != null) {
                AsyncImage(
                    model = req,
                    contentDescription = asset.filename,
                    imageLoader = vm.imageLoader,
                    contentScale = if (s.aspectGrid) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Outlined.Image, null, tint = p.secondaryLabel, modifier = Modifier.align(Alignment.Center))
            }
            if (!dense) {
                val shadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 4f))
                if (asset.isVideo && s.showDurations) {
                    Text(
                        formatDuration(asset.durationSec).ifEmpty { "▶" },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = shadow,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
                    )
                }
                if (asset.isFavorite && !selecting && s.showFavouriteBadges) {
                    Icon(
                        Icons.Filled.Favorite, null, tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(14.dp)
                            .shadow(2.dp, CircleShape, clip = false),
                    )
                }
            }
            if (selecting) {
                if (isSelected) {
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.25f)))
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(5.dp).size(22.dp)
                            .background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.CheckCircle, "Selected", tint = p.accent, modifier = Modifier.size(22.dp))
                    }
                } else {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(5.dp).size(22.dp)
                            .border(1.5.dp, Color.White, CircleShape)
                            .background(Color.Black.copy(alpha = 0.15f), CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun FooterCount(list: MediaList) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        val n = list.count
        Text(
            if (n == 1) "1 Item" else "%,d Items".format(n),
            color = p.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyAlbum(list: MediaList) {
    val p = LocalPalette.current
    val spec = list.spec
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(iconFor(spec), null, tint = p.secondaryLabel, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(14.dp))
            Text("No Photos or Videos", color = p.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                when (spec.kind) {
                    AlbumKind.Deleted -> "Items you delete stay here for 30 days."
                    AlbumKind.Hidden -> "Items you hide will appear here."
                    AlbumKind.Virtual -> "Nothing matches."
                    else -> "Nothing in ${spec.title} yet."
                },
                color = p.secondaryLabel,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { list.refresh() }) { Text("Refresh") }
        }
    }
}

/** iOS-style fast-scroll handle that appears while scrolling; drag it to fly through years. */
@Composable
private fun Scrubber(
    state: LazyGridState,
    count: Int,
    label: (Int) -> String?,
    top: Dp,
    bottom: Dp,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) recentlyScrolled = true
        else {
            delay(1400); recentlyScrolled = false
        }
    }
    val visible = recentlyScrolled && count > 200
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "scrubber")
    if (alpha <= 0.01f && !visible) return

    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .width(120.dp)
            .padding(top = top, bottom = bottom)
            .alpha(alpha)
    ) {
        val density = LocalDensity.current
        val thumbH = with(density) { 44.dp.toPx() }
        val track = (constraints.maxHeight - thumbH).coerceAtLeast(1f)
        var dragY by remember { mutableFloatStateOf(0f) }
        val fraction = if (count > 1) state.firstVisibleItemIndex / (count - 1).toFloat() else 0f
        val y = if (dragging) dragY else fraction * track
        val currentIndex = ((y / track) * (count - 1)).roundToInt().coerceIn(0, count - 1)

        if (dragging) {
            val text = label(currentIndex) ?: ""
            if (text.isNotEmpty()) {
                Text(
                    text,
                    color = p.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-with(density) { 52.dp.roundToPx() }, (y + thumbH / 4).roundToInt()) }
                        .background(p.card, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, y.roundToInt()) }
                .padding(end = 4.dp)
                .size(width = 36.dp, height = 44.dp)
                .shadow(4.dp, RoundedCornerShape(18.dp))
                .background(p.card, RoundedCornerShape(18.dp))
                .pointerInput(count, track) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragY = (if (count > 1) state.firstVisibleItemIndex / (count - 1).toFloat() else 0f) * track
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, amount ->
                        change.consume()
                        dragY = (dragY + amount).coerceIn(0f, track)
                        val target = ((dragY / track) * (count - 1)).roundToInt().coerceIn(0, count - 1)
                        scope.launch { state.scrollToItem(target) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("⇅", color = p.secondaryLabel, fontSize = 16.sp)
        }
    }
}
