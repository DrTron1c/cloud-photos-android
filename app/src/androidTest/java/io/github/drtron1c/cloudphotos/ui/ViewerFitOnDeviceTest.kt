package io.github.drtron1c.cloudphotos.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.drtron1c.cloudphotos.photos.AlbumSpec
import io.github.drtron1c.cloudphotos.photos.Asset
import io.github.drtron1c.cloudphotos.photos.MediaList
import io.github.drtron1c.cloudphotos.photos.Version
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The real viewer — Liquid Glass, swipe-to-dismiss, the pager, the video controls — playing a
 * video (or GIF) from a file, checked from the pixels on screen: with the bars hidden, the whole
 * picture is fitted in the space, never cropped or zoomed, for videos smaller and larger than it.
 */
@RunWith(AndroidJUnit4::class)
class ViewerFitOnDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val kit = VideoTestKit(ctx, "test-viewer")

    private class OneItem(val a: Asset) : MediaList {
        override val key = "test-viewer"
        override val spec = AlbumSpec.virtual("test-viewer", "Test", "photo")
        override val count = 1
        override val refreshing = false
        override fun get(index: Int) = if (index == 0) a else null
        override fun ensure(index: Int) {}
        override fun indexOf(assetId: String) = if (assetId == a.id) 0 else -1
        override fun update(asset: Asset) {}
        override fun removeAll(ids: Set<String>) {}
        override fun refresh() {}
    }

    private fun asset(file: File, w: Int, h: Int, gif: Boolean = false): Asset {
        val type = if (gif) "com.compuserve.gif" else "public.mpeg-4"
        val v = Version(Uri.fromFile(file).toString(), file.length(), "test-" + file.name, type, w, h)
        return Asset(
            "test-${file.name}", "a-${file.name}", "CPLAsset", "tag", 0, 0, false, false, type, file.name, w, h,
            !gif, if (gif) null else 2.0, null, null, null, v, null, null,
        )
    }

    private fun check(name: String, file: File, vw: Int, vh: Int, spaceW: Int, spaceH: Int, gif: Boolean = false) {
        lateinit var vm: MainViewModel
        rule.runOnUiThread { vm = MainViewModel(ctx.applicationContext as Application) }
        // The stored size is given sideways, like iCloud does for some phone videos.
        val list = OneItem(asset(file, vh, vw, gif))
        rule.setContent {
            val d = LocalDensity.current
            CloudPhotosTheme(themeMode = "dark") {
                CompositionLocalProvider(LocalGlassEnabled provides true) {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(with(d) { spaceW.toDp() }, with(d) { spaceH.toDp() }).testTag("space")) {
                            GlassHost(Modifier.fillMaxSize(), content = { ViewerScreen(vm, list, 0, startFullScreen = true) {} }) {}
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(1500)
        // Full screen (bars and controls hidden), as after tapping the picture.
        var shot: Bitmap? = null
        var last: List<Int>? = null
        val until = System.currentTimeMillis() + 20_000
        Thread.sleep(800)
        while (System.currentTimeMillis() < until) {
            rule.waitForIdle()
            Thread.sleep(400)
            val b = rule.onNodeWithTag("space").captureToImage().asAndroidBitmap()
            val box = VideoTestKit.bounds(b)
            if (box != null && box == last) { shot = b; break }
            last = box
        }
        val b = shot ?: rule.onNodeWithTag("space").captureToImage().asAndroidBitmap()
        kit.save(b, "viewer-fit-$name.png")
        val box = VideoTestKit.bounds(b)
        assertTrue("$name: no picture drawn", box != null)
        val (left, top, right, bottom) = box!!
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val fit = min(w / vw, h / vh)
        val ew = vw * fit
        val eh = vh * fit
        val gotW = (right - left + 1).toFloat()
        val gotH = (bottom - top + 1).toFloat()
        val tol = max(5f, 0.025f * max(ew, eh))
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        Log.i(kit.tag, "VIEWER $name space ${b.width}x${b.height} video ${vw}x$vh expected ${ew.toInt()}x${eh.toInt()} got ${gotW.toInt()}x${gotH.toInt()} at $left,$top")
        assertTrue("$name: picture is ${gotW.toInt()}x${gotH.toInt()}, should be ${ew.toInt()}x${eh.toInt()} (whole video fitted)", abs(gotW - ew) <= tol && abs(gotH - eh) <= tol)
        assertTrue("$name: picture not centred ($cx,$cy)", abs(cx - w / 2) <= 5 && abs(cy - h / 2) <= 5)
        if (!gif) {
            val inset = min(ew, eh) * 0.14f * 0.45f
            val edges = mapOf(
                "top" to b.getPixel(cx.toInt(), (top + inset).toInt()),
                "bottom" to b.getPixel(cx.toInt(), (bottom - inset).toInt()),
                "left" to b.getPixel((left + inset).toInt(), cy.toInt()),
                "right" to b.getPixel((right - inset).toInt(), cy.toInt()),
            )
            for ((side, c) in edges) assertTrue("$name: $side edge missing (colour ${Integer.toHexString(c)})", VideoTestKit.yellow(c))
        }
    }

    private fun shaped(file: File): Triple<File, Int, Int> = kit.shape(file).let { (w, h) -> Triple(file, w, h) }

    @Test
    fun phoneVideoOnALandscapeScreen() {
        val (f, w, h) = shaped(kit.video(512, 288, rotated = true))
        check("rotated-phone-landscape", f, w, h, 1000, 620)
    }

    @Test
    fun phoneVideoBiggerThanTheScreen() {
        // More pixels than the space, like a 1080×1920 phone video on a tablet.
        val (f, w, h) = shaped(kit.video(512, 288, rotated = true))
        check("rotated-phone-bigger", f, w, h, 420, 260)
    }

    @Test
    fun landscapeVideoOnAPortraitScreen() {
        val (f, w, h) = shaped(kit.video(512, 288))
        check("landscape-portrait", f, w, h, 600, 1100)
    }

    @Test
    fun landscapeVideoBiggerThanTheScreen() {
        val (f, w, h) = shaped(kit.video(512, 288))
        check("landscape-bigger", f, w, h, 300, 420)
    }

    @Test
    fun gifPlaysFittedLikeAVideo() {
        val f = File(kit.dir, "portrait.gif").apply { writeBytes(VideoTestKit.gif(48, 96, listOf(0, 1, 2, 3), 25)) }
        check("gif-portrait", f, 48, 96, 900, 560, gif = true)
    }
}
