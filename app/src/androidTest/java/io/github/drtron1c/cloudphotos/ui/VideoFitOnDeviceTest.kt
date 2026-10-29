package io.github.drtron1c.cloudphotos.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A playing video is always shown whole — fitted inside its space, never cropped or zoomed —
 * whatever the shape of the video or of the space (portrait phone videos on a landscape tablet,
 * landscape videos on a portrait screen, small and large videos, files with a rotation flag).
 * Checked from the pixels on screen: every edge of the picture must be visible.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoFitOnDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "test-fit").apply { mkdirs() }
    private val tag = "CompanionTest"

    /** Blue inside, a yellow band round the edge (so a cropped edge is easy to spot). */
    private fun picture(w: Int, h: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(250, 220, 0))
        val band = min(w, h) * 0.14f
        c.drawRect(band, band, w - band, h - band, Paint().apply { color = Color.rgb(30, 120, 255) })
        return File(dir, "picture-${w}x$h.png").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun export(item: EditedMediaItem, out: File) = runBlocking(Dispatchers.Main) {
        out.delete()
        suspendCancellableCoroutine<Unit> { cont ->
            Transformer.Builder(ctx).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { cont.resume(Unit) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { cont.resumeWithException(exportException) }
            }).build().start(item, out.path)
        }
    }

    /** A 2-second test video; [rotated] stores landscape pixels with a 90° flag, like phone videos. */
    private fun video(w: Int, h: Int, rotated: Boolean = false): File {
        val plain = File(dir, "video-${w}x$h.mp4")
        export(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(picture(w, h)))).setDurationUs(2_000_000).setFrameRate(30).build(), plain)
        if (!rotated) return plain
        val out = File(dir, "video-${w}x$h-rotated.mp4")
        val turn: Effect = ScaleAndRotateTransformation.Builder().setRotationDegrees(90f).build()
        export(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(plain))).setEffects(Effects(listOf(), listOf(turn))).build(), out)
        return out
    }

    /** The picture's shape as it should appear (after the file's rotation flag). */
    private fun shape(f: File): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.path)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val turn = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        r.release()
        Log.i(tag, "FIT file ${f.name} stored ${w}x$h rotation $turn")
        return if (turn % 180 != 0) h to w else w to h
    }

    private fun save(bmp: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
        }
        runCatching {
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun bright(c: Int) = max(Color.red(c), max(Color.green(c), Color.blue(c))) > 70
    private fun yellow(c: Int) = Color.red(c) > 160 && Color.green(c) > 140 && Color.blue(c) < 120
    private fun blue(c: Int) = Color.blue(c) > 160 && Color.red(c) < 130

    /** Plays [file] inside a [boxW]×[boxH] pixel space and checks the whole picture is on screen. */
    private fun check(name: String, file: File, boxW: Int, boxH: Int) {
        val (vw, vh) = shape(file)
        lateinit var player: ExoPlayer
        rule.runOnUiThread {
            player = ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                playWhenReady = true
                prepare()
            }
        }
        rule.setContent {
            val d = LocalDensity.current
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
                Box(Modifier.size(with(d) { boxW.toDp() }, with(d) { boxH.toDp() }).testTag("space")) {
                    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 16f))
                    // The stored size is deliberately given sideways, like iCloud does for some phone videos.
                    FittedVideo(player = player, zoom = zoom, fallback = Size(vh.toFloat(), vw.toFloat()))
                }
            }
        }
        // Wait for the picture to settle.
        var shot: Bitmap? = null
        var last: List<Int>? = null
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            rule.waitForIdle()
            Thread.sleep(400)
            val b = rule.onNodeWithTag("space").captureToImage().asAndroidBitmap()
            val box = bounds(b)
            if (box != null && box == last) { shot = b; break }
            last = box
        }
        rule.runOnUiThread { player.release() }
        val b = shot ?: rule.onNodeWithTag("space").captureToImage().asAndroidBitmap()
        save(b, "video-fit-$name.png")
        val box = bounds(b)
        assertTrue("$name: no picture drawn", box != null)
        val (left, top, right, bottom) = box!!
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val fit = min(w / vw, h / vh)
        val ew = vw * fit
        val eh = vh * fit
        val gotW = (right - left + 1).toFloat()
        val gotH = (bottom - top + 1).toFloat()
        val tol = max(4f, 0.02f * max(ew, eh))
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        Log.i(tag, "FIT $name space ${b.width}x${b.height} video ${vw}x$vh expected ${ew.toInt()}x${eh.toInt()} got ${gotW.toInt()}x${gotH.toInt()} at $left,$top")
        assertTrue("$name: picture is ${gotW.toInt()}x${gotH.toInt()}, should be ${ew.toInt()}x${eh.toInt()} (whole video fitted)", abs(gotW - ew) <= tol && abs(gotH - eh) <= tol)
        assertTrue("$name: picture not centred ($cx,$cy)", abs(cx - w / 2) <= 4 && abs(cy - h / 2) <= 4)
        // All four yellow edges of the picture are visible, and the blue middle.
        val inset = min(ew, eh) * 0.14f * 0.45f
        val midX = cx.toInt()
        val midY = cy.toInt()
        val edges = mapOf(
            "top" to b.getPixel(midX, (top + inset).toInt()),
            "bottom" to b.getPixel(midX, (bottom - inset).toInt()),
            "left" to b.getPixel((left + inset).toInt(), midY),
            "right" to b.getPixel((right - inset).toInt(), midY),
        )
        for ((side, c) in edges) assertTrue("$name: $side edge missing (colour ${Integer.toHexString(c)})", yellow(c))
        assertTrue("$name: middle missing", blue(b.getPixel(midX, midY)))
    }

    /** The drawn picture's bounds within the black space: left, top, right, bottom. */
    private fun bounds(b: Bitmap): List<Int>? {
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = -1
        var btm = -1
        val row = IntArray(b.width)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            for (x in row.indices) if (bright(row[x])) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > btm) btm = y
            }
        }
        return if (r < 0) null else listOf(l, t, r, btm)
    }

    /** The space to test in: a share of the emulator's screen, landscape or portrait. */
    private fun space(wide: Float, tall: Float): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        val s = min(m.widthPixels, m.heightPixels) * 0.9f
        return (s * wide).toInt() to (s * tall).toInt()
    }

    @Test
    fun smallPortraitVideoInLandscapeSpace() {
        val (w, h) = space(1f, 0.5f)
        check("portrait-in-landscape", video(160, 288), w, h)
    }

    @Test
    fun smallLandscapeVideoInPortraitSpace() {
        val (w, h) = space(0.5f, 1f)
        check("landscape-in-portrait", video(288, 160), w, h)
    }

    @Test
    fun phoneVideoWithRotationFlagOnLandscapeTablet() {
        val (w, h) = space(1f, 0.625f)
        check("rotated-phone-on-tablet", video(320, 176, rotated = true), w, h)
    }

    @Test
    fun landscapeVideoOnSixteenByTenTablet() {
        val (w, h) = space(1f, 0.625f)
        check("landscape-on-tablet", video(640, 368), w, h)
    }

    @Test
    fun largeVideoBiggerThanTheSpace() {
        val (w, h) = space(1f, 0.4f)
        check("large", video(1280, 720), w, h)
    }
}
