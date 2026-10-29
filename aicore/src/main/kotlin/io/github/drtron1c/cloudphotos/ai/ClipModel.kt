package io.github.drtron1c.cloudphotos.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Picture preparation for MobileCLIP: shortest side to [size] (bilinear), centre crop to a
 * [size]×[size] square, RGB scaled to 0…1, channels first. Works on plain ARGB pixels so the
 * same code runs on Android (Bitmap.getPixels) and in tests.
 */
object ImagePrep {
    const val SIZE = 256

    fun toChw(argb: IntArray, width: Int, height: Int, size: Int = SIZE): FloatArray {
        val scale = size.toFloat() / min(width, height)
        val rw = max(size, (width * scale).roundToInt())
        val rh = max(size, (height * scale).roundToInt())
        val x0 = (rw - size) / 2
        val y0 = (rh - size) / 2
        val out = FloatArray(3 * size * size)
        val plane = size * size
        for (y in 0 until size) {
            // Sample centre of the target pixel in source coordinates.
            val sy = ((y + y0) + 0.5f) / scale - 0.5f
            val yA = sy.toInt().coerceIn(0, height - 1)
            val yB = (yA + 1).coerceAtMost(height - 1)
            val fy = (sy - yA).coerceIn(0f, 1f)
            for (x in 0 until size) {
                val sx = ((x + x0) + 0.5f) / scale - 0.5f
                val xA = sx.toInt().coerceIn(0, width - 1)
                val xB = (xA + 1).coerceAtMost(width - 1)
                val fx = (sx - xA).coerceIn(0f, 1f)
                val p00 = argb[yA * width + xA]
                val p01 = argb[yA * width + xB]
                val p10 = argb[yB * width + xA]
                val p11 = argb[yB * width + xB]
                val i = y * size + x
                for (c in 0 until 3) {
                    val shift = 16 - 8 * c
                    val v00 = (p00 shr shift) and 0xff
                    val v01 = (p01 shr shift) and 0xff
                    val v10 = (p10 shr shift) and 0xff
                    val v11 = (p11 shr shift) and 0xff
                    val top = v00 + (v01 - v00) * fx
                    val bottom = v10 + (v11 - v10) * fx
                    out[c * plane + i] = (top + (bottom - top) * fy) / 255f
                }
            }
        }
        return out
    }
}

/** Unit-length copy of [v]. */
fun normalize(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += x * x
    val n = sqrt(s).toFloat().takeIf { it > 0f } ?: return v
    return FloatArray(v.size) { v[it] / n }
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/**
 * A compact stored embedding: unit vector quantised to signed bytes with one scale.
 * Similarity with a float query is `dot(query, q) * scale`.
 */
class Packed(val q: ByteArray, val scale: Float) {
    fun similarity(query: FloatArray): Float {
        var s = 0f
        for (i in q.indices) s += query[i] * q[i]
        return s * scale
    }

    /** Similarity of two stored embeddings. */
    fun similarity(other: Packed): Float {
        var s = 0
        for (i in q.indices) s += q[i] * other.q[i]
        return s * scale * other.scale
    }

    companion object {
        fun of(unit: FloatArray): Packed {
            var m = 0f
            for (x in unit) m = max(m, kotlin.math.abs(x))
            val scale = if (m > 0f) m / 127f else 1f
            return Packed(ByteArray(unit.size) { (unit[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }, scale)
        }
    }
}

/**
 * MobileCLIP image and text encoders (ONNX). Pictures and words land in the same space, so
 * "red hat" scores highest against photos of red hats. Runs fully on the device.
 */
class ClipModel(visionModel: File, textModel: File, private val tokenizer: ClipTokenizer, threads: Int = 4) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val vision: OrtSession = env.createSession(visionModel.path, options)
    private val text: OrtSession = env.createSession(textModel.path, options)

    /** Embedding of a prepared picture (see [ImagePrep]). */
    fun embedImage(chw: FloatArray, size: Int = ImagePrep.SIZE): FloatArray {
        val input = vision.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
            vision.run(mapOf(input to t)).use { r -> return normalize(pick(r, "image_embeds")) }
        }
    }

    /** Input / output names and shapes, for diagnostics. */
    fun describe(): String = buildString {
        for ((label, session) in listOf("vision" to vision, "text" to text)) {
            append(label).append(" in=")
            append(session.inputInfo.entries.joinToString { "${it.key}:${it.value.info}" })
            append(" out=")
            append(session.outputInfo.entries.joinToString { "${it.key}:${it.value.info}" })
            append('\n')
        }
    }

    /**
     * Padding after the end token. MobileCLIP finds the end token with argmax over the ids, so
     * padding must be 0 (padding with the end token itself makes every phrase look the same —
     * found by the CI test).
     */
    var padWithZero = true
    var useMask = true

    /** Embedding of a search phrase. */
    fun embedText(phrase: String): FloatArray {
        val raw = tokenizer.encode(phrase)
        val end = raw.indexOfFirst { it == raw.max() }
        val ids = if (padWithZero) IntArray(raw.size) { if (it <= end) raw[it] else 0 } else raw
        val n = ids.size.toLong()
        val inputs = HashMap<String, OnnxTensor>()
        try {
            for (name in text.inputNames) {
                val data = if (name.contains("mask")) LongArray(ids.size) { if (!useMask || it <= end) 1L else 0L }
                else LongArray(ids.size) { ids[it].toLong() }
                inputs[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(data), longArrayOf(1, n))
            }
            text.run(inputs).use { r -> return normalize(pick(r, "text_embeds")) }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun pick(result: OrtSession.Result, preferred: String): FloatArray {
        val value = result.firstOrNull { it.key == preferred }?.value
            ?: result.firstOrNull { it.key.contains("embed") }?.value
            ?: result.first().value
        @Suppress("UNCHECKED_CAST")
        return when (val v = (value as OnnxTensor).value) {
            is Array<*> -> (v[0] as FloatArray)
            is FloatArray -> v
            else -> error("Unexpected model output")
        }
    }

    override fun close() {
        vision.close()
        text.close()
        options.close()
    }
}
