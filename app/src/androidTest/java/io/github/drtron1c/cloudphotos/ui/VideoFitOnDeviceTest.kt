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
    private val kit = VideoTestKit(ctx, "test-fit")
    private val tag = kit.tag
    private fun video(w: Int, h: Int, rotated: Boolean = false) = kit.video(w, h, rotated)
    private fun shape(f: File) = kit.shape(f)
    private fun save(bmp: Bitmap, name: String) = kit.save(bmp, name)
    private fun bounds(b: Bitmap) = VideoTestKit.bounds(b)
    private fun yellow(c: Int) = VideoTestKit.yellow(c)
    private fun blue(c: Int) = VideoTestKit.blue(c)

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

    @Test
    fun videoBiggerThanTheSpaceIsShrunkToFitNotCropped() {
        // The usual case on a tablet: a phone video with more pixels than the space it's shown in.
        check("downscale-landscape", video(512, 288), 300, 200)
    }

    @Test
    fun rotatedPhoneVideoBiggerThanALandscapeSpace() {
        check("downscale-rotated", video(512, 288, rotated = true), 360, 200)
    }
}
