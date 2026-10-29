package io.github.drtron1c.cloudphotos.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.heifwriter.HeifWriter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.drtron1c.cloudphotos.CloudPhotosApp
import io.github.drtron1c.cloudphotos.ai.Vision
import io.github.drtron1c.cloudphotos.photos.LocalCopies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Zoom resolution, HEIC → JPEG, video trimming and the text reader, on a real Android. */
@RunWith(AndroidJUnit4::class)
class MediaExtrasOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "test-extras").apply { mkdirs() }

    /** A 4032×3024 test picture with fine stripes and a coloured square at a known place. */
    private fun pattern(): Bitmap {
        val b = Bitmap.createBitmap(4032, 3024, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val p = Paint()
        p.color = Color.BLACK
        for (x in 0 until 4032 step 8) c.drawRect(x.toFloat(), 0f, x + 4f, 3024f, p)
        p.color = Color.rgb(220, 30, 30)
        c.drawRect(3000f, 2000f, 3400f, 2400f, p)
        return b
    }

    private fun heic(): File? {
        val f = File(dir, "pattern.heic")
        return try {
            val w = HeifWriter.Builder(f.path, 4032, 3024, HeifWriter.INPUT_MODE_BITMAP).setGridEnabled(true).setQuality(95).build()
            w.start(); w.addBitmap(pattern()); w.stop(30_000); w.close()
            f.takeIf { it.length() > 0 }
        } catch (e: Throwable) {
            Log.i("CompanionTest", "HEIC_WRITE unavailable: $e")
            null
        }
    }

    /** Zoomed-in tiles are cut from the full-size original (what the photo viewer does), for HEIC too. */
    @Test
    fun fullResolutionTilesFromHeicAndJpeg() {
        val jpg = File(dir, "pattern.jpg").also { f -> f.outputStream().use { pattern().compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val files = listOfNotNull(jpg, heic())
        for (f in files) {
            val d = BitmapRegionDecoder.newInstance(f.path)
            assertEquals(4032, d.width)
            assertEquals(3024, d.height)
            val tile = d.decodeRegion(Rect(3100, 2100, 3612, 2612), BitmapFactory.Options())
            assertNotNull("${f.name}: no tile", tile)
            // Inside the red square at full resolution.
            val px = tile!!.getPixel(100, 100)
            Log.i("CompanionTest", "TILE ${f.name} ${tile.width}x${tile.height} px=${Integer.toHexString(px)}")
            assertTrue("${f.name} ${Integer.toHexString(px)}", Color.red(px) > 170 && Color.green(px) < 90)
            // Stripes 4 px wide are still there (not blurred away).
            val row = IntArray(64) { Color.red(d.decodeRegion(Rect(1000, 1000, 1064, 1001), BitmapFactory.Options())!!.getPixel(it, 0)) }
            assertTrue("${f.name} stripes ${row.toList()}", row.max() - row.min() > 120)
            d.recycle()
        }
        Log.i("CompanionTest", "HEIC_TILES tested=${files.size}")
    }

    @Test
    fun heicBecomesJpegForSharing() {
        val h = heic()
        assumeTrue("No HEIC encoder on this emulator", h != null)
        val out = File(dir, "pattern-converted.jpg")
        LocalCopies.toJpeg(h!!, out)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.path, o)
        assertEquals("image/jpeg", o.outMimeType)
        assertEquals(4032, o.outWidth)
        assertEquals(3024, o.outHeight)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun trimsAVideo() = runBlocking {
        // Make a 4-second test video from a picture, then trim it to the middle second.
        val png = File(dir, "frame.png").also { f -> f.outputStream().use { Bitmap.createScaledBitmap(pattern(), 640, 480, true).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val source = File(dir, "source.mp4")
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val t = Transformer.Builder(ctx).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { cont.resume(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { cont.resumeWithException(exportException) }
                }).build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(png))).setDurationUs(4_000_000).setFrameRate(30).build()
                t.start(Composition.Builder(EditedMediaItemSequence(item)).build(), source.path)
            }
        }
        val trimmed = File(dir, "trimmed.mp4")
        VideoTrimmer.trim(ctx, source, trimmed, 1_000, 2_000) {}
        val r = MediaMetadataRetriever()
        r.setDataSource(trimmed.path)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        r.release()
        Log.i("CompanionTest", "TRIM duration_ms=$ms")
        assertTrue("trimmed to $ms ms", ms in 700..1400)
    }

    /** The text reader runs on Android and reads printed words (downloads the model like the app). */
    @Test
    fun readsTextOnDevice() = runBlocking {
        val graph = (ctx.applicationContext as CloudPhotosApp).graph
        val vision: Vision = graph.vision
        assertTrue(vision.textPack.download(graph.http))
        vision.load()
        val b = Bitmap.createBitmap(1200, 500, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(240, 236, 220))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 20, 30); textSize = 90f; isFakeBoldText = true }
        c.drawText("PLATFORM 9", 60f, 180f, p)
        c.drawText("Coffee 3.50", 60f, 360f, p)
        val jpeg = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        val t0 = System.currentTimeMillis()
        val lines = vision.readNow("test-photo", jpeg)!!
        Log.i("CompanionTest", "OCR ms=${System.currentTimeMillis() - t0} lines=$lines")
        val all = lines.joinToString(" ").uppercase()
        assertTrue(all, all.contains("PLATFORM") && all.contains("COFFEE"))
        vision.forget(setOf("test-photo"))
        assertEquals(null, vision.textOf("test-photo"))
    }

    /** The face models load and run on Android (a blank picture has no faces). */
    @Test
    fun faceModelsRunOnDevice() = runBlocking {
        val graph = (ctx.applicationContext as CloudPhotosApp).graph
        val vision = graph.vision
        assertTrue(vision.facePack.download(graph.http))
        vision.load()
        val b = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val jpeg = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val t0 = System.currentTimeMillis()
        assertTrue(vision.analyse("blank", jpeg, wantFaces = true, wantText = false))
        Log.i("CompanionTest", "FACES ms=${System.currentTimeMillis() - t0}")
        assertTrue(vision.hasFaces("blank"))
        vision.forget(setOf("blank"))
        assertTrue(!vision.hasFaces("blank"))
    }
}
