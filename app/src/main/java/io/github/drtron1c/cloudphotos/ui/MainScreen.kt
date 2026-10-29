package io.github.drtron1c.cloudphotos.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.github.drtron1c.cloudphotos.photos.AlbumKind
import io.github.drtron1c.cloudphotos.photos.MediaList
import io.github.drtron1c.cloudphotos.vpn.VpnState

@Composable
fun AppRoot(vm: MainViewModel) {
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    val vpnState by vm.vpn.state.collectAsState()
    // Android asks once whether Cloud Photos may run a VPN.
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.vpn.onPermissionResult(r.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(vpnState) {
        if (vpnState is VpnState.NeedsPermission) {
            val intent = vm.vpn.permissionIntent()
            if (intent == null) vm.vpn.onPermissionResult(true)
            else {
                vm.expectingExternal = true
                vpnPermission.launch(intent)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        when (val ph = vm.phase) {
            Phase.Starting -> SplashScreen()
            is Phase.Login -> LoginScreen(ph, vm) { id, pw, keep -> vm.signIn(id, pw, keep) }
            is Phase.TwoFactor -> TwoFactorScreen(ph, vm)
            is Phase.Waiting -> WaitingScreen(ph.message) { vm.cancelSignIn() }
            is Phase.Failed -> FailedScreen(ph.message, vm, onRetry = { vm.retryStartup() }, onSignOut = { vm.signOut() })
            Phase.Ready -> MainScreen(vm)
        }
        VpnBanner(vm, vpnState, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = if (vm.phase == Phase.Ready) 60.dp else 6.dp))
        if (vm.locked && vm.settings.lockApp) {
            val unlock = {
                if (activity != null) {
                    vm.expectingExternal = true
                    activity.requireUnlock("Unlock ${vm.settings.appName}") { vm.unlock() }
                } else vm.unlock()
            }
            LockScreen(vm.settings.appName, onUnlock = unlock)
            LaunchedEffect(Unit) { unlock() }
        }
    }
}

@Composable
fun MainScreen(vm: MainViewModel) {
    val p = LocalPalette.current
    val snackbar = remember { SnackbarHostState() }
    val saveable = rememberSaveableStateHolder()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        vm.upload(uris)
    }
    val pickToUpload = {
        vm.expectingExternal = true
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.stack.isNotEmpty()) { vm.back() }
    BackHandler(enabled = vm.stack.isEmpty() && vm.tab != Tab.Library) { vm.selectTab(Tab.Library) }

    // Everything is the backdrop for the banners' glass; the tabs are the tab bar's backdrop.
    GlassHost(Modifier.fillMaxSize().background(p.background), content = {
        GlassHost(Modifier.fillMaxSize(), content = {
            saveable.SaveableStateProvider(vm.tab.name + vm.zone + vm.settings.newestOnTop) {
                when (vm.tab) {
                    Tab.Library -> LibraryTab(vm, onUploadClick = pickToUpload)
                    Tab.Albums -> AlbumsTab(vm)
                    Tab.Search -> SearchTab(vm)
                }
            }
        }) {
            if (!(vm.tab == Tab.Library && vm.librarySelecting)) {
                TabBar(vm, navBottom, Modifier.align(Alignment.BottomCenter))
            }
        }

        vm.stack.forEachIndexed { i, route ->
            val covered = i != vm.stack.lastIndex
            key(i, route) {
                when (route) {
                    is Route.Grid -> {
                        val list = vm.listByKey(route.key)
                        if (list == null) LaunchedEffect(Unit) { vm.back() }
                        else AlbumGrid(vm, list)
                    }
                    is Route.Viewer -> {
                        val list = vm.listByKey(route.key)
                        if (list == null) LaunchedEffect(Unit) { vm.back() }
                        else ViewerScreen(vm, list, route.index, covered) { vm.back() }
                    }
                    is Route.Slideshow -> {
                        val list = vm.listByKey(route.key)
                        if (list == null) LaunchedEffect(Unit) { vm.back() }
                        else SlideshowScreen(vm, list, route.index) { vm.back() }
                    }
                    is Route.Editor -> {
                        val asset = vm.listByKey(route.key)?.get(route.index)
                        if (asset == null) LaunchedEffect(Unit) { vm.back() }
                        else EditorScreen(vm, asset) { vm.back() }
                    }
                    Route.Settings -> SettingsScreen(vm) { vm.back() }
                    Route.AllAlbums -> AllAlbumsScreen(vm) { vm.back() }
                    Route.AppIdentity -> IdentityScreen(vm) { vm.back() }
                    Route.Vpn -> VpnScreen(vm) { vm.back() }
                }
            }
        }
    }) {
        // Liquid Glass banners at the top: update available / what's new / VPN.
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = statusTop + 6.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            UpdateBanner(vm.updates)
            if (vm.whatsNew.isNotEmpty()) {
                WhatsNewBanner(vm.whatsNew, io.github.drtron1c.cloudphotos.BuildConfig.VERSION_CODE, onDismiss = { vm.dismissWhatsNew() })
            }
        }

        AnimatedVisibility(
            visible = vm.uploadStatus != null,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = statusTop + 64.dp),
        ) {
            Row(
                Modifier.background(p.card, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(vm.uploadStatus ?: "", color = p.label, fontSize = 14.sp)
            }
        }

        if (vm.offlineMode && vm.stack.isEmpty()) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusTop + 60.dp)
                    .background(p.card, RoundedCornerShape(16.dp))
                    .clickable { vm.retryStartup() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CloudOff, null, tint = p.secondaryLabel, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Offline — tap to reconnect", color = p.label, fontSize = 13.sp)
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + TAB_BAR_HEIGHT.dp + 20.dp),
        )

        val busy = vm.busy
        if (busy != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.background(p.card, RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(busy, color = p.label, fontSize = 15.sp)
                }
            }
        }
    }
}

/** An album, smart album, shared album or search result, with its ••• menu. */
@Composable
private fun AlbumGrid(vm: MainViewModel, list: MediaList) {
    val spec = list.spec
    var menu by remember { mutableStateOf(false) }
    val canKeepOffline = !vm.offlineMode && spec.kind != AlbumKind.Virtual &&
        spec.kind != AlbumKind.Hidden && spec.kind != AlbumKind.Deleted && spec.kind != AlbumKind.Library
    PhotoGrid(
        vm = vm,
        list = list,
        isRoot = false,
        onBack = { vm.back() },
        headerActions = {
            Box {
                Pill(icon = Icons.Filled.MoreHoriz, onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Slideshow") },
                        leadingIcon = { Icon(Icons.Outlined.Slideshow, null) },
                        enabled = list.count > 0,
                        onClick = { menu = false; vm.open(Route.Slideshow(list.key, 0)) },
                    )
                    if (canKeepOffline) {
                        val kept = vm.isOffline(spec)
                        DropdownMenuItem(
                            text = { Text(if (kept) "Remove Offline Copy" else "Keep Offline") },
                            leadingIcon = { Icon(if (kept) Icons.Outlined.CloudOff else Icons.Outlined.CloudDownload, null) },
                            onClick = { menu = false; vm.setOffline(spec, !kept) },
                        )
                    }
                    if (!vm.offlineMode) {
                        DropdownMenuItem(
                            text = { Text("Refresh") },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                            onClick = { menu = false; list.refresh() },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        },
    )
}

@Composable
private fun TabBar(vm: MainViewModel, navBottom: Dp, modifier: Modifier) {
    val labels = vm.settings.tabLabels
    Box(modifier.fillMaxWidth()) {
        GlassBar(navBottom) {
            Row(
                Modifier.fillMaxWidth().height(TAB_BAR_HEIGHT.dp).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabItem(Icons.Filled.PhotoLibrary, "Library", labels, vm.tab == Tab.Library) { vm.selectTab(Tab.Library) }
                TabItem(Icons.Filled.Collections, "Albums", labels, vm.tab == Tab.Albums) { vm.selectTab(Tab.Albums) }
                TabItem(Icons.Filled.Search, "Search", labels, vm.tab == Tab.Search) { vm.selectTab(Tab.Search) }
            }
        }
    }
}

@Composable
private fun TabItem(icon: ImageVector, label: String, showLabel: Boolean, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val tint = if (selected) p.accent else p.secondaryLabel
    val glass = LocalGlassEnabled.current
    Column(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (glass && selected) p.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(if (showLabel) 26.dp else 30.dp))
        if (showLabel) Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
