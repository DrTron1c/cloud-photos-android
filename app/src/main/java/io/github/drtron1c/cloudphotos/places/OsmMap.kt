package io.github.drtron1c.cloudphotos.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import io.github.drtron1c.cloudphotos.BuildConfig
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/** A photo on the map. */
class MapPoint(val id: String, val lat: Double, val lon: Double)

/** Web Mercator: longitude / latitude ↔ 0…1 across the world. */
object Mercator {
    fun x(lon: Double) = (lon + 180.0) / 360.0
    fun y(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0
    }
    fun lat(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))
    fun lon(x: Double) = x * 360.0 - 180.0
}

/**
 * A small map drawn by the app itself (OpenStreetMap data, CARTO's label-free map pictures, place
 * names in English from the app's own town list). Map pictures load through the same
 * encrypted-DNS (and VPN) connection as everything else; CARTO's servers see which map squares
 * are shown — never your photos. Pinch to zoom, drag to move, tap a bubble to open it.
 */
@Composable
fun OsmMap(
    points: List<MapPoint>,
    geo: Geo?,
    imageLoader: ImageLoader,
    thumbnail: @Composable (String) -> Any?,
    onOpen: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val tilePx = 256f * max(1f, density.density / 1.6f)
    BoxWithConstraints(modifier.clipToBounds().background(Color(0xFFE8E4DC))) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        // Start framing all the photos.
        val start = remember(points.size) {
            if (points.isEmpty()) Triple(0.5, 0.4, 2f) else {
                val xs = points.map { Mercator.x(it.lon) }
                val ys = points.map { Mercator.y(it.lat) }
                val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-4)
                val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-4)
                val z = min(ln(w / (tilePx * spanX * 1.3)) / ln(2.0), ln(h / (tilePx * spanY * 1.3)) / ln(2.0))
                Triple((xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2, z.toFloat().coerceIn(1.5f, 15f))
            }
        }
        var cx by remember(start) { mutableStateOf(start.first) }
        var cy by remember(start) { mutableStateOf(start.second) }
        var zoom by remember(start) { mutableFloatStateOf(start.third) }
        val world = tilePx * 2.0.pow(zoom.toDouble())

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val worldNow = tilePx * 2.0.pow(zoom.toDouble())
                        // Keep the point under the fingers still while zooming.
                        val fx = cx + (centroid.x - w / 2) / worldNow
                        val fy = cy + (centroid.y - h / 2) / worldNow
                        val nz = (zoom + ln(gestureZoom.toDouble()).toFloat() / ln(2f)).coerceIn(1f, 19f)
                        val worldNew = tilePx * 2.0.pow(nz.toDouble())
                        cx = (fx - (centroid.x - w / 2) / worldNew - pan.x / worldNew).let { it - floor(it) }
                        cy = (fy - (centroid.y - h / 2) / worldNew - pan.y / worldNew).coerceIn(0.0, 1.0)
                        zoom = nz
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { zoom = (zoom + 1).coerceAtMost(19f) })
                },
        ) {
            // Tiles at the nearest whole zoom level, scaled to fit the zoom in between.
            val tz = floor(zoom).toInt().coerceIn(0, 19)
            val n = 1 shl tz
            val tileOnScreen = (world / n).toFloat()
            val left = cx * world - w / 2
            val top = cy * world - h / 2
            val x0 = floor(left / tileOnScreen).toInt()
            val y0 = floor(top / tileOnScreen).toInt().coerceAtLeast(0)
            val x1 = floor((left + w) / tileOnScreen).toInt()
            val y1 = floor((top + h) / tileOnScreen).toInt().coerceAtMost(n - 1)
            val sizeDp = with(density) { tileOnScreen.toDp() }
            for (ty in y0..y1) for (tx in x0..x1) {
                val wx = Math.floorMod(tx, n)
                // Map pictures without writing on them; the place names are drawn by the app, in English.
                val url = "https://${"abcd"[Math.floorMod(wx + ty, 4)]}.basemaps.cartocdn.com/rastertiles/voyager_nolabels/$tz/$wx/$ty@2x.png"
                val ox = (tx * tileOnScreen - left).roundToInt()
                val oy = (ty * tileOnScreen - top).roundToInt()
                AsyncImage(
                    model = remember(url) {
                        ImageRequest.Builder(context).data(url)
                            .httpHeaders(NetworkHeaders.Builder().set("User-Agent", USER_AGENT).build())
                            .memoryCacheKey(url).diskCacheKey(url).build()
                    },
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.offset { IntOffset(ox, oy) }.size(sizeDp + 1.dp),
                )
            }

            // English place names: countries when zoomed out, then bigger towns first.
            if (geo != null) {
                val labels = remember(geo, zoom.toInt(), (cx * 64).toInt(), (cy * 64).toInt(), w, h) {
                    val latTop = Mercator.lat((top / world).coerceIn(0.0, 1.0))
                    val latBottom = Mercator.lat(((top + h) / world).coerceIn(0.0, 1.0))
                    val lonLeft = Mercator.lon(((left / world) % 1.0 + 1.0) % 1.0)
                    val lonRight = Mercator.lon((((left + w) / world) % 1.0 + 1.0) % 1.0)
                    val minPop = when {
                        zoom < 3.5f -> 5_000_000; zoom < 4.5f -> 2_000_000; zoom < 5.5f -> 800_000
                        zoom < 6.5f -> 250_000; zoom < 7.5f -> 100_000; zoom < 8.5f -> 40_000; else -> 15_000
                    }
                    val countries = if (zoom < 6f) geo.countries.filter { it.lat in latBottom..latTop } else emptyList()
                    countries + geo.towns(latBottom, latTop, lonLeft, lonRight, minPop, 80)
                }
                val taken = HashSet<Pair<Int, Int>>()
                val cellW = with(density) { 110.dp.toPx() }
                val cellH = with(density) { 26.dp.toPx() }
                for (l in labels) {
                    var sx = Mercator.x(l.lon) * world - left
                    if (sx < 0) sx += world
                    if (sx > w) sx -= world
                    val sy = Mercator.y(l.lat) * world - top
                    if (sx < 0 || sy < 0 || sx > w || sy > h) continue
                    val key = (sx / cellW).toInt() to (sy / cellH).toInt()
                    if (!taken.add(key)) continue
                    Text(
                        if (l.country) l.name.uppercase() else l.name,
                        color = if (l.country) Color(0xFF5A5A66) else Color(0xFF26262C),
                        fontSize = if (l.country) 11.sp else 12.sp,
                        fontWeight = if (l.country) FontWeight.SemiBold else FontWeight.Medium,
                        letterSpacing = if (l.country) 1.sp else 0.sp,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(Color.White, blurRadius = 6f),
                        ),
                        maxLines = 1,
                        modifier = Modifier.offset { IntOffset(sx.roundToInt() - 20, sy.roundToInt() - 8) },
                    )
                }
            }

            // Photos grouped into bubbles ~70 dp apart.
            val cell = with(density) { 70.dp.toPx() }
            val clusters = remember(points, zoom, cx, cy) {
                val groups = HashMap<Pair<Int, Int>, MutableList<Pair<MapPoint, Pair<Float, Float>>>>()
                for (p in points) {
                    var sx = (Mercator.x(p.lon) * world - left)
                    // Wrap around the date line.
                    if (sx < -cell) sx += world
                    if (sx > w + cell) sx -= world
                    val sy = (Mercator.y(p.lat) * world - top)
                    if (sx < -cell || sy < -cell || sx > w + cell || sy > h + cell) continue
                    groups.getOrPut(floor(sx / cell).toInt() to floor(sy / cell).toInt()) { ArrayList() } += p to (sx.toFloat() to sy.toFloat())
                }
                groups.values.map { g ->
                    val mx = g.map { it.second.first }.average().toFloat()
                    val my = g.map { it.second.second }.average().toFloat()
                    Triple(g.map { it.first.id }, mx, my)
                }
            }
            val bubble = 52.dp
            val half = with(density) { (bubble / 2).roundToPx() }
            for ((ids, mx, my) in clusters) {
                Box(
                    Modifier
                        .offset { IntOffset(mx.roundToInt() - half, my.roundToInt() - half) }
                        .size(bubble)
                        .shadow(4.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                        .clickable { onOpen(ids) },
                ) {
                    val model = thumbnail(ids.first())
                    if (model != null) {
                        AsyncImage(model = model, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    if (ids.size > 1) {
                        Text(
                            if (ids.size > 999) "999+" else ids.size.toString(),
                            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp)
                                .background(Color(0xFF0A84FF), CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        // Required by OpenStreetMap's licence.
        Text(
            "© OpenStreetMap contributors © CARTO",
            color = Color.Black.copy(alpha = 0.75f), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private val USER_AGENT = "CloudPhotos/${BuildConfig.VERSION_NAME} (+https://github.com/DrTron1c/cloud-photos-android)"
