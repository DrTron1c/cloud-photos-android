package io.github.drtron1c.cloudphotos.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.drtron1c.cloudphotos.photos.Asset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class EditTool { Adjust, Filters, Crop }

/** Aspect presets for cropping; ratio is width / height, null = freeform, 0 = the photo's own. */
private val CROP_ASPECTS = listOf<Pair<String, Float?>>(
    "Freeform" to null, "Original" to 0f, "Square" to 1f,
    "16:9" to 16f / 9f, "4:3" to 4f / 3f, "3:2" to 3f / 2f, "9:16" to 9f / 16f, "3:4" to 3f / 4f,
)

/**
 * Photo editor: light/colour adjustments, filters, crop and rotate. Like "Duplicate & Edit"
 * on iPhone the result is saved as a new photo, so the original is never touched.
 */
@Composable
fun EditorScreen(vm: MainViewModel, asset: Asset, onClose: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var full by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }

    var tool by remember { mutableStateOf(EditTool.Adjust) }
    var filter by remember { mutableStateOf(EditFilter.Original) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(0f) }
    var warmth by remember { mutableFloatStateOf(0f) }
    // Crop as fractions of the (rotated) image.
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var turns by remember { mutableIntStateOf(0) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(asset.id) {
        val image = asset.displayImage
        if (image == null) { failed = true; return@LaunchedEffect }
        val result = runCatching {
            vm.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(vm.model(image))
                    .diskCacheKey(image.checksum)
                    .size(4096)
                    .allowHardware(false)
                    .build()
            )
        }.getOrNull()
        val bmp = (result as? SuccessResult)?.image?.toBitmap()
        if (bmp == null) { failed = true; return@LaunchedEffect }
        val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        full = soft
        withContext(Dispatchers.Default) {
            preview = scaled(soft, 2048).asImageBitmap()
            thumb = scaled(soft, 180).asImageBitmap()
        }
    }

    val matrix = remember(filter, exposure, contrast, saturation, warmth) {
        EditMath.build(filter, exposure, contrast, saturation, warmth)
    }
    val changed = filter != EditFilter.Original || exposure != 0f || contrast != 0f || saturation != 0f ||
        warmth != 0f || crop != Rect(0f, 0f, 1f, 1f) || turns % 4 != 0

    fun rotate() {
        val f = full ?: return
        full = EditMath.rotate90(f)
        turns++
        preview = preview?.let { pv -> EditMath.rotate90(pv.asAndroidBitmap()).asImageBitmap() }
        thumb = thumb?.let { t -> EditMath.rotate90(t.asAndroidBitmap()).asImageBitmap() }
        // Turn the crop box with the picture (90° clockwise).
        crop = Rect(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
        aspect = aspect?.let { if (it > 0f) 1f / it else it }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(top = top, bottom = bottom)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (changed) confirmDiscard = true else onClose() }) {
                Text("Cancel", color = Color.White, fontSize = 17.sp)
            }
            Spacer(Modifier.weight(1f))
            if (changed) {
                TextButton(onClick = {
                    repeat((4 - turns % 4) % 4) { rotate() }
                    turns = 0
                    exposure = 0f; contrast = 0f; saturation = 0f; warmth = 0f
                    filter = EditFilter.Original; crop = Rect(0f, 0f, 1f, 1f); aspect = null
                }) { Text("Revert", color = Color(0xFFFF453A), fontSize = 16.sp) }
            }
            TextButton(
                enabled = full != null && changed,
                onClick = {
                    val f = full ?: return@TextButton
                    vm.saveEdit(asset, f, floatArrayOf(crop.left, crop.top, crop.right, crop.bottom), matrix) { onClose() }
                },
            ) { Text("Save as Copy", color = if (full != null && changed) Color(0xFFFFCC00) else Color.Gray, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val pv = preview
            when {
                failed -> Text("This photo can't be edited here.", color = Color.Gray)
                pv == null -> CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 2.5.dp)
                else -> EditCanvas(
                    image = pv,
                    colorFilter = if (showOriginal) null else ColorFilter.colorMatrix(ColorMatrix(matrix)),
                    crop = crop,
                    cropping = tool == EditTool.Crop,
                    aspect = aspect?.let { if (it == 0f) pv.width.toFloat() / pv.height else it },
                    onCrop = { crop = it },
                    onToggleOriginal = { showOriginal = it },
                )
            }
        }

        // Tool panel
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            when (tool) {
                EditTool.Adjust -> {
                    AdjustSlider("Exposure", exposure) { exposure = it }
                    AdjustSlider("Contrast", contrast) { contrast = it }
                    AdjustSlider("Saturation", saturation) { saturation = it }
                    AdjustSlider("Warmth", warmth) { warmth = it }
                }
                EditTool.Filters -> {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        for (f in EditFilter.entries) {
                            Column(
                                Modifier.clickable { filter = f },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                val t = thumb
                                Box(
                                    Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                                        .border(if (filter == f) 2.dp else 0.dp, if (filter == f) Color(0xFFFFCC00) else Color.Transparent, RoundedCornerShape(8.dp))
                                        .background(Color.DarkGray),
                                ) {
                                    if (t != null) {
                                        Image(
                                            t, f.title,
                                            contentScale = ContentScale.Crop,
                                            colorFilter = ColorFilter.colorMatrix(ColorMatrix(EditMath.build(f, 0f, 0f, 0f, 0f))),
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(f.title.uppercase(), color = if (filter == f) Color(0xFFFFCC00) else Color.LightGray, fontSize = 10.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                EditTool.Crop -> {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(onClick = { rotate() }) {
                            Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate", tint = Color.White)
                        }
                        for ((label, ratio) in CROP_ASPECTS) {
                            val selected = aspect == ratio
                            Text(
                                label,
                                color = if (selected) Color.Black else Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) Color(0xFFFFCC00) else Color(0xFF2C2C2E))
                                    .clickable {
                                        aspect = ratio
                                        val pv = preview
                                        if (ratio != null && pv != null) {
                                            val r = if (ratio == 0f) pv.width.toFloat() / pv.height else ratio
                                            crop = centeredCrop(pv.width.toFloat(), pv.height.toFloat(), r)
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Drag the corners to crop, or drag inside the box to move it.",
                        color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Icons.Outlined.Tune, "Adjust", tool == EditTool.Adjust) { tool = EditTool.Adjust }
            ToolButton(Icons.Outlined.AutoFixHigh, "Filters", tool == EditTool.Filters) { tool = EditTool.Filters }
            ToolButton(Icons.Outlined.Crop, "Crop", tool == EditTool.Crop) { tool = EditTool.Crop }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard Changes", color = p.destructive) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep Editing") } },
        )
    }
}

private fun scaled(src: Bitmap, maxSide: Int): Bitmap {
    val m = max(src.width, src.height)
    if (m <= maxSide) return src.copy(Bitmap.Config.ARGB_8888, false)
    val s = maxSide.toFloat() / m
    return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt().coerceAtLeast(1), (src.height * s).roundToInt().coerceAtLeast(1), true)
}


/** Largest centred crop of [ratio] (w/h) inside a w×h image, as fractions. */
private fun centeredCrop(w: Float, h: Float, ratio: Float): Rect {
    var cw = w
    var ch = w / ratio
    if (ch > h) { ch = h; cw = h * ratio }
    val l = (w - cw) / 2f / w
    val t = (h - ch) / 2f / h
    return Rect(l, t, l + cw / w, t + ch / h)
}

@Composable
private fun EditCanvas(
    image: ImageBitmap,
    colorFilter: ColorFilter?,
    crop: Rect,
    cropping: Boolean,
    aspect: Float?,
    onCrop: (Rect) -> Unit,
    onToggleOriginal: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
        val cw = with(density) { maxWidth.toPx() }
        val ch = with(density) { maxHeight.toPx() }
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        // What's on screen: the whole photo while cropping, otherwise just the cropped part.
        val srcW = if (cropping) iw else iw * crop.width
        val srcH = if (cropping) ih else ih * crop.height
        val scale = min(cw / srcW, ch / srcH)
        val dw = srcW * scale
        val dh = srcH * scale
        val ox = (cw - dw) / 2f
        val oy = (ch - dh) / 2f
        val latestCrop by rememberUpdatedState(crop)
        val handle = with(density) { 28.dp.toPx() }
        val minFrac = 0.08f

        val gestures = if (cropping) {
            Modifier.pointerInput(iw, ih, aspect, cw, ch) {
                var mode = 0 // 0 move, 1 TL, 2 TR, 3 BL, 4 BR
                var cropState = latestCrop
                detectDragGestures(
                    onDragStart = { pos ->
                        cropState = latestCrop
                        val r = cropState
                        val l = ox + r.left * dw; val t = oy + r.top * dh
                        val rr = ox + r.right * dw; val b = oy + r.bottom * dh
                        mode = when {
                            abs(pos.x - l) < handle && abs(pos.y - t) < handle -> 1
                            abs(pos.x - rr) < handle && abs(pos.y - t) < handle -> 2
                            abs(pos.x - l) < handle && abs(pos.y - b) < handle -> 3
                            abs(pos.x - rr) < handle && abs(pos.y - b) < handle -> 4
                            else -> 0
                        }
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        val dx = drag.x / dw
                        val dy = drag.y / dh
                        val r = cropState
                        val next = if (mode == 0) {
                            val mx = dx.coerceIn(-r.left, 1f - r.right)
                            val my = dy.coerceIn(-r.top, 1f - r.bottom)
                            Rect(r.left + mx, r.top + my, r.right + mx, r.bottom + my)
                        } else {
                            var l = r.left; var t = r.top; var rr = r.right; var b = r.bottom
                            when (mode) {
                                1 -> { l += dx; t += dy }
                                2 -> { rr += dx; t += dy }
                                3 -> { l += dx; b += dy }
                                4 -> { rr += dx; b += dy }
                            }
                            l = l.coerceIn(0f, rr - minFrac); rr = rr.coerceIn(l + minFrac, 1f)
                            t = t.coerceIn(0f, b - minFrac); b = b.coerceIn(t + minFrac, 1f)
                            if (aspect != null) {
                                // Keep the chosen shape: height follows width (in pixels).
                                val wPx = (rr - l) * iw
                                var hFrac = wPx / aspect / ih
                                var wFrac = rr - l
                                if (hFrac > 1f) { hFrac = 1f; wFrac = ih * aspect / iw }
                                when (mode) {
                                    1, 2 -> { t = (b - hFrac).coerceAtLeast(0f); b = t + hFrac }
                                    else -> { b = (t + hFrac).coerceAtMost(1f); t = b - hFrac }
                                }
                                if (mode == 1 || mode == 3) l = rr - wFrac else rr = l + wFrac
                                if (l < 0f || rr > 1f || t < 0f || b > 1f) return@detectDragGestures
                            }
                            Rect(l, t, rr, b)
                        }
                        cropState = next
                        onCrop(next)
                    },
                )
            }
        } else {
            Modifier.pointerInput(Unit) {
                detectTapGestures(onPress = {
                    // Press and hold to compare with the original, like iPhone.
                    onToggleOriginal(true)
                    tryAwaitRelease()
                    onToggleOriginal(false)
                })
            }
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val srcOffset = if (cropping) IntOffset.Zero else IntOffset((crop.left * iw).roundToInt(), (crop.top * ih).roundToInt())
            val srcSize = IntSize(srcW.roundToInt().coerceIn(1, image.width - srcOffset.x), srcH.roundToInt().coerceIn(1, image.height - srcOffset.y))
            drawImage(
                image = image,
                srcOffset = srcOffset,
                srcSize = srcSize,
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                colorFilter = colorFilter,
                filterQuality = FilterQuality.High,
            )
            if (cropping) {
                val l = ox + crop.left * dw; val t = oy + crop.top * dh
                val r = ox + crop.right * dw; val b = oy + crop.bottom * dh
                val dim = Color.Black.copy(alpha = 0.55f)
                drawRect(dim, Offset(ox, oy), Size(dw, t - oy))
                drawRect(dim, Offset(ox, b), Size(dw, oy + dh - b))
                drawRect(dim, Offset(ox, t), Size(l - ox, b - t))
                drawRect(dim, Offset(r, t), Size(ox + dw - r, b - t))
                drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 1.5.dp.toPx()))
                // Rule-of-thirds guides.
                for (i in 1..2) {
                    val x = l + (r - l) * i / 3f
                    val y = t + (b - t) * i / 3f
                    drawLine(Color.White.copy(alpha = 0.4f), Offset(x, t), Offset(x, b), 1f)
                    drawLine(Color.White.copy(alpha = 0.4f), Offset(l, y), Offset(r, y), 1f)
                }
                val len = 20.dp.toPx(); val w = 4.dp.toPx()
                for ((cx, cy, sx, sy) in listOf(
                    listOf(l, t, 1f, 1f), listOf(r, t, -1f, 1f), listOf(l, b, 1f, -1f), listOf(r, b, -1f, -1f),
                )) {
                    drawLine(Color.White, Offset(cx, cy), Offset(cx + len * sx, cy), w)
                    drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + len * sy), w)
                }
            }
        }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(84.dp))
        Slider(
            value = value,
            onValueChange = { onChange(if (abs(it) < 0.04f) 0f else it) },
            valueRange = -1f..1f,
            colors = SliderDefaults.colors(thumbColor = Color(0xFFFFCC00), activeTrackColor = Color(0xFFFFCC00), inactiveTrackColor = Color(0xFF3A3A3C)),
            modifier = Modifier.weight(1f).height(36.dp),
        )
        Text(
            (value * 100).roundToInt().toString(),
            color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.width(36.dp).padding(start = 6.dp),
        )
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Color(0xFFFFCC00) else Color.LightGray
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}
