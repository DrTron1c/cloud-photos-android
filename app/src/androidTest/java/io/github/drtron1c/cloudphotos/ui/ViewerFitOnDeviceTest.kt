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
import androidx.compose.ui.test.onNodeWithContentDescription
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

    @Test
    fun tappingThePictureHidesTheBars() {
        val (f, w, h) = shaped(kit.video(512, 288))
        lateinit var vm: MainViewModel
        rule.runOnUiThread { vm = MainViewModel(ctx.applicationContext as Application) }
        val list = OneItem(asset(f, w, h))
        rule.setContent {
            CloudPhotosTheme(themeMode = "dark") {
                CompositionLocalProvider(LocalGlassEnabled provides true) {
                    Box(Modifier.fillMaxSize().testTag("space")) {
                        GlassHost(Modifier.fillMaxSize(), content = { ViewerScreen(vm, list, 0) {} }) {}
                    }
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(1500)
        rule.onNodeWithContentDescription("Back").assertExists()
        // Tap the picture (above the controls), like you would.
        rule.onNodeWithTag("space").performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, height * 0.4f)) }
        // A single tap counts once the double-tap wait (~300 ms) has passed: move the test clock on.
        rule.mainClock.advanceTimeBy(700)
        Thread.sleep(600)
        rule.waitForIdle()
        Thread.sleep(600)
        rule.waitForIdle()
        kit.save(rule.onNodeWithTag("space").captureToImage().asAndroidBitmap(), "viewer-after-tap.png")
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
        rule.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    /** A magenta still the size of [w]×[h]: easy to spot if it ever shows. */
    private fun magentaStill(w: Int, h: Int): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(android.graphics.Color.rgb(255, 0, 255))
        return File(kit.dir, "still-${w}x$h.png").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun magenta(b: Bitmap): Int {
        var n = 0
        val row = IntArray(b.width)
        for (y in 0 until b.height step 3) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            for (c in row) if (android.graphics.Color.red(c) > 200 && android.graphics.Color.green(c) < 90 && android.graphics.Color.blue(c) > 200) n++
        }
        return n
    }

    /** Opens [a] full screen and returns how many magenta pixels showed in each capture (~4 s), plus the last capture. */
    private fun watch(a: Asset, spaceW: Int, spaceH: Int): Pair<List<Int>, Bitmap> {
        lateinit var vm: MainViewModel
        rule.runOnUiThread { vm = MainViewModel(ctx.applicationContext as Application) }
        val list = OneItem(a)
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
        val counts = ArrayList<Int>()
        var last: Bitmap? = null
        val until = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < until) {
            rule.waitForIdle()
            val b = rule.onNodeWithTag("space").captureToImage().asAndroidBitmap()
            counts += magenta(b)
            last = b
            Thread.sleep(150)
        }
        return counts to last!!
    }

    @Test
    fun croppedVideoNeverShowsTheUncroppedStill() {
        // The original is 16:9; it was cropped to a square. iCloud's still is of the original.
        val (orig, ow, oh) = shaped(kit.video(512, 288))
        val (crop, cw, ch) = shaped(kit.video(288, 288))
        val still = magentaStill(ow, oh)
        val type = "public.mpeg-4"
        val a = Asset(
            "test-cropped", "a-cropped", "CPLAsset", "tag", 0, 0, false, false, type, "IMG_CROP.MOV", ow, oh, true, 2.0,
            null,
            Version(Uri.fromFile(still).toString(), still.length(), "still-cropped", "public.png", ow, oh),
            Version(Uri.fromFile(crop).toString(), crop.length(), "edit-cropped", type, cw, ch),
            Version(Uri.fromFile(orig).toString(), orig.length(), "orig-cropped", type, ow, oh),
            null, null,
        )
        val (counts, last) = watch(a, 1000, 620)
        kit.save(last, "viewer-cropped.png")
        Log.i(kit.tag, "VIEWER cropped magenta per capture $counts")
        assertTrue("the uncropped still showed (${counts.max()} px)", counts.all { it == 0 })
        // And the cropped (square) video is what's showing, fitted.
        val box = VideoTestKit.bounds(last)!!
        val side = 620
        assertTrue("square crop fitted, got $box", Math.abs(box[2] - box[0] + 1 - side) <= 6 && Math.abs(box[3] - box[1] + 1 - side) <= 6)
    }

    @Test
    fun stillIsGoneOnceTheVideoPlays() {
        val (f, w, h) = shaped(kit.video(512, 288))
        // A still a little taller than the video, so if it stayed it would show above and below it.
        val still = magentaStill(w, h + 60)
        val type = "public.mpeg-4"
        val a = Asset(
            "test-still", "a-still", "CPLAsset", "tag", 0, 0, false, false, type, "IMG_STILL.MOV", w, h, true, 2.0,
            null,
            Version(Uri.fromFile(still).toString(), still.length(), "still-plain", "public.png", w, h + 60),
            null,
            Version(Uri.fromFile(f).toString(), f.length(), "orig-plain", type, w, h),
            null, null,
        )
        val (counts, last) = watch(a, 1000, 620)
        kit.save(last, "viewer-still-gone.png")
        Log.i(kit.tag, "VIEWER still magenta per capture $counts")
        assertTrue("still never left (${counts.takeLast(5)})", counts.takeLast(5).all { it == 0 })
    }

    @Test
    fun stillShowsWhileTheVideoCantStart() {
        // Control for the two tests above: stills do load here, so their "no magenta" means something.
        val missing = File(kit.dir, "missing.mp4").also { it.delete() }
        val still = magentaStill(512, 288)
        val type = "public.mpeg-4"
        val a = Asset(
            "test-control", "a-control", "CPLAsset", "tag", 0, 0, false, false, type, "IMG_CTRL.MOV", 512, 288, true, 2.0,
            null,
            Version(Uri.fromFile(still).toString(), still.length(), "still-control", "public.png", 512, 288),
            null,
            Version(Uri.fromFile(missing).toString(), 0, "orig-control", type, 512, 288),
            null, null,
        )
        val (counts, _) = watch(a, 1000, 620)
        Log.i(kit.tag, "VIEWER control magenta per capture $counts")
        assertTrue("the still never showed (${counts.takeLast(3)})", counts.takeLast(3).all { it > 1000 })
    }
}
