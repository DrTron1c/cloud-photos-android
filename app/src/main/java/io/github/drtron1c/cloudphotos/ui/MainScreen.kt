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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.github.drtron1c.cloudphotos.photos.AlbumSpec

@Composable
fun AppRoot(vm: MainViewModel) {
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    Box(Modifier.fillMaxSize()) {
        when (val ph = vm.phase) {
            Phase.Starting -> SplashScreen()
            is Phase.Login -> LoginScreen(ph) { id, pw, keep -> vm.signIn(id, pw, keep) }
            is Phase.TwoFactor -> TwoFactorScreen(ph, vm)
            is Phase.Waiting -> WaitingScreen(ph.message) { vm.cancelSignIn() }
            is Phase.Failed -> FailedScreen(ph.message, onRetry = { vm.retryStartup() }, onSignOut = { vm.signOut() })
            Phase.Ready -> MainScreen(vm)
        }
        if (vm.locked && vm.lockApp) {
            val unlock = {
                if (activity != null) {
                    vm.expectingExternal = true
                    activity.requireUnlock("Unlock Cloud Photos") { vm.unlock() }
                } else vm.unlock()
            }
            LockScreen(onUnlock = unlock)
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
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.stack.isNotEmpty()) { vm.back() }
    BackHandler(enabled = vm.stack.isEmpty() && vm.tab != Tab.Library) { vm.selectTab(Tab.Library) }

    Box(Modifier.fillMaxSize().background(p.background)) {
        saveable.SaveableStateProvider(vm.tab.name + vm.zone) {
            when (vm.tab) {
                Tab.Library -> PhotoGrid(
                    vm = vm,
                    spec = AlbumSpec.LIBRARY,
                    isRoot = true,
                    onBack = null,
                    onSelectingChanged = { vm.librarySelecting = it },
                    onUploadClick = {
                        vm.expectingExternal = true
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                )
                Tab.Albums -> AlbumsTab(vm)
            }
        }

        if (!(vm.tab == Tab.Library && vm.librarySelecting)) {
            TabBar(vm, navBottom, Modifier.align(Alignment.BottomCenter))
        }

        vm.stack.forEachIndexed { i, route ->
            key(i, route) {
                when (route) {
                    is Route.Album -> PhotoGrid(vm, route.spec, isRoot = false, onBack = { vm.back() })
                    is Route.Viewer -> ViewerScreen(vm, route.spec, route.index) { vm.back() }
                    Route.Settings -> SettingsScreen(vm) { vm.back() }
                    Route.AllAlbums -> AllAlbumsScreen(vm) { vm.back() }
                }
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

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + TAB_BAR_HEIGHT.dp + 8.dp),
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

@Composable
private fun TabBar(vm: MainViewModel, navBottom: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().background(p.bar)) {
        HorizontalDivider(thickness = 0.5.dp, color = p.separator)
        Row(
            Modifier.fillMaxWidth().height(TAB_BAR_HEIGHT.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem(Icons.Filled.PhotoLibrary, "Library", vm.tab == Tab.Library) { vm.selectTab(Tab.Library) }
            TabItem(Icons.Filled.Collections, "Albums", vm.tab == Tab.Albums) { vm.selectTab(Tab.Albums) }
        }
        Spacer(Modifier.height(navBottom))
    }
}

@Composable
private fun TabItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val tint = if (selected) p.accent else p.secondaryLabel
    Column(
        Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
