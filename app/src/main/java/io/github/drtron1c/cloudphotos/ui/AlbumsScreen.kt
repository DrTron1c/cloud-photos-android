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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Panorama
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import coil3.request.ImageRequest
import io.github.drtron1c.cloudphotos.photos.AlbumKind
import io.github.drtron1c.cloudphotos.photos.AlbumSpec

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
    else -> Icons.Outlined.Image
}

/** Opens an album, asking for fingerprint / PIN first for Hidden and Recently Deleted (like Face ID on iPhone). */
fun openAlbum(vm: MainViewModel, activity: FragmentActivity?, spec: AlbumSpec) {
    val isPrivate = spec.kind == AlbumKind.Hidden || spec.kind == AlbumKind.Deleted
    if (isPrivate && vm.lockPrivateAlbums && activity != null) {
        vm.expectingExternal = true
        activity.requireUnlock("Unlock ${spec.title}") { vm.open(Route.Album(spec)) }
    } else {
        vm.open(Route.Album(spec))
    }
}

@Composable
fun AlbumsTab(vm: MainViewModel) {
    val p = LocalPalette.current
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TAB_BAR_HEIGHT.dp
    val tile = if (LocalConfiguration.current.screenWidthDp >= 600) 190.dp else 150.dp
    val myAlbums = remember(vm.userAlbums, vm.zone) {
        listOf(AlbumSpec.LIBRARY.copy(title = "Recents"), AlbumSpec.FAVORITES) +
            vm.userAlbums.map { AlbumSpec.user(it.recordName, it.title) }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(p.background),
        contentPadding = PaddingValues(top = top + 8.dp, bottom = bottom + 16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Albums", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = p.label, modifier = Modifier.weight(1f))
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
                    AlbumTile(vm, spec, tile) { vm.open(Route.Album(spec)) }
                }
                if (vm.albumsLoading) {
                    item {
                        Box(Modifier.size(tile), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
        item { SectionHeader("Media Types") }
        items(AlbumSpec.MEDIA_TYPES, key = { it.key }) { spec ->
            AlbumRow(vm, spec, locked = false) { openAlbum(vm, activity, spec) }
        }
        item { SectionHeader("Utilities") }
        items(listOf(AlbumSpec.HIDDEN, AlbumSpec.RECENTLY_DELETED), key = { it.key }) { spec ->
            AlbumRow(vm, spec, locked = vm.lockPrivateAlbums) { openAlbum(vm, activity, spec) }
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
fun AlbumTile(vm: MainViewModel, spec: AlbumSpec, size: Dp, onClick: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val list = vm.list(spec)
    val count = list.count
    LaunchedEffect(count) { if (count > 0) list.ensure(count - 1) }
    val cover = if (count > 0) list[count - 1] else null
    Column(Modifier.width(size).clickable(onClick = onClick)) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(p.placeholder),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = cover?.thumbnail
            if (thumb != null) {
                AsyncImage(
                    model = remember(thumb.checksum) {
                        ImageRequest.Builder(context).data(thumb.url).memoryCacheKey(thumb.checksum).diskCacheKey(thumb.checksum).build()
                    },
                    contentDescription = spec.title,
                    imageLoader = vm.imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(iconFor(spec), null, tint = p.secondaryLabel, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(spec.title, color = p.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (count < 0) " " else "%,d".format(count), color = p.secondaryLabel, fontSize = 14.sp)
    }
}

@Composable
private fun AlbumRow(vm: MainViewModel, spec: AlbumSpec, locked: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val count = if (locked) -1 else vm.list(spec).count
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconFor(spec), null, tint = p.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Text(spec.title, color = p.accent, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (locked) Icon(Icons.Filled.Lock, null, tint = p.secondaryLabel, modifier = Modifier.size(16.dp))
        else if (count >= 0) Text("%,d".format(count), color = p.secondaryLabel, fontSize = 16.sp)
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
                AlbumTileAdaptive(vm, spec) { vm.open(Route.Album(spec)) }
            }
        }
    }
}

@Composable
private fun AlbumTileAdaptive(vm: MainViewModel, spec: AlbumSpec, onClick: () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        AlbumTile(vm, spec, maxWidth, onClick)
    }
}
