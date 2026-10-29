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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.github.drtron1c.cloudphotos.photos.AlbumSpec

/** Width (dp) the current screen gets — narrower than the window next to a sidebar or a photo. */
val LocalContentWidth = compositionLocalOf<Dp?> { null }

/** True when a sidebar (or the photo beside the grid) replaces the bottom tab bar. */
val LocalNoTabBar = compositionLocalOf { false }

@Composable
fun contentWidthDp(): Int = LocalContentWidth.current?.value?.toInt() ?: LocalConfiguration.current.screenWidthDp

val SIDEBAR_WIDTH = 264.dp

/** Big-screen layout (iPad style): sections and albums down the left side. */
@Composable
fun Sidebar(vm: MainViewModel, modifier: Modifier) {
    val p = LocalPalette.current
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val root = vm.stack.isEmpty()
    val openKey = (vm.stack.firstOrNull() as? Route.Grid)?.key
    val albums = remember(vm.userAlbums) { vm.userAlbums.map { AlbumSpec.user(it.recordName, it.title) } }
    val shared = remember(vm.sharedAlbums) { vm.sharedAlbums.map { AlbumSpec.shared(it) } }

    Box(modifier.background(if (p.isDark) Color(0xFF111113) else Color(0xFFF2F2F7))) {
        LazyColumn(
            Modifier.fillMaxHeight().fillMaxWidth(),
            contentPadding = PaddingValues(top = top + 10.dp, bottom = bottom + 16.dp, start = 10.dp, end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        vm.settings.appName, color = p.label, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Pill(icon = Icons.Filled.Settings, onDark = false, onClick = { vm.open(Route.Settings) })
                }
            }
            item { SideItem(Icons.Filled.PhotoLibrary, "Library", root && vm.tab == Tab.Library) { vm.goHome(Tab.Library) } }
            item { SideItem(Icons.Filled.Collections, "Albums", root && vm.tab == Tab.Albums) { vm.goHome(Tab.Albums) } }
            item { SideItem(Icons.Filled.Search, "Search", root && vm.tab == Tab.Search) { vm.goHome(Tab.Search) } }
            if (!vm.offlineMode) {
                item { SideHeading("Collections") }
                item { SideItem(Icons.Outlined.AutoAwesome, "Memories", vm.stack.firstOrNull() == Route.Memories) { vm.goTo(Route.Memories) } }
                item { SideItem(Icons.Outlined.Face, "People & Pets", vm.stack.firstOrNull() == Route.People) { vm.goTo(Route.People) } }
                item { SideItem(Icons.Outlined.Map, "Places", vm.stack.firstOrNull() == Route.Places) { vm.goTo(Route.Places) } }
                item { SideItem(Icons.Outlined.PhotoFilter, "Similar Photos", vm.stack.firstOrNull() == Route.Similar) { vm.goTo(Route.Similar) } }
                for (spec in listOf(AlbumSpec.FAVORITES, AlbumSpec.VIDEOS, AlbumSpec.RECENTLY_SAVED, AlbumSpec.HIDDEN, AlbumSpec.RECENTLY_DELETED)) {
                    item(key = spec.key) {
                        SideItem(iconFor(spec), spec.title, openKey != null && vm.listByKey(openKey)?.spec?.key == spec.key) {
                            vm.stack.clear()
                            openAlbum(vm, activity, spec)
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    item { SideHeading("My Albums") }
                    items(albums, key = { it.key }) { spec ->
                        SideItem(iconFor(spec), spec.title, openKey != null && vm.listByKey(openKey)?.spec?.key == spec.key) {
                            vm.stack.clear(); vm.openAlbum(spec)
                        }
                    }
                }
                if (shared.isNotEmpty()) {
                    item { SideHeading("Shared Albums") }
                    items(shared, key = { it.key }) { spec ->
                        SideItem(iconFor(spec), spec.title, openKey != null && vm.listByKey(openKey)?.spec?.key == spec.key) {
                            vm.stack.clear(); vm.openAlbum(spec)
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(0.5.dp),
            color = p.separator,
        )
    }
}

@Composable
private fun SideHeading(title: String) {
    val p = LocalPalette.current
    Column {
        Spacer(Modifier.height(14.dp))
        Text(title, color = p.secondaryLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, bottom = 4.dp))
    }
}

@Composable
private fun SideItem(icon: ImageVector, title: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) p.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Color.White else p.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            title, color = if (selected) Color.White else p.label, fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
