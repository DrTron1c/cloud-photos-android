package io.github.drtron1c.cloudphotos.identity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max

/**
 * App-icon pictures in Android's adaptive-icon layout: a 432×432 square whose middle 288×288
 * is what the home screen shows inside its icon shape (the rest is spare edge for effects).
 */
object IconArt {
    const val SIZE = 432
    const val VISIBLE = 288
    private const val INSET = (SIZE - VISIBLE) / 2f

    /**
     * Renders the part of [src] that's visible in a [boxPx]-square crop box, where the picture
     * fills the box (centre-crop), is zoomed by [zoom] about the centre and moved by ([tx], [ty]).
     */
    fun render(src: Bitmap, zoom: Float, tx: Float, ty: Float, boxPx: Float): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val base = max(boxPx / src.width, boxPx / src.height)
        val toOut = VISIBLE / boxPx
        fun matrix(extra: Float) = Matrix().apply {
            postTranslate(-src.width / 2f, -src.height / 2f)
            postScale(base * zoom * toOut * extra, base * zoom * toOut * extra)
            postTranslate(
                (INSET + (boxPx / 2f + tx) * toOut - SIZE / 2f) * extra + SIZE / 2f,
                (INSET + (boxPx / 2f + ty) * toOut - SIZE / 2f) * extra + SIZE / 2f,
            )
        }
        // A slightly enlarged copy fills the spare edge where the picture itself may not reach…
        canvas.drawBitmap(src, matrix(SIZE.toFloat() / VISIBLE), paint)
        // …then the picture exactly as framed in the crop box.
        canvas.drawBitmap(src, matrix(1f), paint)
        return out
    }

    /** Largest move allowed so the picture still covers the whole crop box. */
    fun maxOffset(src: Bitmap, zoom: Float, boxPx: Float): Pair<Float, Float> {
        val base = max(boxPx / src.width, boxPx / src.height)
        return ((src.width * base * zoom - boxPx) / 2f).coerceAtLeast(0f) to
            ((src.height * base * zoom - boxPx) / 2f).coerceAtLeast(0f)
    }
}
