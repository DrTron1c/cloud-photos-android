package io.github.drtron1c.cloudphotos.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One line of text found in a picture, with its box (pixels) and how sure the reader is (0…1). */
class TextLine(val text: String, val confidence: Float, val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Reads the text in photos on the device (PaddleOCR's PP-OCRv4 mobile models, as packaged by
 * RapidOCR): a detector finds where the text is, a recogniser reads each line. Latin letters,
 * digits and Chinese; the character list travels inside the recogniser file.
 */
class TextModel(detector: File, recognizer: File, threads: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val det: OrtSession = env.createSession(detector.path, options)
    private val rec: OrtSession = env.createSession(recognizer.path, options)

    /** Index 0 is CTC's "blank"; the model's characters follow, then a space. */
    val characters: List<String> = buildList {
        add("")
        val meta = rec.metadata.customMetadata["character"] ?: error("The text reader has no character list")
        addAll(meta.split('\n'))
        add(" ")
    }

    fun read(argb: IntArray, width: Int, height: Int, maxSide: Int = 960): List<TextLine> {
        val boxes = findText(argb, width, height, maxSide)
        val out = ArrayList<TextLine>()
        for (b in boxes) {
            val line = readLine(argb, width, height, b[0], b[1], b[2], b[3]) ?: continue
            if (line.text.isNotBlank() && line.confidence >= 0.5f) out += line
        }
        // Top to bottom, then left to right.
        return out.sortedWith(compareBy({ it.y + it.h / 2 }, { it.x }))
    }

    /** Text areas as [x, y, w, h] in picture pixels (DB detector, axis-aligned boxes). */
    fun findText(argb: IntArray, width: Int, height: Int, maxSide: Int = 960): List<IntArray> {
        val ratio = min(1f, maxSide.toFloat() / max(width, height))
        val rw = max(32, ((width * ratio / 32f).roundToInt()) * 32)
        val rh = max(32, ((height * ratio / 32f).roundToInt()) * 32)
        val sx = width.toFloat() / rw
        val sy = height.toFloat() / rh
        val plane = rw * rh
        val input = FloatArray(3 * plane)
        // BGR, ImageNet mean / std (PaddleOCR's preprocessing).
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (y in 0 until rh) for (x in 0 until rw) {
            val p = FaceModel.bilinear(argb, width, height, ((x + 0.5f) * sx - 0.5f).coerceIn(0f, width - 1f), ((y + 0.5f) * sy - 0.5f).coerceIn(0f, height - 1f))
            val i = y * rw + x
            val b = (p and 0xff) / 255f; val g = ((p shr 8) and 0xff) / 255f; val r = ((p shr 16) and 0xff) / 255f
            input[i] = (b - mean[0]) / std[0]
            input[plane + i] = (g - mean[1]) / std[1]
            input[2 * plane + i] = (r - mean[2]) / std[2]
        }
        val prob: FloatArray
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { r ->
                val v = (r.get(0) as OnnxTensor).floatBuffer
                prob = FloatArray(v.remaining()).also { v.get(it) }
            }
        }
        // Connected areas above the threshold → boxes, grown a little (DB "unclip").
        val mask = BooleanArray(plane) { prob[it] > 0.3f }
        val seen = BooleanArray(plane)
        val boxes = ArrayList<IntArray>()
        val stack = IntArray(plane)
        for (start in 0 until plane) {
            if (!mask[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var minX = rw; var minY = rh; var maxX = 0; var maxY = 0
            var n = 0; var sum = 0f
            while (top > 0) {
                val i = stack[--top]
                val x = i % rw; val y = i / rw
                n++; sum += prob[i]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[top++] = i - 1 }
                if (x < rw - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[top++] = i + 1 }
                if (y > 0 && mask[i - rw] && !seen[i - rw]) { seen[i - rw] = true; stack[top++] = i - rw }
                if (y < rh - 1 && mask[i + rw] && !seen[i + rw]) { seen[i + rw] = true; stack[top++] = i + rw }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (min(bw, bh) < 3 || n < 10 || sum / n < 0.6f) continue
            val grow = (bw * bh * 1.6f / (2f * (bw + bh))).roundToInt()
            val x0 = ((minX - grow) * sx).toInt().coerceIn(0, width - 1)
            val y0 = ((minY - grow) * sy).toInt().coerceIn(0, height - 1)
            val x1 = ((maxX + 1 + grow) * sx).toInt().coerceIn(x0 + 1, width)
            val y1 = ((maxY + 1 + grow) * sy).toInt().coerceIn(y0 + 1, height)
            boxes += intArrayOf(x0, y0, x1 - x0, y1 - y0)
        }
        return boxes
    }

    /** Reads one line of text inside the box. */
    fun readLine(argb: IntArray, width: Int, height: Int, bx: Int, by: Int, bw: Int, bh: Int): TextLine? {
        if (bw < 4 || bh < 4) return null
        // Tall, narrow boxes are probably vertical text; this reader only does horizontal lines.
        if (bh > bw * 1.5f) return null
        val h = REC_HEIGHT
        val w = min(MAX_REC_WIDTH, max(16, ceil(h * bw.toFloat() / bh).toInt()))
        val plane = h * w
        val input = FloatArray(3 * plane)
        val sx = bw.toFloat() / w
        val sy = bh.toFloat() / h
        for (y in 0 until h) for (x in 0 until w) {
            val p = FaceModel.bilinear(
                argb, width, height,
                (bx + (x + 0.5f) * sx - 0.5f).coerceIn(0f, width - 1f),
                (by + (y + 0.5f) * sy - 0.5f).coerceIn(0f, height - 1f),
            )
            val i = y * w + x
            input[i] = ((p and 0xff) / 255f - 0.5f) / 0.5f
            input[plane + i] = (((p shr 8) and 0xff) / 255f - 0.5f) / 0.5f
            input[2 * plane + i] = (((p shr 16) and 0xff) / 255f - 0.5f) / 0.5f
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            rec.run(mapOf(rec.inputNames.first() to t)).use { r ->
                val tensor = r.get(0) as OnnxTensor
                val shape = tensor.info.shape
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                val v = tensor.floatBuffer
                val probs = FloatArray(v.remaining()).also { v.get(it) }
                return ctcDecode(probs, steps, classes)?.let { (text, conf) -> TextLine(text, conf, bx, by, bw, bh) }
            }
        }
    }

    /** Greedy CTC: best character per step, repeats merged, blanks dropped. */
    fun ctcDecode(probs: FloatArray, steps: Int, classes: Int): Pair<String, Float>? {
        val sb = StringBuilder()
        var last = -1
        var confSum = 0f
        var confN = 0
        for (s in 0 until steps) {
            var best = 0
            var bestP = -1f
            val off = s * classes
            for (c in 0 until classes) if (probs[off + c] > bestP) { bestP = probs[off + c]; best = c }
            if (best != 0 && best != last) {
                // The last class is always the space (whatever the length of the character list).
                sb.append(if (best == classes - 1) " " else characters.getOrElse(best) { "" })
                confSum += bestP; confN++
            }
            last = best
        }
        if (confN == 0) return null
        return sb.toString().trim() to confSum / confN
    }

    override fun close() {
        det.close()
        rec.close()
        options.close()
    }

    companion object {
        const val REC_HEIGHT = 48
        const val MAX_REC_WIDTH = 1280
    }
}
