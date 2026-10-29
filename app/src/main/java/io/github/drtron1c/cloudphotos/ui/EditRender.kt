package io.github.drtron1c.cloudphotos.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import io.github.drtron1c.cloudphotos.photos.Asset
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Looks and adjustments for the editor, expressed as 4×5 colour matrices. */
enum class EditFilter(val title: String) {
    Original("Original"), Vivid("Vivid"), Warm("Warm"), Cool("Cool"), Dramatic("Dramatic"),
    Mono("Mono"), Noir("Noir"), Silvertone("Silvertone"), Fade("Fade"), Sepia("Sepia")
}

object EditMath {
    private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

    private fun contrast(c: Float): ColorMatrix {
        val t = 128f * (1f - c)
        return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
    }

    private fun brightness(b: Float) = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f))

    private fun warmth(w: Float) = ColorMatrix(
        floatArrayOf(1f + 0.18f * w, 0f, 0f, 0f, 0f, 0f, 1f + 0.04f * w, 0f, 0f, 0f, 0f, 0f, 1f - 0.18f * w, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    )

    private fun sepia() = ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    private fun filter(f: EditFilter): ColorMatrix = when (f) {
        EditFilter.Original -> ColorMatrix()
        EditFilter.Vivid -> ColorMatrix(saturation(1.35f)).apply { postConcat(contrast(1.08f)) }
        EditFilter.Warm -> ColorMatrix(warmth(0.8f)).apply { postConcat(saturation(1.1f)) }
        EditFilter.Cool -> ColorMatrix(warmth(-0.8f))
        EditFilter.Dramatic -> ColorMatrix(contrast(1.35f)).apply { postConcat(saturation(0.85f)); postConcat(brightness(-12f)) }
        EditFilter.Mono -> saturation(0f)
        EditFilter.Noir -> ColorMatrix(saturation(0f)).apply { postConcat(contrast(1.45f)) }
        EditFilter.Silvertone -> ColorMatrix(saturation(0f)).apply { postConcat(contrast(0.9f)); postConcat(brightness(14f)) }
        EditFilter.Fade -> ColorMatrix(contrast(0.82f)).apply { postConcat(saturation(0.75f)); postConcat(brightness(16f)) }
        EditFilter.Sepia -> sepia()
    }

    /** Combined matrix. Sliders run −1…1 with 0 = unchanged. */
    fun build(f: EditFilter, exposure: Float, contrastAmt: Float, saturationAmt: Float, warmthAmt: Float): FloatArray {
        val m = filter(f)
        if (exposure != 0f) m.postConcat(brightness(exposure * 70f))
        if (contrastAmt != 0f) m.postConcat(contrast(1f + contrastAmt * 0.6f))
        if (saturationAmt != 0f) m.postConcat(saturation(1f + saturationAmt))
        if (warmthAmt != 0f) m.postConcat(warmth(warmthAmt))
        return m.array.copyOf()
    }

    fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}

object EditRender {
    /**
     * Crop ([crop] = left, top, right, bottom as 0…1 fractions), apply the colour matrix and
     * write a high-quality JPEG stamped with the original's capture date.
     */
    fun render(cacheDir: File, asset: Asset, bitmap: Bitmap, crop: FloatArray, matrix: FloatArray): File {
        val w = bitmap.width
        val h = bitmap.height
        val l = (crop[0] * w).toInt().coerceIn(0, w - 1)
        val t = (crop[1] * h).toInt().coerceIn(0, h - 1)
        val r = (crop[2] * w).toInt().coerceIn(l + 1, w)
        val b = (crop[3] * h).toInt().coerceIn(t + 1, h)
        val cropped = if (l == 0 && t == 0 && r == w && b == h) bitmap else Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
        val out = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            cropped, 0f, 0f,
            Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix)) },
        )
        val dir = File(cacheDir, "edit").apply { mkdirs() }
        val file = File(dir, "edit_${System.nanoTime()}.jpg")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (cropped !== bitmap) cropped.recycle()
        out.recycle()

        runCatching {
            val tz = TimeZone.getDefault()
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = tz }
            val stamp = fmt.format(Date(if (asset.date > 0) asset.date else System.currentTimeMillis()))
            val offsetMin = tz.getOffset(asset.date) / 60000
            val offset = "%s%02d:%02d".format(if (offsetMin < 0) "-" else "+", kotlin.math.abs(offsetMin) / 60, kotlin.math.abs(offsetMin) % 60)
            val exif = ExifInterface(file)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
            exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Cloud Photos")
            if (asset.latitude != null && asset.longitude != null) exif.setLatLong(asset.latitude, asset.longitude)
            exif.saveAttributes()
        }
        return file
    }
}
