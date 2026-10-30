package io.github.drtron1c.cloudphotos.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.drtron1c.cloudphotos.photos.MediaCache
import io.github.drtron1c.cloudphotos.settings.AppSettings
import kotlinx.coroutines.runBlocking
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** GIFs played like videos, the frame strip's exact frames, and the temporary video cache. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackExtrasOnDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val kit = VideoTestKit(ctx, "test-extras-playback")
    private val tag = kit.tag

    private fun settings(name: String) = AppSettings(ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() })

    private fun centre(b: Bitmap) = VideoTestKit.colourIndex(b.getPixel(b.width / 2, b.height / 2))

    @Test
    fun gifPlaysSeeksStepsAndShowsEachFrame() {
        // Red, green, blue, yellow — a quarter of a second each.
        val bytes = VideoTestKit.gif(64, 48, listOf(0, 1, 2, 3), 25)
        lateinit var player: GifPlayer
        rule.runOnUiThread { player = GifPlayer("test-gif") { bytes }.apply { playWhenReady = false; prepare() } }
        val until = System.currentTimeMillis() + 10_000
        while (rule.runOnUiThread { player.playbackState } != Player.STATE_READY && System.currentTimeMillis() < until) Thread.sleep(50)
        rule.runOnUiThread {
            assertEquals(Player.STATE_READY, player.playbackState)
            assertEquals(1000L, player.duration)
            assertEquals(64, player.videoSize.width)
            assertEquals(48, player.videoSize.height)
        }
        rule.setContent {
            val d = LocalDensity.current
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Box(Modifier.size(with(d) { 600.toDp() }, with(d) { 300.toDp() }).testTag("space")) {
                    FittedVideo(player = player, zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = 16f)), fallback = Size(64f, 48f))
                }
            }
        }
        rule.waitForIdle()
        Thread.sleep(500)
        // Seeking shows the frame at that moment, while paused.
        for ((i, t) in listOf(0L, 300L, 600L, 900L).withIndex()) {
            rule.runOnUiThread { player.seekTo(t) }
            Thread.sleep(400)
            rule.waitForIdle()
            val b = rule.onNodeWithTag("space").captureToImage().asAndroidBitmap()
            if (i == 0) kit.save(b, "gif-frame.png")
            assertEquals("frame showing at $t ms", i, centre(b))
            // Fitted: 600×300 space, 4:3 GIF → 400×300.
            val box = VideoTestKit.bounds(b)!!
            assertTrue("GIF fitted, got $box", Math.abs(box[2] - box[0] + 1 - 400) <= 4 && Math.abs(box[3] - box[1] + 1 - 300) <= 4)
        }
        // Frame by frame.
        rule.runOnUiThread {
            assertEquals(250L, player.step(0, 1, 1000))
            assertEquals(500L, player.step(300, 1, 1000))
            assertEquals(0L, player.step(300, -1, 1000))
            assertEquals(500L, player.step(990, -1, 1000))
        }
        // The strip's pictures are the individual frames.
        val got = arrayOfNulls<Int>(4)
        runBlocking { player.pictures(listOf(100L, 350L, 600L, 850L), 24) { i, b -> got[i] = centre(b) } }
        assertEquals(listOf(0, 1, 2, 3), got.toList())
        // And it plays (and loops) on its own.
        rule.runOnUiThread { player.seekTo(0); player.playWhenReady = true }
        Thread.sleep(1300)
        val pos = rule.runOnUiThread { player.currentPosition }
        Log.i(tag, "GIF position after 1.3 s: $pos")
        assertTrue("GIF advanced and looped ($pos)", pos in 1..999)
        rule.runOnUiThread { player.release() }
    }

    @Test
    fun videoStripShowsTheExactFrames() {
        // Red, green, blue, yellow — half a second each.
        val f = kit.colourVideo()
        lateinit var player: ExoPlayer
        rule.runOnUiThread { player = ExoPlayer.Builder(ctx).build() }
        val cache = MediaCache(ctx, settings("test-strip"), OkHttpClient(), "media-test-strip")
        val frames = VideoFrames(player, Uri.fromFile(f), "colours", f.length(), cache)
        val got = arrayOfNulls<Int>(4)
        runBlocking { frames.pictures(listOf(250L, 750L, 1250L, 1750L), 32) { i, b -> got[i] = centre(b) } }
        Log.i(tag, "STRIP colours ${got.toList()}")
        assertEquals(listOf(0, 1, 2, 3), got.toList())
        rule.runOnUiThread { player.release() }
    }

    /** Counts downloads (opens of the "network"). */
    private class Counting(data: ByteArray, private val onOpen: () -> Unit) : DataSource {
        private val inner = ByteArrayDataSource(data)
        override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)
        override fun open(dataSpec: DataSpec): Long { onOpen(); return inner.open(dataSpec) }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = inner.read(buffer, offset, length)
        override fun getUri(): Uri? = inner.uri
        override fun close() = inner.close()
    }

    @Test
    fun videosComeFromTheCacheTheSecondTime() {
        val data = ByteArray(700_000) { (it % 251).toByte() }
        var downloads = 0
        val s = settings("test-cache")
        val cache = MediaCache(ctx, s, OkHttpClient(), "media-test-cache", upstream = { Counting(data) { downloads++ } })
        try {
            cache.clear()
            val first = cache.readAll(Uri.parse("https://example.invalid/video.mp4?token=one"), "same-file")
            assertArrayEquals(data, first)
            val after = downloads
            assertTrue(after >= 1)
            // iCloud gives a new link each session; the same file still comes from the cache.
            val second = cache.readAll(Uri.parse("https://example.invalid/video.mp4?token=two"), "same-file")
            assertArrayEquals(data, second)
            assertEquals("second read came from the cache", after, downloads)
            assertTrue(cache.used() >= data.size)
            // The frame strip's random reads use it too.
            val reader = cache.mediaDataSource(Uri.parse("https://example.invalid/video.mp4?token=three"), "same-file", data.size.toLong())
            val buf = ByteArray(1000)
            assertEquals(1000, reader.readAt(500_000, buf, 0, 1000))
            assertArrayEquals(data.copyOfRange(500_000, 501_000), buf)
            assertEquals(after, downloads)
            // "Until the app closes": a new start empties it.
            s.mediaCacheKeep = "session"
            cache.trim(appStarting = true)
            assertEquals(0L, cache.used())
            // Turned off: reads go straight to iCloud and nothing is kept.
            s.mediaCacheMb = 0
            cache.readAll(Uri.parse("https://example.invalid/video.mp4?token=four"), "same-file")
            assertEquals(after + 1, downloads)
            assertEquals(0L, cache.used())
        } finally {
            cache.release()
        }
    }
}
