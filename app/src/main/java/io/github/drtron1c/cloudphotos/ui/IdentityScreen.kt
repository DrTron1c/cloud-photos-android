package io.github.drtron1c.cloudphotos.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.drtron1c.cloudphotos.identity.CustomLauncher
import io.github.drtron1c.cloudphotos.identity.IconArt
import io.github.drtron1c.cloudphotos.identity.LauncherApk
import io.github.drtron1c.cloudphotos.settings.AppIdentity
import io.github.drtron1c.cloudphotos.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Your own app name (typed with any keyboard) and picture. Applied as a small companion app
 * so it shows on the home screen and in the app drawer, or as a home-screen shortcut.
 */
@Composable
fun IdentityScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val s = vm.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var name by remember { mutableStateOf(s.customName.ifBlank { "Cloud Photos" }) }
    // "cloud" = built-in cloud in a colour, "picture" = your own image.
    var mode by remember { mutableStateOf(if (CustomLauncher.iconFile(context).exists()) "saved" else "cloud") }
    var colour by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf<Bitmap?>(null) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxPx by remember { mutableFloatStateOf(1f) }
    val nameFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        saved = withContext(Dispatchers.IO) { CustomLauncher.savedIcon(context) }
        if (saved == null && mode == "saved") mode = "cloud"
    }

    fun loadPicture(model: Any) {
        loading = true
        scope.launch {
            val result = runCatching {
                vm.imageLoader.execute(
                    ImageRequest.Builder(context).data(model).size(1600).allowHardware(false).build()
                )
            }.getOrNull()
            val bmp = (result as? SuccessResult)?.image?.toBitmap()
            loading = false
            if (bmp == null) {
                vm.toast("Couldn't open that picture"); return@launch
            }
            source = bmp
            zoom = 1f
            offset = Offset.Zero
            mode = "picture"
        }
    }

    // Picture sent from a photo's "Use as App Icon".
    LaunchedEffect(vm.iconSource) {
        val src = vm.iconSource ?: return@LaunchedEffect
        vm.iconSource = null
        loadPicture(src)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadPicture(uri)
    }

    // The finished 432×432 adaptive-icon picture.
    val icon: Bitmap? = remember(mode, colour, source, zoom, offset, boxPx, saved) {
        when (mode) {
            "picture" -> source?.let { IconArt.render(it, zoom, offset.x, offset.y, boxPx) }
            "saved" -> saved
            else -> AppIdentity.iconBitmap(context, AppSettings.ACCENTS[colour].second.toInt())
        }
    }
    val cleanName = LauncherApk.cleanName(name)
    val installed = vm.companionLabel != null

    fun doneTyping() {
        focus.clearFocus()
        keyboard?.hide()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.groupedBackground)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(top = top + 8.dp, bottom = bottom + 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(icon = Icons.AutoMirrored.Filled.ArrowBackIos, onDark = false, onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text("App Icon & Name", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = p.label)
            }

            // Home-screen preview.
            Box(
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2B5876), Color(0xFF4E4376), Color(0xFFB06AB3)))),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconPreview(icon, 76.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        cleanName.ifEmpty { "…" },
                        color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 140.dp),
                    )
                }
            }

            // ---------- Name ----------
            SectionTitle("Name")
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", "").take(LauncherApk.MAX_NAME) },
                    label = { Text("App name") },
                    placeholder = { Text("Type any name") },
                    singleLine = true,
                    supportingText = { Text("${name.length} / ${LauncherApk.MAX_NAME} · works with the on-screen or a physical keyboard") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { doneTyping() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocus)
                        // Physical keyboard: Enter finishes, Esc leaves the field.
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyUp && (e.key == Key.Escape || e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                                doneTyping(); true
                            } else false
                        },
                )
            }

            // ---------- Icon ----------
            SectionTitle("Icon")
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(p.placeholder).padding(2.dp),
                ) {
                    val options = buildList {
                        add("cloud" to "Cloud")
                        add("picture" to "Your Picture")
                        if (saved != null) add("saved" to "Current")
                    }
                    for ((key, label) in options) {
                        val on = mode == key
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (on) p.card else Color.Transparent)
                                .clickable { mode = key }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = p.label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                when (mode) {
                    "cloud" -> {
                        Text("Background colour", color = p.secondaryLabel, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppSettings.ACCENTS.forEachIndexed { i, (label, argb) ->
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape).background(Color(argb))
                                        .border(if (colour == i) 3.dp else 0.dp, if (colour == i) p.label else Color.Transparent, CircleShape)
                                        .clickable { colour = i },
                                    contentAlignment = Alignment.Center,
                                ) { if (colour == i) Icon(Icons.Filled.Check, label, tint = Color.White, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                    "saved" -> Text("The picture you used last time.", color = p.secondaryLabel, fontSize = 14.sp)
                    else -> {
                        val src = source
                        if (loading) {
                            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp)
                            }
                        } else if (src == null) {
                            Text(
                                "Pick any picture from this device — or open a photo in your library, tap ••• and choose “Use as App Icon”.",
                                color = p.secondaryLabel, fontSize = 14.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                vm.expectingExternal = true
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }, shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Outlined.Image, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Choose Picture")
                            }
                        } else {
                            Text("Pinch to zoom and drag to frame it.", color = p.secondaryLabel, fontSize = 13.sp)
                            Spacer(Modifier.height(10.dp))
                            CropBox(
                                src = src,
                                zoom = zoom,
                                offset = offset,
                                onSize = { boxPx = it },
                                onChange = { z, o -> zoom = z; offset = o },
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Zoom", color = p.secondaryLabel, fontSize = 13.sp)
                                Slider(
                                    value = zoom,
                                    onValueChange = { z ->
                                        zoom = z
                                        val (mx, my) = IconArt.maxOffset(src, z, boxPx)
                                        offset = Offset(offset.x.coerceIn(-mx, mx), offset.y.coerceIn(-my, my))
                                    },
                                    valueRange = 1f..5f,
                                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                )
                            }
                            TextButton(onClick = {
                                vm.expectingExternal = true
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) { Text("Choose Another Picture") }
                        }
                    }
                }
            }

            // ---------- Apply ----------
            SectionTitle("Put it on your home screen")
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card),
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (installed) {
                        Text("Installed as “${vm.companionLabel}”", color = p.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                    }
                    Button(
                        onClick = { doneTyping(); icon?.let { vm.installCompanion(name, it) } },
                        enabled = icon != null && cleanName.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(if (installed) "Update App" else "Add as App", fontSize = 16.sp) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Adds “${cleanName.ifEmpty { "…" }}” to your home screen and app drawer with this picture. It's a tiny companion app made on this device that just opens Cloud Photos. Android will ask you to confirm the install.",
                        color = p.secondaryLabel, fontSize = 13.sp,
                    )
                }
                HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = p.separator)
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setHideOriginal(!s.hideOriginalIcon) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide the original icon", color = p.label, fontSize = 17.sp)
                        Text(
                            "So only your version shows. It comes back by itself if you remove your app.",
                            color = p.secondaryLabel, fontSize = 13.sp,
                        )
                    }
                    Switch(checked = s.hideOriginalIcon, onCheckedChange = { vm.setHideOriginal(it) })
                }
                if (installed) {
                    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = p.separator)
                    Text(
                        "Remove My App", color = p.destructive, fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth().clickable { context.startActivity(vm.removeCompanionIntent()) }.padding(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(16.dp),
            ) {
                OutlinedButton(
                    onClick = { doneTyping(); icon?.let { vm.saveIdentity(name, it); vm.pinIdentityShortcut(name, it) } },
                    enabled = icon != null && cleanName.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add as Home-Screen Shortcut Instead") }
                Spacer(Modifier.height(6.dp))
                Text(
                    "A shortcut doesn't appear in the app drawer. On Xiaomi / HyperOS it needs “Home screen shortcuts” allowed: Settings › Apps › Cloud Photos › Other permissions.",
                    color = p.secondaryLabel, fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Your name is also used inside the app — on the lock screen and in the recent-apps list.",
                color = p.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp),
            )

            // ---------- Built-in names for the original icon ----------
            SectionTitle("Original icon's name")
            Row(
                Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((alias, label) in AppSettings.LAUNCHER_NAMES) {
                    val on = s.launcherName == alias
                    Text(
                        label,
                        color = if (on) Color.White else p.label,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) p.accent else p.card)
                            .clickable { vm.setLauncherName(alias) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Text(
                "Quick option without installing anything: renames the original Cloud Photos icon to one of these.",
                color = p.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp),
            )
        }
    }

    if (vm.needsInstallPermission) {
        AlertDialog(
            onDismissRequest = { vm.needsInstallPermission = false },
            title = { Text("Allow installing your app") },
            text = { Text("Android needs your OK for Cloud Photos to install the companion app. Turn on “Allow from this source”, then come back — it carries on by itself.") },
            confirmButton = {
                TextButton(onClick = { context.startActivity(vm.allowInstallsIntent()) }) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { vm.needsInstallPermission = false }) { Text("Cancel") } },
        )
    }
    if (vm.companionConflict) {
        AlertDialog(
            onDismissRequest = { vm.companionConflict = false },
            title = { Text("Remove the old version first") },
            text = { Text("A version made before (for example before Cloud Photos' data was cleared) is installed and can't be updated. Remove it, then tap “Add as App” again.") },
            confirmButton = {
                TextButton(onClick = { vm.companionConflict = false; context.startActivity(vm.removeCompanionIntent()) }) { Text("Remove Old Version") }
            },
            dismissButton = { TextButton(onClick = { vm.companionConflict = false }) { Text("Cancel") } },
        )
    }
}

/** The visible part of an adaptive-icon picture in a rounded-square icon shape. */
@Composable
fun IconPreview(icon: Bitmap?, size: androidx.compose.ui.unit.Dp) {
    val img = remember(icon) { icon?.asImageBitmap() }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(Color.DarkGray)) {
        if (img != null) {
            Image(
                img, null,
                contentScale = ContentScale.Crop,
                // Middle 2/3 of the 432 px picture is what launchers show.
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = IconArt.SIZE.toFloat() / IconArt.VISIBLE
                    scaleY = IconArt.SIZE.toFloat() / IconArt.VISIBLE
                },
            )
        }
    }
}

@Composable
private fun CropBox(src: Bitmap, zoom: Float, offset: Offset, onSize: (Float) -> Unit, onChange: (Float, Offset) -> Unit) {
    val p = LocalPalette.current
    val img = remember(src) { src.asImageBitmap() }
    var z by remember { mutableFloatStateOf(zoom) }
    var o by remember { mutableStateOf(offset) }
    z = zoom
    o = offset
    var box by remember { mutableFloatStateOf(1f) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(56.dp))
                .border(2.dp, p.accent, RoundedCornerShape(56.dp))
                .clipToBounds()
                .onSizeChanged { box = it.width.toFloat(); onSize(box) }
                .pointerInput(src) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        val nz = (z * gestureZoom).coerceIn(1f, 5f)
                        val (mx, my) = IconArt.maxOffset(src, nz, box)
                        val no = Offset((o.x + pan.x).coerceIn(-mx, mx), (o.y + pan.y).coerceIn(-my, my))
                        z = nz
                        o = no
                        onChange(nz, no)
                    }
                },
        ) {
            Image(
                img, null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = z
                    scaleY = z
                    translationX = o.x
                    translationY = o.y
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title.uppercase(), color = LocalPalette.current.secondaryLabel, fontSize = 13.sp,
        modifier = Modifier.padding(start = 32.dp, top = 22.dp, bottom = 6.dp),
    )
}
