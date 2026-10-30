package io.github.drtron1c.cloudphotos.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.github.drtron1c.cloudphotos.BuildConfig
import io.github.drtron1c.cloudphotos.ai.ModelState
import io.github.drtron1c.cloudphotos.update.UpdateState
import io.github.drtron1c.cloudphotos.vpn.VpnState
import io.github.drtron1c.cloudphotos.settings.AppIdentity
import io.github.drtron1c.cloudphotos.settings.AppSettings
import io.github.drtron1c.cloudphotos.work.AutoUploadWorker
import io.github.drtron1c.cloudphotos.work.OfflineWorker
import kotlinx.coroutines.delay

val DNS_OPTIONS = listOf(
    "cloudflare" to "Cloudflare (1.1.1.1)",
    "quad9" to "Quad9 (9.9.9.9)",
    "google" to "Google (8.8.8.8)",
    "system" to "Network default (not encrypted)",
)

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val s = vm.settings
    val context = LocalContext.current
    val activity = context.findActivity() as? FragmentActivity
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var confirmSignOut by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    // Refreshed every couple of seconds so background job status stays current.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    var offlineBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tick, vm.offlineAlbums) { offlineBytes = vm.offlineBytes() }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setMemoriesNotify(true) else vm.toast("Notifications are off for Cloud Photos in Android settings")
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: "Your song"
            s.memoryMusicUri = uri.toString()
            s.memoryMusicName = name
            s.memoryMusic = "custom"
        }
    }
    var showShortcutList by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (AutoUploadWorker.hasMediaAccess(context)) dialog = "autoupload"
        else vm.toast("Cloud Photos needs access to your photos and videos to upload them")
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.groupedBackground)
            .verticalScroll(rememberScrollState())
            .padding(top = top + 8.dp, bottom = bottom + 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(icon = Icons.AutoMirrored.Filled.ArrowBackIos, onDark = false, onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = p.label)
            }

            // ---------- Account ----------
            Group("Account") {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(vm.fullName ?: "iCloud", color = p.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(vm.appleId, color = p.secondaryLabel, fontSize = 14.sp)
                        if (vm.offlineMode) Text("Offline — showing photos saved on this device", color = p.secondaryLabel, fontSize = 13.sp)
                    }
                }
            }

            if (vm.zones.size > 1) {
                Group("Library", footer = "Choose which iCloud library to browse, like the Personal / Shared Library switch on iPhone.") {
                    vm.zones.forEachIndexed { i, z ->
                        if (i > 0) Divider()
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.switchZone(z) }.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = vm.zone == z, onClick = { vm.switchZone(z) })
                            Text(if (z == "PrimarySync") "Personal Library" else "Shared Library", color = p.label, fontSize = 17.sp)
                        }
                    }
                }
            }

            // ---------- Appearance ----------
            Group("Appearance") {
                Segmented("Theme", listOf("system" to "Automatic", "light" to "Light", "dark" to "Dark"), s.themeMode) { s.themeMode = it }
                Divider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Accent colour", color = p.label, fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppSettings.ACCENTS.forEachIndexed { i, (name, argb) ->
                            val c = Color(argb)
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(if (s.accent == i) 3.dp else 0.dp, if (s.accent == i) p.label else Color.Transparent, CircleShape)
                                    .clickable { s.accent = i },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (s.accent == i) Icon(Icons.Filled.Check, name, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Divider()
                ToggleRow(
                    "Liquid Glass",
                    if (glassSupported()) "iOS 26-style see-through glass bars, buttons and banners" else "Needs Android 12 or newer",
                    s.liquidGlass,
                    enabled = glassSupported(),
                ) { s.liquidGlass = it }
                Divider()
                ToggleRow("Tab bar labels", "Show names under the Library, Albums and Search icons", s.tabLabels) { s.tabLabels = it }
                Divider()
                ValueRow("App icon & name", s.appName) { vm.open(Route.AppIdentity) }
            }
            Footer("Type any name and use any picture as the app's icon.")

            // ---------- Display & big screens ----------
            Group("Display & big screens") {
                val display = remember(tick) { context.display }
                val now = display?.refreshRate?.let { "%.0f Hz".format(it) } ?: "—"
                val max = display?.supportedModes?.maxOfOrNull { it.refreshRate }?.let { "%.0f Hz".format(it) } ?: "—"
                ToggleRow("Smooth motion", "Use the screen's fastest refresh rate (now $now, up to $max)", s.highRefresh) { s.highRefresh = it }
                Divider()
                ToggleRow("Sidebar on big screens", "Tablets and unfolded phones get an iPad-style sidebar instead of the tab bar", s.sidebar) { s.sidebar = it }
                Divider()
                ToggleRow("Photos beside the grid", "On big screens, open photos next to the grid so you can flick through both", s.splitViewer) { s.splitViewer = it }
                Divider()
                ActionRow("Keyboard Shortcuts", p.accent) { showShortcutList = true }
            }
            Footer("With a keyboard: press Ctrl+/ for shortcuts. In split screen or a floating window, long-press a photo to drag it into another app, or drop photos onto Cloud Photos to add them to iCloud.")

            // ---------- Library & grid ----------
            Group("Library") {
                ToggleRow("Newest at top", "Off = newest at the bottom, like iPhone", s.newestOnTop) { vm.setNewestOnTop(it) }
                Divider()
                ToggleRow("Aspect ratio grid", "Show whole photos instead of square crops", s.aspectGrid) { s.aspectGrid = it }
                Divider()
                ToggleRow("Video lengths", "Show the duration on video thumbnails", s.showDurations) { s.showDurations = it }
                Divider()
                ToggleRow("Favourite hearts", "Mark favourites in the grid", s.showFavouriteBadges) { s.showFavouriteBadges = it }
                Divider()
                Segmented("Open app on", listOf("library" to "Library", "albums" to "Albums", "search" to "Search"), s.startTab) { s.startTab = it }
                Divider()
                Segmented("Library view", listOf("years" to "Years", "months" to "Months", "days" to "Days", "all" to "All"), s.libraryView) { s.libraryView = it }
                Divider()
                ToggleRow("Confirm before deleting", "Ask before moving items to Recently Deleted", s.confirmDelete) { s.confirmDelete = it }
                Divider()
                ToggleRow("Haptic feedback", "Little taps when selecting and zooming the grid", s.haptics) { s.haptics = it }
            }

            // ---------- Videos ----------
            Group("Videos & Live Photos") {
                ToggleRow("Auto-play videos", "Start playing when you open a video", s.autoplayVideos) { s.autoplayVideos = it }
                Divider()
                ToggleRow("Loop videos", "Play again from the start when they finish", s.loopVideos) { s.loopVideos = it }
                Divider()
                ToggleRow("Start muted", "Turn sound on with the speaker button", s.startMuted) { s.startMuted = it }
                Divider()
                ToggleRow("Auto-play Live Photos", "Play the motion once when you swipe to one", s.livePhotoAutoplay) { s.livePhotoAutoplay = it }
            }
            var mediaCacheUsed by remember { mutableStateOf(-1L) }
            LaunchedEffect(s.mediaCacheMb, s.mediaCacheKeep, dialog) { mediaCacheUsed = vm.mediaCacheBytes() }
            Group(
                "Temporary cache",
                footer = "Videos and GIFs you watch are kept for a while in this app's private storage, so watching again, scrubbing and the " +
                    "frame strip don't download them again. No other app can read it, and Android may also clear it when space runs low.",
            ) {
                ValueRow("Cache videos & GIFs", MEDIA_CACHE_SIZES.firstOrNull { it.first == s.mediaCacheMb.toString() }?.second ?: "${s.mediaCacheMb} MB") { dialog = "mediacache" }
                if (s.mediaCacheMb > 0) {
                    Divider()
                    ValueRow("Keep them", MEDIA_CACHE_KEEPS.firstOrNull { it.first == s.mediaCacheKeep }?.second ?: s.mediaCacheKeep) { dialog = "mediakeep" }
                }
                Divider()
                ValueRow("Space used", if (mediaCacheUsed < 0) "…" else formatBytes(mediaCacheUsed).ifEmpty { "0 KB" }, onClick = null)
                Divider()
                ActionRow("Clear cached videos", p.accent) { vm.clearMediaCache { mediaCacheUsed = 0L } }
            }

            // ---------- Slideshow ----------
            Group("Slideshow") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Time per photo", color = p.label, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text("${s.slideshowSeconds} s", color = p.secondaryLabel, fontSize = 15.sp)
                    }
                    Slider(
                        value = s.slideshowSeconds.toFloat(),
                        onValueChange = { s.slideshowSeconds = it.toInt().coerceIn(2, 15) },
                        valueRange = 2f..15f,
                        steps = 12,
                    )
                }
                Divider()
                ToggleRow("Pan & zoom", "Gentle Ken Burns movement", s.slideshowKenBurns) { s.slideshowKenBurns = it }
                Divider()
                ToggleRow("Shuffle", "Show photos in random order", s.slideshowShuffle) { s.slideshowShuffle = it }
            }

            // ---------- Memories ----------
            Group("Memories") {
                ToggleRow("On This Day reminder", "A notification in the morning when you have photos from this day in past years", s.memoriesNotify) { on ->
                    if (!on) vm.setMemoriesNotify(false)
                    else if (android.os.Build.VERSION.SDK_INT >= 33 && !io.github.drtron1c.cloudphotos.memories.OnThisDayWorker.canNotify(context)) {
                        vm.expectingExternal = true
                        notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else vm.setMemoriesNotify(true)
                }
                Divider()
                Segmented(
                    "Music",
                    io.github.drtron1c.cloudphotos.memories.MemoryMusic.STYLES + listOf("custom" to "Yours", "none" to "None"),
                    s.memoryMusic,
                ) { choice ->
                    if (choice == "custom" && s.memoryMusicUri.isEmpty()) {
                        vm.expectingExternal = true
                        musicPicker.launch(arrayOf("audio/*"))
                    } else s.memoryMusic = choice
                }
                if (s.memoryMusic == "custom") {
                    Divider()
                    ValueRow("Your song", s.memoryMusicName.ifBlank { "Choose…" }) {
                        vm.expectingExternal = true
                        musicPicker.launch(arrayOf("audio/*"))
                    }
                }
            }
            Footer("Memories are made on this device. The built-in music is composed by the app itself; your own song is played from your device and never uploaded. The reminder shows no photo, and nothing about it on the lock screen.")

            // ---------- Places ----------
            Group("Places") {
                ToggleRow("Show map", "Map pictures come from Esri (ArcGIS) — their servers see which map areas you look at, never your photos", s.placesMap) { s.placesMap = it }
            }
            Footer("Place names (“Athens, Greece”) are worked out on this device from a built-in list of towns (GeoNames, CC BY 4.0).")

            // ---------- Sharing ----------
            Group("Sharing & exporting") {
                ToggleRow("Share as Most Compatible", "Turn HEIC photos into JPEG when sharing or copying, like the iPhone's “Automatic” transfer setting", s.shareCompatible) { s.shareCompatible = it }
            }
            Footer("Export lets you choose each time. Select photos (or use an album's ••• menu) and pick Export… to copy them to any folder, USB drive, SD card or cloud drive.")

            // ---------- Widget ----------
            Group("Home-screen widget") {
                Segmented(
                    "Widget shows",
                    listOf("onthisday" to "On This Day", "favourites" to "Favourites", "random" to "Random"),
                    s.widgetMode,
                ) { vm.setWidgetMode(it) }
            }
            Footer("Long-press your home screen → Widgets → Cloud Photos to add it. It changes photo every few hours.")

            // ---------- Auto-upload ----------
            Group("Upload from this device") {
                ToggleRow(
                    "Upload new photos & videos",
                    "Add photos taken on this device to iCloud Photos automatically",
                    s.autoUpload,
                    enabled = vm.canUpload || s.autoUpload,
                ) { on ->
                    if (!on) vm.setAutoUpload(false)
                    else if (AutoUploadWorker.hasMediaAccess(context)) dialog = "autoupload"
                    else {
                        vm.expectingExternal = true
                        permissionLauncher.launch(AutoUploadWorker.permissions())
                    }
                }
                if (s.autoUpload) {
                    Divider()
                    ToggleRow("Wi-Fi only", "Don't use mobile data for uploads", s.autoUploadWifiOnly) { vm.setAutoUploadWifiOnly(it) }
                    Divider()
                    ToggleRow("Include screenshots", "Upload screenshots too", s.autoUploadScreenshots) { s.autoUploadScreenshots = it }
                    val status = remember(tick) { vm.autoUploadStatus() }
                    if (status.isNotBlank()) {
                        Divider()
                        Text(status, color = p.secondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                    }
                }
            }
            Footer("Photos you saved from iCloud with this app are never uploaded again.")

            // ---------- Offline ----------
            Group("Available offline") {
                if (vm.offlineAlbums.isEmpty()) {
                    Text(
                        "No albums kept offline. Open an album, tap ••• and choose “Keep Offline”.",
                        color = p.secondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                    )
                } else {
                    vm.offlineAlbums.forEachIndexed { i, spec ->
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(spec.title, color = p.label, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.setOffline(spec, false) }) {
                                Icon(Icons.Outlined.Close, "Stop keeping offline", tint = p.secondaryLabel)
                            }
                        }
                    }
                }
                Divider()
                ToggleRow("Include videos", "Videos use a lot more space", s.offlineVideos) {
                    s.offlineVideos = it
                    OfflineWorker.syncNow(context)
                }
                Divider()
                ValueRow("Space used", formatBytes(offlineBytes).ifEmpty { "0 KB" }, onClick = null)
                val status = remember(tick) { vm.offlineStatus() }
                if (status.isNotBlank()) {
                    Divider()
                    Text(status, color = p.secondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                }
                if (vm.offlineAlbums.isNotEmpty()) {
                    Divider()
                    ActionRow("Download now", p.accent) { OfflineWorker.syncNow(context); vm.toast("Downloading in the background…") }
                    Divider()
                    ActionRow("Remove all offline copies", p.destructive) { dialog = "clearoffline" }
                }
            }
            Footer("Offline copies live in this app's private storage (other apps can't read them), open without internet, and are deleted when you sign out.")

            // ---------- Privacy ----------
            Group("Privacy & security") {
                val vpnState by vm.vpn.state.collectAsState()
                val vpnAccount by vm.vpn.account.collectAsState()
                ValueRow(
                    "Proton VPN",
                    when {
                        vpnAccount == null -> "Not set up"
                        vpnState is VpnState.Connected -> "Connected · " + (vpnState as VpnState.Connected).server.country
                        vpnState is VpnState.Connecting -> "Connecting…"
                        else -> "Off"
                    },
                ) { vm.open(Route.Vpn) }
                Divider()
                ValueRow("Encrypted DNS", DNS_OPTIONS.firstOrNull { it.first == s.dnsProvider }?.second ?: s.dnsProvider) { dialog = "dns" }
                Divider()
                ToggleRow(
                    "Hide in Recents",
                    if (Build.VERSION.SDK_INT >= 33) "Blank preview in the app switcher" else "Blank preview in the app switcher (also blocks screenshots)",
                    s.hideInRecents,
                ) { s.hideInRecents = it }
                Divider()
                ToggleRow("Lock Cloud Photos", "Ask for fingerprint, face or PIN every time you open the app", s.lockApp) { on ->
                    if (activity != null && on) {
                        vm.expectingExternal = true
                        activity.requireUnlock("Turn on app lock") { vm.changeLockApp(true) }
                    } else vm.changeLockApp(on)
                }
                Divider()
                ToggleRow("Lock Hidden & Recently Deleted", "Like Face ID for these albums on iPhone", s.lockPrivateAlbums) { on ->
                    if (activity != null && !on) {
                        vm.expectingExternal = true
                        activity.requireUnlock("Turn off album lock") { vm.changeLockPrivateAlbums(false) }
                    } else vm.changeLockPrivateAlbums(on)
                }
                Divider()
                ToggleRow("Recently Viewed album", "Keep a list of what you've opened (on this device only)", s.recentlyViewedEnabled) {
                    s.recentlyViewedEnabled = it
                    if (!it) vm.clearRecentlyViewed()
                }
                if (s.recentlyViewedEnabled && vm.recentlyViewed.isNotEmpty()) {
                    Divider()
                    ActionRow("Clear Recently Viewed", p.accent) { vm.clearRecentlyViewed(); vm.toast("Recently Viewed cleared") }
                }
                Divider()
                ActionRow("Clear cached photos", p.accent) { vm.clearImageCache() }
            }
            Footer(
                "Photos and videos are fetched over encrypted HTTPS straight from Apple, and DNS look-ups are encrypted too, so your Wi-Fi " +
                    "provider can't see which photo or video you open. They can still see that this device is talking to Apple's iCloud " +
                    "servers and how much data moves — use a VPN if you want to hide that as well. The app has no web browser inside it, " +
                    "so nothing you view is ever saved in any browser history."
            )

            // ---------- Smart search ----------
            Group("Smart Search (on-device AI)") {
                val modelState by vm.smart.modelState.collectAsState()
                val analysed by vm.smart.indexed.collectAsState()
                ToggleRow(
                    "Search what's in photos",
                    "e.g. “red hat late 2026”. One-time ${megabytes(vm.smart.downloadSize)} download, then everything runs on this device",
                    s.aiSearch,
                ) { vm.setAiSearch(it) }
                if (s.aiSearch) {
                    Divider()
                    val status = remember(tick, modelState, analysed) {
                        when (modelState) {
                            is ModelState.Downloading -> "Downloading the model… ${megabytes((modelState as ModelState.Downloading).bytes)} of ${megabytes(vm.smart.downloadSize)}"
                            is ModelState.Failed -> "Download failed: ${(modelState as ModelState.Failed).message}"
                            else -> vm.aiStatus().ifBlank { "%,d photos analysed".format(analysed) }
                        }
                    }
                    Text(status, color = p.secondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                    Divider()
                    ToggleRow("Only while charging", "Analyse photos only when plugged in", s.aiChargingOnly) { vm.setAiChargingOnly(it) }
                }
                if (s.aiSearch || analysed > 0 || modelState !is ModelState.Missing) {
                    Divider()
                    ActionRow("Delete AI Model and Analysis", p.destructive) { vm.deleteAiData() }
                }
                Divider()
                val faceState by vm.vision.facePack.state.collectAsState()
                val facesDone by vm.vision.facesDone.collectAsState()
                ToggleRow(
                    "People & Pets",
                    "Find and group faces so you can name people. One-time ${megabytes(vm.vision.facePack.downloadSize)} download" +
                        when (val f = faceState) {
                            is ModelState.Downloading -> " · downloading ${megabytes(f.bytes)}"
                            is ModelState.Failed -> " · download failed"
                            else -> if (s.aiPeople) " · %,d photos looked at".format(facesDone) else ""
                        },
                    s.aiPeople,
                ) { vm.setAiPeople(it) }
                if (s.aiPeople || facesDone > 0 || faceState !is ModelState.Missing) {
                    Divider()
                    ActionRow("Delete Faces and Names", p.destructive) { vm.deletePeopleData() }
                }
                Divider()
                val textState by vm.vision.textPack.state.collectAsState()
                val textDone by vm.vision.textDone.collectAsState()
                ToggleRow(
                    "Text in photos",
                    "Read signs, documents and screenshots so you can search and copy their text. One-time ${megabytes(vm.vision.textPack.downloadSize)} download" +
                        when (val t = textState) {
                            is ModelState.Downloading -> " · downloading ${megabytes(t.bytes)}"
                            is ModelState.Failed -> " · download failed"
                            else -> if (s.aiText) " · %,d photos read".format(textDone) else ""
                        },
                    s.aiText,
                ) { vm.setAiText(it) }
                if (s.aiText || textDone > 0 || textState !is ModelState.Missing) {
                    Divider()
                    ActionRow("Delete Text Read from Photos", p.destructive) { vm.deleteTextData() }
                }
            }
            Footer(
                "Everything is analysed on this device and stored encrypted in the app — nothing about your photos, faces, text or searches is ever sent anywhere. " +
                    "Hidden and Recently Deleted photos are never analysed, and what was learned about a photo is wiped as soon as you hide or delete it. " +
                    "The only downloads are the models themselves, once, from Hugging Face: Apple's MobileCLIP (search), OpenCV's YuNet and SFace (faces), PaddleOCR (text: Latin letters, numbers and Chinese)."
            )

            // ---------- Updates ----------
            Group("Updates") {
                val update by vm.updates.state.collectAsState()
                Column(Modifier.padding(16.dp)) {
                    Text("Installed: build ${BuildConfig.VERSION_CODE}", color = p.label, fontSize = 17.sp)
                    Text(
                        updateStatusLine(update, System.currentTimeMillis()),
                        color = if (update is UpdateState.Failed) p.destructive else p.secondaryLabel, fontSize = 13.sp,
                    )
                }
                Divider()
                ActionRow("Check for Updates", p.accent) { vm.updates.checkNow() }
                Divider()
                ToggleRow("Check when the app opens", "Shows a banner at the top when a newer build is on GitHub", s.checkUpdates) { s.checkUpdates = it }
                Divider()
                ActionRow("Change Log", p.accent) { dialog = "changelog" }
            }

            // ---------- Sign-in ----------
            Group("Sign-in") {
                if (vm.rememberPassword) {
                    ActionRow("Forget saved password", p.accent) { vm.forgetPassword(); vm.toast("Saved password removed") }
                } else {
                    Text(
                        "Your password isn't saved. When your iCloud session expires (roughly every couple of months) you'll be asked to sign in again.",
                        color = p.secondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                    )
                }
                if (!vm.offlineMode) {
                    Divider()
                    ActionRow("Refresh library", p.accent) { vm.refreshAll(); vm.toast("Refreshing…") }
                }
            }

            Group(null) {
                ActionRow("Sign Out", p.destructive) { confirmSignOut = true }
            }

            Text(
                "Cloud Photos ${BuildConfig.VERSION_NAME}\nUnofficial iCloud Photos client — not made by or affiliated with Apple. " +
                    "It talks only to Apple's own iCloud servers, the same way icloud.com does.",
                color = p.secondaryLabel, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }

    if (showShortcutList) ShortcutsDialog { showShortcutList = false }
    when (dialog) {
        "mediacache" -> ChoiceDialog(
            title = "Cache videos & GIFs",
            options = MEDIA_CACHE_SIZES,
            selected = s.mediaCacheMb.toString(),
            footer = "The most space cached videos may take. The least recently watched go first.",
            onDismiss = { dialog = null },
        ) { s.mediaCacheMb = it.toInt(); vm.applyMediaCache(); dialog = null }
        "mediakeep" -> ChoiceDialog(
            title = "Keep cached videos",
            options = MEDIA_CACHE_KEEPS,
            selected = s.mediaCacheKeep,
            onDismiss = { dialog = null },
        ) { s.mediaCacheKeep = it; vm.applyMediaCache(); dialog = null }
        "dns" -> ChoiceDialog(
            title = "Encrypted DNS",
            options = DNS_OPTIONS,
            selected = s.dnsProvider,
            footer = "Looks up Apple's server addresses over HTTPS so your Wi-Fi or mobile provider can't log them. If a network blocks it, pick another provider.",
            onDismiss = { dialog = null },
        ) { vm.setDns(it); dialog = null }
        "autoupload" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Upload which photos?") },
            text = { Text("Upload only photos and videos taken from now on, or everything already on this device too? Items already in iCloud are skipped.") },
            confirmButton = { TextButton(onClick = { vm.setAutoUpload(true, fromNow = true); dialog = null }) { Text("From Now On") } },
            dismissButton = { TextButton(onClick = { vm.setAutoUpload(true, fromNow = false); dialog = null }) { Text("Everything") } },
        )
        "changelog" -> {
            val entries = remember { loadChangelog(context) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Change Log") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        ChangeList(entries, showHeadings = true)
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            )
        }
        "clearoffline" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Remove offline copies?") },
            text = { Text("Photos stay in iCloud — only the copies saved on this device are removed.") },
            confirmButton = { TextButton(onClick = { vm.clearOffline(); dialog = null }) { Text("Remove", color = p.destructive) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("This removes your iCloud session, saved password, offline copies and trusted-device status from this app. Your photos stay safe in iCloud.") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign Out", color = p.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

private val MEDIA_CACHE_SIZES = listOf(
    "0" to "Off",
    "512" to "Up to 512 MB",
    "1024" to "Up to 1 GB",
    "2048" to "Up to 2 GB",
    "5120" to "Up to 5 GB",
    "10240" to "Up to 10 GB",
)

private val MEDIA_CACHE_KEEPS = listOf(
    "session" to "Until the app closes",
    "day" to "1 day",
    "week" to "1 week",
    "month" to "30 days",
)

/** Pick one option from a short list. */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    footer: String? = null,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((key, label) in options) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(key) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = key == selected, onClick = { onPick(key) })
                        Text(label, color = p.label, fontSize = 16.sp)
                    }
                }
                if (footer != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(footer, color = p.secondaryLabel, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Group(title: String?, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Spacer(Modifier.height(22.dp))
    if (title != null) {
        Text(
            title.uppercase(), color = p.secondaryLabel, fontSize = 13.sp,
            modifier = Modifier.padding(start = 32.dp, bottom = 6.dp),
        )
    }
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.card)
    ) { content() }
}

@Composable
private fun Group(title: String?, footer: String, content: @Composable () -> Unit) {
    Group(title, content)
    Footer(footer)
}

@Composable
private fun Footer(text: String) {
    Text(
        text, color = LocalPalette.current.secondaryLabel, fontSize = 13.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = LocalPalette.current.separator)
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) p.label else p.secondaryLabel, fontSize = 17.sp)
            Text(subtitle, color = p.secondaryLabel, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: (() -> Unit)?) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = p.label, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(value, color = p.secondaryLabel, fontSize = 15.sp)
        if (onClick != null) Text("  ›", color = p.secondaryLabel, fontSize = 18.sp)
    }
}

@Composable
private fun Segmented(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, color = p.label, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(p.placeholder).padding(2.dp),
        ) {
            for ((key, label) in options) {
                val on = key == selected
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (on) p.card else Color.Transparent)
                        .clickable { onPick(key) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = p.label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, color: Color, onClick: () -> Unit) {
    Text(
        title, color = color, fontSize = 17.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    )
}
