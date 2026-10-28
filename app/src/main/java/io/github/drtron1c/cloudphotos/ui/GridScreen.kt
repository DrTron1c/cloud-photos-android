package io.github.drtron1c.cloudphotos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.drtron1c.cloudphotos.photos.AlbumKind
import io.github.drtron1c.cloudphotos.photos.AlbumSpec
import io.github.drtron1c.cloudphotos.photos.Asset
import io.github.drtron1c.cloudphotos.photos.AssetList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

fun gridColumnOptions(widthDp: Int): List<Int> =
    if (widthDp >= 600) listOf(4, 6, 9, 13, 18) else listOf(3, 5, 7, 11)

const val TAB_BAR_HEIGHT = 56

/**
 * The photo grid used by Library and every album: oldest at the top, opens scrolled to the
 * newest at the bottom, pinch to change density, tap to open, long-press or Select to pick.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoGrid(
    vm: MainViewModel,
    spec: AlbumSpec,
    isRoot: Boolean,
    onBack: (() -> Unit)?,
    onSelectingChanged: (Boolean) -> Unit = {},
    onUploadClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val list = vm.list(spec)
    val count = list.count
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val options = gridColumnOptions(widthDp)
    val level = vm.gridLevel.coerceIn(0, options.lastIndex)
    val columns = options[level]
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomReserve = navBottom + (if (isRoot) TAB_BAR_HEIGHT.dp else 0.dp)

    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Asset>() }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
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
            count == 0 -> EmptyAlbum(spec, list)
            else -> {
                val state = rememberSaveable(spec.key, vm.zone, saver = LazyGridState.Saver) {
                    LazyGridState(firstVisibleItemIndex = (count - 1).coerceAtLeast(0))
                }
                if (isRoot) {
                    LaunchedEffect(vm.scrollToNewest) {
                        if (vm.scrollToNewest > 0 && list.count > 0) state.animateScrollToItem(list.count - 1)
                    }
                }

                // Coming back from the viewer: make sure the photo you ended on is on screen.
                LaunchedEffect(vm.lastViewed) {
                    val lv = vm.lastViewed ?: return@LaunchedEffect
                    if (lv.first != spec.key) return@LaunchedEffect
                    val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
                    if (lv.second !in visible) state.scrollToItem(lv.second.coerceIn(0, (list.count - 1).coerceAtLeast(0)))
                    vm.lastViewed = null
                }

                val levelNow by rememberUpdatedState(level)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = state,
                    horizontalArrangement = Arrangement.spacedBy(if (columns >= 9) 1.dp else 2.dp),
                    verticalArrangement = Arrangement.spacedBy(if (columns >= 9) 1.dp else 2.dp),
                    contentPadding = PaddingValues(top = statusTop + 64.dp, bottom = bottomReserve + 12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(options.size) {
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
                                    if (zoom > 1.2f && levelNow > 0) vm.setGridLevel(levelNow - 1)
                                    else if (zoom < 0.83f && levelNow < options.lastIndex) vm.setGridLevel(levelNow + 1)
                                }
                            }
                        },
                ) {
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
                                    vm.open(Route.Viewer(spec, index))
                                }
                            },
                            onLongClick = {
                                if (asset == null) return@GridCell
                                selecting = true
                                selected[asset.id] = asset
                            },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FooterCount(list)
                    }
                }

                Scrubber(
                    state = state,
                    count = count,
                    label = { i -> list[i]?.let { formatMonth(it.date) } },
                    top = statusTop + 70.dp,
                    bottom = bottomReserve + 16.dp,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )

                // Header: date of what's on screen (Library) or the album title.
                val firstDate by remember(state) {
                    derivedStateOf { list[state.firstVisibleItemIndex]?.date ?: 0L }
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
                        val title = if (isRoot) {
                            if (columns >= 9) formatMonth(firstDate) else formatShortDay(firstDate)
                        } else spec.title
                        Text(
                            title.ifEmpty { spec.title },
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(p.bar)
                    .padding(bottom = navBottom)
                    .height(TAB_BAR_HEIGHT.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (spec.kind) {
                    AlbumKind.Deleted -> {
                        TextButton(onClick = { vm.recover(chosen) { endSelection() } }, enabled = chosen.isNotEmpty()) {
                            Text("Recover", fontSize = 16.sp)
                        }
                        SelectedLabel(chosen.size, Modifier.weight(1f))
                        Spacer(Modifier.width(80.dp))
                    }
                    else -> {
                        IconButton(onClick = { vm.share(chosen) { context.startActivity(it) } }, enabled = chosen.isNotEmpty()) {
                            Icon(Icons.Outlined.Share, "Share", tint = if (chosen.isNotEmpty()) p.accent else p.secondaryLabel)
                        }
                        SelectedLabel(chosen.size, Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }, enabled = chosen.isNotEmpty()) {
                                Icon(Icons.Outlined.MoreHoriz, "More", tint = if (chosen.isNotEmpty()) p.accent else p.secondaryLabel)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                val allFav = chosen.all { it.isFavorite }
                                DropdownMenuItem(
                                    text = { Text(if (allFav) "Unfavourite" else "Favourite") },
                                    leadingIcon = { Icon(if (allFav) Icons.Outlined.FavoriteBorder else Icons.Filled.Favorite, null) },
                                    onClick = { menu = false; vm.setFavorite(chosen, !allFav) { endSelection() } },
                                )
                                DropdownMenuItem(
                                    text = { Text("Save to Device") },
                                    leadingIcon = { Icon(Icons.Outlined.Download, null) },
                                    onClick = { menu = false; vm.saveToDevice(chosen); endSelection() },
                                )
                                if (spec.kind == AlbumKind.Hidden) {
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
                        IconButton(onClick = { confirmDelete = true }, enabled = chosen.isNotEmpty()) {
                            Icon(Icons.Outlined.Delete, "Delete", tint = if (chosen.isNotEmpty()) p.accent else p.secondaryLabel)
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
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    vm: MainViewModel,
    asset: Asset?,
    dense: Boolean,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Box(
        Modifier
            .aspectRatio(1f)
            .background(p.placeholder)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (asset != null) {
            val thumb = asset.thumbnail
            if (thumb != null) {
                val req = remember(thumb.checksum) {
                    ImageRequest.Builder(context)
                        .data(thumb.url)
                        .memoryCacheKey(thumb.checksum)
                        .diskCacheKey(thumb.checksum)
                        .build()
                }
                AsyncImage(
                    model = req,
                    contentDescription = asset.filename,
                    imageLoader = vm.imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Outlined.Image, null, tint = p.secondaryLabel, modifier = Modifier.align(Alignment.Center))
            }
            if (!dense) {
                val shadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 4f))
                if (asset.isVideo) {
                    Text(
                        formatDuration(asset.durationSec).ifEmpty { "▶" },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = shadow,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
                    )
                }
                if (asset.isFavorite && !selecting) {
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
private fun FooterCount(list: AssetList) {
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
private fun EmptyAlbum(spec: AlbumSpec, list: AssetList) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(
                if (spec.kind == AlbumKind.Library) Icons.Outlined.Image else Icons.Filled.MotionPhotosOn.takeIf { spec.key == "live" } ?: Icons.Outlined.Image,
                null, tint = p.secondaryLabel, modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text("No Photos or Videos", color = p.label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                when (spec.kind) {
                    AlbumKind.Deleted -> "Items you delete stay here for 30 days."
                    AlbumKind.Hidden -> "Items you hide will appear here."
                    else -> "Nothing in ${spec.title} yet."
                },
                color = p.secondaryLabel,
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
    top: androidx.compose.ui.unit.Dp,
    bottom: androidx.compose.ui.unit.Dp,
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
