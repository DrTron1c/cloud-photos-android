package io.github.drtron1c.cloudphotos.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/** Test videos, GIFs and screen checks shared by the on-device video tests. */
@OptIn(UnstableApi::class)
class VideoTestKit(private val ctx: Context, name: String) {
    val dir = File(ctx.cacheDir, name).apply { mkdirs() }
    val tag = "CompanionTest"

    /** Blue inside, a yellow band round the edge (so a cropped edge is easy to spot). */
    fun picture(w: Int, h: Int, fill: Int? = null): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (fill != null) c.drawColor(fill) else {
            c.drawColor(Color.rgb(250, 220, 0))
            val band = min(w, h) * 0.14f
            c.drawRect(band, band, w - band, h - band, Paint().apply { color = Color.rgb(30, 120, 255) })
        }
        val f = File(dir, "picture-${w}x$h-${fill ?: 0}.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun export(composition: Composition, out: File) = runBlocking(Dispatchers.Main) {
        out.delete()
        suspendCancellableCoroutine<Unit> { cont ->
            Transformer.Builder(ctx).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { cont.resume(Unit) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { cont.resumeWithException(exportException) }
            }).build().start(composition, out.path)
        }
    }

    private fun item(png: File, us: Long) = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(png))).setDurationUs(us).setFrameRate(30).build()

    /** A 2-second test video; [rotated] stores landscape pixels with a 90° flag, like phone videos. */
    fun video(w: Int, h: Int, rotated: Boolean = false): File {
        val plain = File(dir, "video-${w}x$h.mp4")
        if (!plain.exists()) export(Composition.Builder(EditedMediaItemSequence(listOf(item(picture(w, h), 2_000_000)))).build(), plain)
        if (!rotated) return plain
        val out = File(dir, "video-${w}x$h-rotated.mp4")
        if (out.exists()) return out
        val turn: Effect = ScaleAndRotateTransformation.Builder().setRotationDegrees(90f).build()
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(plain))).setEffects(Effects(listOf(), listOf(turn))).build()
        export(Composition.Builder(EditedMediaItemSequence(listOf(edited))).build(), out)
        return out
    }

    /** A video that's red, then green, blue and yellow, half a second each. */
    fun colourVideo(): File {
        val out = File(dir, "colours.mp4")
        if (out.exists()) return out
        val items = COLOURS.map { item(picture(320, 240, it), 500_000) }
        export(Composition.Builder(EditedMediaItemSequence(items)).build(), out)
        return out
    }

    /** The picture's shape as it should appear (after the file's rotation flag). */
    fun shape(f: File): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.path)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val turn = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        r.release()
        Log.i(tag, "FIT file ${f.name} stored ${w}x$h rotation $turn")
        return if (turn % 180 != 0) h to w else w to h
    }

    fun save(bmp: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
        }
        runCatching {
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    companion object {
        val COLOURS = listOf(Color.rgb(230, 30, 30), Color.rgb(30, 200, 60), Color.rgb(40, 80, 240), Color.rgb(240, 220, 20))

        fun bright(c: Int) = max(Color.red(c), max(Color.green(c), Color.blue(c))) > 70
        fun yellow(c: Int) = Color.red(c) > 160 && Color.green(c) > 140 && Color.blue(c) < 120
        fun blue(c: Int) = Color.blue(c) > 160 && Color.red(c) < 130

        /** Which of [COLOURS] [c] is (or -1). */
        fun colourIndex(c: Int): Int {
            var best = -1
            var bestD = Int.MAX_VALUE
            for ((i, k) in COLOURS.withIndex()) {
                val d = Math.abs(Color.red(c) - Color.red(k)) + Math.abs(Color.green(c) - Color.green(k)) + Math.abs(Color.blue(c) - Color.blue(k))
                if (d < bestD) { bestD = d; best = i }
            }
            return if (bestD < 150) best else -1
        }

        /** The drawn picture's bounds within a black space: left, top, right, bottom. */
        fun bounds(b: Bitmap): List<Int>? {
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

        /**
         * A small animated GIF: [colours] frames (palette indices into [COLOURS]), [delayCs]
         * hundredths of a second each, looping. Written by hand (GIF89a, uncompressed LZW).
         */
        fun gif(w: Int, h: Int, frames: List<Int>, delayCs: Int): ByteArray {
            val out = ByteArrayOutputStream()
            fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            out.write("GIF89a".toByteArray())
            u16(w); u16(h)
            out.write(0xF2) // global colour table, 8 colours
            out.write(0); out.write(0)
            val palette = COLOURS + List(8 - COLOURS.size) { Color.BLACK }
            for (c in palette) { out.write(Color.red(c)); out.write(Color.green(c)); out.write(Color.blue(c)) }
            // Loop forever.
            out.write(0x21); out.write(0xFF); out.write(11); out.write("NETSCAPE2.0".toByteArray()); out.write(3); out.write(1); u16(0); out.write(0)
            for (index in frames) {
                out.write(0x21); out.write(0xF9); out.write(4); out.write(0); u16(delayCs); out.write(0); out.write(0)
                out.write(0x2C); u16(0); u16(0); u16(w); u16(h); out.write(0)
                out.write(3) // LZW minimum code size
                // 4-bit codes, a clear code every few pixels so the code size never grows.
                val bits = ArrayList<Int>()
                fun code(c: Int) { for (k in 0 until 4) bits += (c shr k) and 1 }
                var n = 0
                for (p in 0 until w * h) {
                    if (n % 6 == 0) code(8)
                    code(index); n++
                }
                code(9)
                val bytes = ByteArray((bits.size + 7) / 8)
                for ((i, bit) in bits.withIndex()) if (bit == 1) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
                var i = 0
                while (i < bytes.size) {
                    val len = min(255, bytes.size - i)
                    out.write(len); out.write(bytes, i, len); i += len
                }
                out.write(0)
            }
            out.write(0x3B)
            return out.toByteArray()
        }
    }
}
