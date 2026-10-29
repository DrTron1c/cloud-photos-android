package io.github.drtron1c.cloudphotos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.drtron1c.cloudphotos.photos.AlbumSpec
import io.github.drtron1c.cloudphotos.photos.MediaList

private val VIEWS = listOf("years" to "Years", "months" to "Months", "days" to "Days", "all" to "All Photos")
private val FILTERS = listOf("all" to "All Items", "favourites" to "Favourites", "edited" to "Edited", "photos" to "Photos", "videos" to "Videos")

@Composable
fun LibraryTab(vm: MainViewModel, onUploadClick: () -> Unit) {
    val s = vm.settings
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val view = if (vm.offlineMode) "all" else s.libraryView
    GlassHost(Modifier.fillMaxSize(), content = {
        when (view) {
            "years" -> PeriodsView(vm, byYear = true)
            "months" -> PeriodsView(vm, byYear = false)
            "days" -> DaysView(vm)
            else -> AllPhotosView(vm, onUploadClick)
        }
    }) {
        if (!vm.librarySelecting && !vm.offlineMode) {
            ViewSwitcher(
                selected = view,
                onSelect = { s.libraryView = it },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = navBottom + (if (LocalNoTabBar.current) 0.dp else TAB_BAR_HEIGHT.dp) + if (LocalGlassEnabled.current) 22.dp else 10.dp),
            )
        }
    }
}

@Composable
private fun FilterPill(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    val current = vm.settings.libraryFilter
    Box {
        Pill(icon = Icons.Outlined.FilterList, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((key, title) in FILTERS) {
                DropdownMenuItem(
                    text = { Text(if (key == current) "✓  $title" else "     $title") },
                    onClick = { open = false; vm.settings.libraryFilter = key },
                )
            }
        }
    }
    Spacer(Modifier.width(8.dp))
}

@Composable
private fun AllPhotosView(vm: MainViewModel, onUploadClick: () -> Unit) {
    val filter = vm.settings.libraryFilter
    val list: MediaList? = if (filter == "all" || vm.offlineMode) vm.list(AlbumSpec.LIBRARY)
    else remember(filter, vm.index.entries, vm.indexMatchesLibrary, vm.settings.newestOnTop) { vm.filteredLibrary(filter) }
    if (list == null) {
        IndexProgress(vm) { vm.settings.libraryFilter = "all" }
        return
    }
    PhotoGrid(
        vm = vm,
        list = list,
        isRoot = true,
        onBack = null,
        extraBottom = if (vm.offlineMode) 0.dp else 56.dp,
        headerActions = { if (!vm.offlineMode) FilterPill(vm) },
        onSelectingChanged = { vm.librarySelecting = it },
        onUploadClick = onUploadClick,
    )
}

@Composable
private fun DaysView(vm: MainViewModel) {
    val ready = vm.indexMatchesLibrary
    val sections = remember(ready, vm.index.entries, vm.settings.newestOnTop) { if (ready) vm.daySections() else null }
    if (sections == null) {
        IndexProgress(vm, null)
        return
    }
    val jump = vm.pendingJump?.takeIf { it.first == "days" }?.second
    val jumpEntry = remember(jump, sections) {
        jump?.let { target ->
            val i = sections.indexOfFirst { it is GridEntry.Item && it.index == target }
            if (i > 0 && sections[i - 1] is GridEntry.Header) i - 1 else i
        }
    }
    LaunchedEffect(jump) { if (jump != null) vm.pendingJump = null }
    PhotoGrid(
        vm = vm,
        list = vm.list(AlbumSpec.LIBRARY),
        isRoot = true,
        onBack = null,
        sections = sections,
        extraBottom = 56.dp,
        onSelectingChanged = { vm.librarySelecting = it },
        jumpTo = jumpEntry?.takeIf { it >= 0 },
    )
}

@Composable
private fun PeriodsView(vm: MainViewModel, byYear: Boolean) {
    val p = LocalPalette.current
    val ready = vm.indexMatchesLibrary
    val periods = remember(ready, vm.index.entries, vm.settings.newestOnTop, byYear) { if (ready) vm.periods(byYear) else null }
    if (periods == null) {
        IndexProgress(vm, null)
        return
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val wide = contentWidthDp() >= 600
    val newestTop = vm.settings.newestOnTop
    val jump = vm.pendingJump?.takeIf { it.first == "months" && !byYear }?.second
    val startIndex = remember(periods, jump) {
        if (jump != null) periods.indexOfFirst { jump in it.first..it.last }.coerceAtLeast(0)
        else if (newestTop) 0 else (periods.size - 1).coerceAtLeast(0)
    }
    LaunchedEffect(jump) { if (jump != null) vm.pendingJump = null }
    val state = remember(byYear, periods.size, startIndex) { LazyGridState(firstVisibleItemIndex = startIndex) }
    val library = vm.list(AlbumSpec.LIBRARY)

    Box(Modifier.fillMaxSize().background(p.background)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (byYear) (if (wide) 420.dp else 320.dp) else (if (wide) 300.dp else 260.dp)),
            state = state,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = top + 70.dp, bottom = navBottom + TAB_BAR_HEIGHT.dp + 80.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(periods, key = { _, it -> it.key }) { _, period ->
                PeriodCard(vm, library, period, byYear) {
                    if (byYear) {
                        vm.pendingJump = "months" to period.first
                        vm.settings.libraryView = "months"
                    } else {
                        vm.pendingJump = "days" to period.first
                        vm.settings.libraryView = "days"
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = top + 8.dp, start = 18.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (byYear) "Years" else "Months", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = p.label, modifier = Modifier.weight(1f))
            Pill(icon = Icons.Filled.Settings, onDark = false, onClick = { vm.open(Route.Settings) })
        }
    }
}

@Composable
private fun PeriodCard(vm: MainViewModel, library: MediaList, period: Period, byYear: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val cover = library[period.cover]
    LaunchedEffect(period.cover, cover == null) { library.ensure(period.cover) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (byYear) 1.25f else 1.5f)
            .shadow(3.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(p.placeholder)
            .clickable(onClick = onClick)
    ) {
        val req = cover?.let { asset ->
            val v = asset.medium ?: asset.thumbnail
            v?.let {
                coil3.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(vm.model(it)).memoryCacheKey(it.checksum).diskCacheKey(it.checksum)
                    .placeholderMemoryCacheKey(asset.thumbnail?.checksum)
                    .build()
            }
        }
        if (req != null) {
            AsyncImage(
                model = req,
                contentDescription = period.title,
                imageLoader = vm.imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Black.copy(alpha = 0.25f)))
            )
        )
        Column(Modifier.padding(14.dp)) {
            Text(period.title, color = Color.White, fontSize = if (byYear) 30.sp else 22.sp, fontWeight = FontWeight.Bold)
            Text(period.subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ViewSwitcher(selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    val glass = LocalGlassEnabled.current
    val p = LocalPalette.current
    if (glass) {
        GlassSurface(modifier, cornerRadius = null, elevation = 8.dp) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((key, title) in VIEWS) {
                    val on = key == selected
                    Text(
                        title,
                        color = if (on) p.accent else p.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) p.accent.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { onSelect(key) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        return
    }
    Row(
        modifier
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xE6202022))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((key, title) in VIEWS) {
            val on = key == selected
            Text(
                title,
                color = if (on) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) Color.White else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Shown while the on-device index (dates, names…) is still being built. */
@Composable
fun IndexProgress(vm: MainViewModel, onCancel: (() -> Unit)?) {
    val p = LocalPalette.current
    val idx = vm.index
    Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp).padding(28.dp)) {
            if (idx.error != null) {
                Text("Couldn't organise your library", color = p.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(idx.error ?: "", color = p.secondaryLabel, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { vm.refreshAll() }) { Text("Try Again") }
            } else {
                CircularProgressIndicator(
                    progress = { idx.progress.coerceIn(0.02f, 1f) },
                    modifier = Modifier.size(44.dp),
                    strokeWidth = 4.dp,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Organising your library… ${(idx.progress * 100).toInt()}%",
                    color = p.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Years, Months, Days, search and filters need each photo's date and name. This happens once, then stays up to date — kept encrypted on this device.",
                    color = p.secondaryLabel, textAlign = TextAlign.Center, fontSize = 14.sp,
                )
            }
            if (onCancel != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onCancel) { Text("Show All Photos") }
            }
        }
    }
}
