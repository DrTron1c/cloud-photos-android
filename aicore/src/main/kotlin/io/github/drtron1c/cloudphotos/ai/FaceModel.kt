package io.github.drtron1c.cloudphotos.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A face in a picture: box (pixels), five landmarks (eyes, nose, mouth corners) and confidence. */
class Face(val x: Float, val y: Float, val w: Float, val h: Float, val landmarks: FloatArray, val score: Float)

/**
 * On-device face finding (YuNet) and face "fingerprints" (SFace), both from OpenCV's model zoo.
 * Two faces of the same person give fingerprints whose dot product is high (≈0.9), different
 * people low (< 0.3). Pictures are plain ARGB pixels, so this runs the same on Android and in tests.
 */
class FaceModel(detector: File, recognizer: File, threads: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val det: OrtSession = env.createSession(detector.path, options)
    private val rec: OrtSession = env.createSession(recognizer.path, options)

    /** Faces in the picture, best first. */
    fun detect(argb: IntArray, width: Int, height: Int, minScore: Float = 0.8f): List<Face> {
        val size = INPUT
        val scale = min(size.toFloat() / width, size.toFloat() / height)
        val sw = max(1, (width * scale).toInt())
        val sh = max(1, (height * scale).toInt())
        // Letterboxed into 640×640, BGR, 0…255 (what YuNet was trained on).
        val plane = size * size
        val input = FloatArray(3 * plane)
        for (y in 0 until sh) {
            val sy = ((y + 0.5f) / scale - 0.5f).coerceIn(0f, (height - 1).toFloat())
            for (x in 0 until sw) {
                val sx = ((x + 0.5f) / scale - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val p = bilinear(argb, width, height, sx, sy)
                val i = y * size + x
                input[i] = (p and 0xff).toFloat()                       // B
                input[plane + i] = ((p shr 8) and 0xff).toFloat()       // G
                input[2 * plane + i] = ((p shr 16) and 0xff).toFloat()  // R
            }
        }
        val faces = ArrayList<Face>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { r ->
                fun out(name: String): FloatArray {
                    val v = (r.get(name).get() as OnnxTensor).floatBuffer
                    return FloatArray(v.remaining()).also { v.get(it) }
                }
                for (stride in intArrayOf(8, 16, 32)) {
                    val cls = out("cls_$stride")
                    val obj = out("obj_$stride")
                    val bbox = out("bbox_$stride")
                    val kps = out("kps_$stride")
                    val cols = size / stride
                    val rows = size / stride
                    for (row in 0 until rows) for (col in 0 until cols) {
                        val idx = row * cols + col
                        val score = sqrt(cls[idx].coerceIn(0f, 1f) * obj[idx].coerceIn(0f, 1f))
                        if (score < minScore) continue
                        val cx = (col + bbox[idx * 4]) * stride
                        val cy = (row + bbox[idx * 4 + 1]) * stride
                        val w = exp(bbox[idx * 4 + 2]) * stride
                        val h = exp(bbox[idx * 4 + 3]) * stride
                        val lm = FloatArray(10) { k ->
                            val grid = if (k % 2 == 0) col else row
                            (kps[idx * 10 + k] + grid) * stride / scale
                        }
                        faces += Face((cx - w / 2) / scale, (cy - h / 2) / scale, w / scale, h / scale, lm, score)
                    }
                }
            }
        }
        return nms(faces.sortedByDescending { it.score }, 0.3f)
    }

    /** The face's 128-number fingerprint (unit length). */
    fun embed(argb: IntArray, width: Int, height: Int, face: Face): FloatArray {
        val m = similarity(face.landmarks, REFERENCE)  // picture → 112×112 aligned face
        val inv = invert(m)
        val s = ALIGNED
        val plane = s * s
        val input = FloatArray(3 * plane)
        for (y in 0 until s) for (x in 0 until s) {
            val sx = inv[0] * x + inv[1] * y + inv[2]
            val sy = inv[3] * x + inv[4] * y + inv[5]
            val i = y * s + x
            if (sx < -0.5f || sy < -0.5f || sx > width - 0.5f || sy > height - 0.5f) continue
            val p = bilinear(argb, width, height, sx.coerceIn(0f, (width - 1).toFloat()), sy.coerceIn(0f, (height - 1).toFloat()))
            input[i] = ((p shr 16) and 0xff).toFloat()              // R
            input[plane + i] = ((p shr 8) and 0xff).toFloat()       // G
            input[2 * plane + i] = (p and 0xff).toFloat()           // B
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, s.toLong(), s.toLong())).use { t ->
            rec.run(mapOf(rec.inputNames.first() to t)).use { r ->
                val v = (r.get(0) as OnnxTensor).floatBuffer
                return normalize(FloatArray(v.remaining()).also { v.get(it) })
            }
        }
    }

    fun describe(): String = buildString {
        for ((label, session) in listOf("detector" to det, "recognizer" to rec)) {
            append(label).append(" in=").append(session.inputInfo.entries.joinToString { "${it.key}:${it.value.info}" })
            append(" out=").append(session.outputInfo.keys.joinToString()).append('\n')
        }
    }

    override fun close() {
        det.close()
        rec.close()
        options.close()
    }

    companion object {
        const val INPUT = 640
        const val ALIGNED = 112
        /** Where SFace expects the eyes, nose and mouth corners in its 112×112 picture (from OpenCV). */
        val REFERENCE = floatArrayOf(
            38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f,
        )

        fun bilinear(argb: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
            val x0 = floor(x).toInt().coerceIn(0, w - 1)
            val y0 = floor(y).toInt().coerceIn(0, h - 1)
            val x1 = (x0 + 1).coerceAtMost(w - 1)
            val y1 = (y0 + 1).coerceAtMost(h - 1)
            val fx = (x - x0).coerceIn(0f, 1f)
            val fy = (y - y0).coerceIn(0f, 1f)
            val a = argb[y0 * w + x0]; val b = argb[y0 * w + x1]; val c = argb[y1 * w + x0]; val d = argb[y1 * w + x1]
            var out = 0xff shl 24
            for (shift in intArrayOf(0, 8, 16)) {
                val top = ((a shr shift) and 0xff) * (1 - fx) + ((b shr shift) and 0xff) * fx
                val bottom = ((c shr shift) and 0xff) * (1 - fx) + ((d shr shift) and 0xff) * fx
                out = out or (((top * (1 - fy) + bottom * fy) + 0.5f).toInt().coerceIn(0, 255) shl shift)
            }
            return out
        }

        private fun iou(a: Face, b: Face): Float {
            val ix = max(0f, min(a.x + a.w, b.x + b.w) - max(a.x, b.x))
            val iy = max(0f, min(a.y + a.h, b.y + b.h) - max(a.y, b.y))
            val inter = ix * iy
            val union = a.w * a.h + b.w * b.h - inter
            return if (union <= 0f) 0f else inter / union
        }

        fun nms(sorted: List<Face>, threshold: Float): List<Face> {
            val keep = ArrayList<Face>()
            for (f in sorted) if (keep.none { iou(it, f) > threshold }) keep += f
            return keep
        }

        /**
         * Least-squares similarity transform (rotation, uniform scale, shift) taking the [src]
         * points onto [dst] — Umeyama's method, like OpenCV's alignCrop. Returns a 2×3 matrix.
         */
        fun similarity(src: FloatArray, dst: FloatArray): FloatArray {
            val n = src.size / 2
            var mx = 0.0; var my = 0.0; var dx = 0.0; var dy = 0.0
            for (i in 0 until n) { mx += src[2 * i]; my += src[2 * i + 1]; dx += dst[2 * i]; dy += dst[2 * i + 1] }
            mx /= n; my /= n; dx /= n; dy /= n
            // For 2-D similarity: a = Σ(s·d)/Σ|s|², b = Σ(s×d)/Σ|s|² on centred points.
            var sxx = 0.0; var sxy = 0.0; var norm = 0.0
            for (i in 0 until n) {
                val sx = src[2 * i] - mx; val sy = src[2 * i + 1] - my
                val tx = dst[2 * i] - dx; val ty = dst[2 * i + 1] - dy
                sxx += sx * tx + sy * ty
                sxy += sx * ty - sy * tx
                norm += sx * sx + sy * sy
            }
            val a = (sxx / norm).toFloat()
            val b = (sxy / norm).toFloat()
            // [a -b; b a] · p + t
            val tx = (dx - (a * mx - b * my)).toFloat()
            val ty = (dy - (b * mx + a * my)).toFloat()
            return floatArrayOf(a, -b, tx, b, a, ty)
        }

        fun invert(m: FloatArray): FloatArray {
            val det = m[0] * m[4] - m[1] * m[3]
            val ia = m[4] / det; val ib = -m[1] / det; val ic = -m[3] / det; val id = m[0] / det
            return floatArrayOf(ia, ib, -(ia * m[2] + ib * m[5]), ic, id, -(ic * m[2] + id * m[5]))
        }
    }
}
