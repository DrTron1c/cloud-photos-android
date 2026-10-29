package io.github.drtron1c.cloudphotos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Panorama
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import coil3.compose.AsyncImage
import io.github.drtron1c.cloudphotos.photos.AlbumKind
import io.github.drtron1c.cloudphotos.photos.AlbumSpec
import io.github.drtron1c.cloudphotos.photos.Asset
import io.github.drtron1c.cloudphotos.photos.MediaList
import io.github.drtron1c.cloudphotos.photos.UserAlbum

fun iconFor(spec: AlbumSpec): ImageVector = when (spec.icon) {
    "video" -> Icons.Outlined.Videocam
    "live" -> Icons.Outlined.MotionPhotosOn
    "panorama" -> Icons.Outlined.Panorama
    "slomo" -> Icons.Outlined.SlowMotionVideo
    "timelapse" -> Icons.Outlined.Timelapse
    "screenshot" -> Icons.Outlined.Screenshot
    "burst" -> Icons.Outlined.BurstMode
    "hidden" -> Icons.Outlined.VisibilityOff
    "deleted" -> Icons.Outlined.Delete
    "album" -> Icons.Outlined.Collections
    "shared" -> Icons.Outlined.People
    "favorite" -> Icons.Outlined.Favorite
    "saved" -> Icons.Outlined.Schedule
    "edited" -> Icons.Outlined.Edit
    "viewed" -> Icons.Outlined.History
    "duplicates" -> Icons.Outlined.ContentCopy
    "onthisday" -> Icons.Outlined.AutoAwesome
    "search" -> Icons.Outlined.Search
    else -> Icons.Outlined.Image
}

/** Opens an album, asking for fingerprint / PIN first for Hidden and Recently Deleted (like Face ID on iPhone). */
fun openAlbum(vm: MainViewModel, activity: FragmentActivity?, spec: AlbumSpec) {
    val isPrivate = spec.kind == AlbumKind.Hidden || spec.kind == AlbumKind.Deleted
    if (isPrivate && vm.settings.lockPrivateAlbums && activity != null) {
        vm.expectingExternal = true
        activity.requireUnlock("Unlock ${spec.title}") { vm.openAlbum(spec) }
    } else {
        vm.openAlbum(spec)
    }
}

@Composable
fun AlbumsTab(vm: MainViewModel) {
    val p = LocalPalette.current
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TAB_BAR_HEIGHT.dp
    val tile = if (LocalConfiguration.current.screenWidthDp >= 600) 190.dp else 150.dp
    var newAlbum by remember { mutableStateOf(false) }

    if (vm.offlineMode) {
        OfflineAlbums(vm, top, bottom, tile)
        return
    }

    val myAlbums = remember(vm.userAlbums, vm.zone) {
        listOf(AlbumSpec.LIBRARY.copy(title = "Recents"), AlbumSpec.FAVORITES) +
            vm.userAlbums.map { AlbumSpec.user(it.recordName, it.title) }
    }
    val sharedSpecs = remember(vm.sharedAlbums) { vm.sharedAlbums.map { AlbumSpec.shared(it) } }

    LazyColumn(
        Modifier.fillMaxSize().background(p.background),
        contentPadding = PaddingValues(top = top + 8.dp, bottom = bottom + 16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Albums", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = p.label, modifier = Modifier.weight(1f))
                Pill(icon = Icons.Filled.Add, onDark = false, onClick = { newAlbum = true })
                Spacer(Modifier.width(8.dp))
                Pill(icon = Icons.Filled.Refresh, onDark = false, onClick = { vm.refreshAll() })
                Spacer(Modifier.width(8.dp))
                Pill(icon = Icons.Filled.Settings, onDark = false, onClick = { vm.open(Route.Settings) })
            }
        }
        item { SectionHeader("My Albums", action = "See All") { vm.open(Route.AllAlbums) } }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(myAlbums, key = { it.key }) { spec ->
                    AlbumTile(vm, vm.list(spec), tile) { vm.openAlbum(spec) }
                }
                item { NewAlbumTile(tile) { newAlbum = true } }
                if (vm.albumsLoading) {
                    item {
                        Box(Modifier.size(tile), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
        if (sharedSpecs.isNotEmpty()) {
            item { SectionHeader("Shared Albums") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(sharedSpecs, key = { it.key }) { spec ->
                        AlbumTile(vm, vm.list(spec), tile) { vm.openAlbum(spec) }
                    }
                }
            }
        }
        item { SectionHeader("Media Types") }
        items(AlbumSpec.MEDIA_TYPES, key = { it.key }) { spec ->
            AlbumRow(vm, spec, vm.list(spec).count, locked = false) { openAlbum(vm, activity, spec) }
        }
        item { SectionHeader("Utilities") }
        item {
            AlbumRow(vm, AlbumSpec.RECENTLY_SAVED, vm.list(AlbumSpec.RECENTLY_SAVED).count, false) { vm.openAlbum(AlbumSpec.RECENTLY_SAVED) }
        }
        item { VirtualRow(vm, "Recently Edited", "edited") { vm.recentlyEditedList() } }
        if (vm.settings.recentlyViewedEnabled) {
            item { VirtualRow(vm, "Recently Viewed", "viewed") { vm.recentlyViewedList() } }
        }
        item { VirtualRow(vm, "Duplicates", "duplicates") { vm.duplicatesList() } }
        item { VirtualRow(vm, "On This Day", "onthisday") { vm.onThisDayList() } }
        items(listOf(AlbumSpec.HIDDEN, AlbumSpec.RECENTLY_DELETED), key = { it.key }) { spec ->
            AlbumRow(vm, spec, -1, locked = vm.settings.lockPrivateAlbums) { openAlbum(vm, activity, spec) }
        }
        if (vm.zone != "PrimarySync") {
            item {
                Text(
                    "You're viewing the Shared Library. Switch back to your Personal Library in Settings to see your own albums.",
                    color = p.secondaryLabel, fontSize = 13.sp,
                    modifier = Modifier.padding(18.dp),
                )
            }
        }
    }

    if (newAlbum) {
        NewAlbumDialog(onDismiss = { newAlbum = false }) { name ->
            newAlbum = false
            vm.createAlbum(name)
        }
    }
}

/** Shown when there's no connection: the albums kept on this device. */
@Composable
private fun OfflineAlbums(vm: MainViewModel, top: Dp, bottom: Dp, tile: Dp) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.background).padding(top = top + 8.dp, bottom = bottom)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Offline", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = p.label, modifier = Modifier.weight(1f))
            Pill(icon = Icons.Filled.Refresh, onDark = false, onClick = { vm.retryStartup() })
        }
        Text(
            "No connection — these albums are kept on this device.",
            color = p.secondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(tile),
            contentPadding = PaddingValues(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(vm.offlineAlbums, key = { it.key }) { spec ->
                AlbumTileAdaptive(vm, vm.list(spec)) { vm.openAlbum(spec) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    val p = LocalPalette.current
    Column {
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = p.separator, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.label, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = onAction) { Text(action, fontSize = 16.sp) }
        }
    }
}

@Composable
fun AlbumTile(vm: MainViewModel, list: MediaList, size: Dp, onClick: () -> Unit) {
    val p = LocalPalette.current
    val count = list.count
    // Cover = newest item, like the iPhone.
    val coverIndex = if (count <= 0) -1 else if (vm.settings.newestOnTop) 0 else count - 1
    val cover = if (coverIndex >= 0) list[coverIndex] else null
    LaunchedEffect(count, cover == null) { if (coverIndex >= 0) list.ensure(coverIndex) }
    Column(Modifier.width(size).clickable(onClick = onClick)) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(p.placeholder),
            contentAlignment = Alignment.Center,
        ) {
            val req = cover?.let { thumbRequest(vm, it) }
            if (req != null) {
                AsyncImage(
                    model = req,
                    contentDescription = list.spec.title,
                    imageLoader = vm.imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(iconFor(list.spec), null, tint = p.secondaryLabel, modifier = Modifier.size(40.dp))
            }
            if (vm.isOffline(list.spec)) {
                Icon(
                    Icons.Filled.CloudDownload, "Kept offline", tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp)
                        .background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(list.spec.title, color = p.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (count < 0) " " else "%,d".format(count), color = p.secondaryLabel, fontSize = 14.sp)
    }
}

@Composable
private fun NewAlbumTile(size: Dp, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.width(size).clickable(onClick = onClick)) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(8.dp)).border(1.dp, p.separator, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, "New Album", tint = p.accent, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("New Album", color = p.accent, fontSize = 15.sp)
        Text(" ", fontSize = 14.sp)
    }
}

@Composable
private fun AlbumRow(vm: MainViewModel, spec: AlbumSpec, count: Int, locked: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconFor(spec), null, tint = p.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Text(spec.title, color = p.accent, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (vm.isOffline(spec)) {
            Icon(Icons.Filled.CloudDownload, "Kept offline", tint = p.secondaryLabel, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        if (locked) Icon(Icons.Filled.Lock, null, tint = p.secondaryLabel, modifier = Modifier.size(16.dp))
        else if (count >= 0) Text("%,d".format(count), color = p.secondaryLabel, fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = p.secondaryLabel)
    }
}

/** A Utilities row backed by the on-device index (shows progress until it's ready). */
@Composable
private fun VirtualRow(vm: MainViewModel, title: String, icon: String, build: () -> MediaList?) {
    val p = LocalPalette.current
    val ready = vm.indexMatchesLibrary
    val count = remember(ready, vm.index.entries, vm.recentlyViewed) { if (ready) build()?.count ?: -1 else -1 }
    Row(
        Modifier.fillMaxWidth()
            .clickable {
                val l = build()
                if (l != null) vm.openList(l) else vm.toast("Still organising your library… ${(vm.index.progress * 100).toInt()}%")
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconFor(AlbumSpec.virtual(icon, title, icon)), null, tint = p.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, color = p.accent, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (count >= 0) Text("%,d".format(count), color = p.secondaryLabel, fontSize = 16.sp)
        else CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = p.secondaryLabel)
    }
}

/** "See All": every album as a grid. */
@Composable
fun AllAlbumsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val specs = remember(vm.userAlbums) {
        listOf(AlbumSpec.LIBRARY.copy(title = "Recents"), AlbumSpec.FAVORITES) +
            vm.userAlbums.map { AlbumSpec.user(it.recordName, it.title) }
    }
    Column(Modifier.fillMaxSize().background(p.background)) {
        Row(Modifier.fillMaxWidth().padding(top = top + 8.dp, start = 14.dp, end = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(icon = Icons.AutoMirrored.Filled.ArrowBackIos, onDark = false, onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("My Albums", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = p.label)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (wide) 170.dp else 150.dp),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = bottom + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(specs, key = { it.key }) { spec ->
                AlbumTileAdaptive(vm, vm.list(spec)) { vm.openAlbum(spec) }
            }
        }
    }
}

@Composable
private fun AlbumTileAdaptive(vm: MainViewModel, list: MediaList, onClick: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        AlbumTile(vm, list, maxWidth, onClick)
    }
}

@Composable
fun NewAlbumDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Album") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Title") })
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick an album (or make a new one) for the selected items. */
@Composable
fun AddToAlbumDialog(vm: MainViewModel, assets: List<Asset>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val p = LocalPalette.current
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NewAlbumDialog(onDismiss = { creating = false }) { name ->
            creating = false
            vm.createAlbum(name, assets, onDone)
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (assets.size == 1) "Add to Album" else "Add ${assets.size} Items to Album") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, null, tint = p.accent)
                        Spacer(Modifier.width(12.dp))
                        Text("New Album…", color = p.accent, fontSize = 16.sp)
                    }
                }
                items(vm.userAlbums, key = { it.recordName }) { album: UserAlbum ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.addToAlbum(album, assets, onDone) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Collections, null, tint = p.secondaryLabel)
                        Spacer(Modifier.width(12.dp))
                        Text(album.title, color = p.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (vm.userAlbums.isEmpty()) {
                    item { Text("You don't have any albums yet.", color = p.secondaryLabel) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
