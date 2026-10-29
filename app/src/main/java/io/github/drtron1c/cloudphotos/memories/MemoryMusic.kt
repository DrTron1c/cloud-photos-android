package io.github.drtron1c.cloudphotos.memories

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Built-in soundtracks for memories, composed on the device (no downloads, no licensing):
 * a short chord progression with soft pads, a gentle arpeggio and a little reverb, rendered once
 * to a WAV file that loops seamlessly.
 */
object MemoryMusic {
    val STYLES = listOf("gentle" to "Gentle", "bright" to "Bright", "dreamy" to "Dreamy")

    private class Style(
        val bpm: Double,
        /** Chords as semitones above A3 (220 Hz). */
        val chords: List<IntArray>,
        val arpeggio: IntArray,
        val pad: Double,
        val pluck: Double,
        val brightness: Double,
    )

    private fun style(name: String) = when (name) {
        // I – V – vi – IV in C, slow and warm.
        "bright" -> Style(
            104.0,
            listOf(intArrayOf(3, 7, 10, 15), intArrayOf(10, 14, 17, 22), intArrayOf(0, 3, 7, 12), intArrayOf(8, 12, 15, 20)),
            intArrayOf(0, 1, 2, 3, 2, 1, 2, 3), pad = 0.22, pluck = 0.42, brightness = 0.6,
        )
        "dreamy" -> Style(
            66.0,
            listOf(intArrayOf(0, 3, 7, 10, 14), intArrayOf(-4, 0, 3, 7, 10), intArrayOf(-7, -3, 0, 5, 9), intArrayOf(-2, 2, 5, 9, 12)),
            intArrayOf(0, 2, 4, 3, 1, 3, 4, 2), pad = 0.34, pluck = 0.18, brightness = 0.25,
        )
        else -> Style(
            76.0,
            listOf(intArrayOf(3, 7, 10, 15), intArrayOf(-2, 2, 5, 10), intArrayOf(0, 3, 7, 12), intArrayOf(-4, 0, 3, 8)),
            intArrayOf(0, 1, 2, 3, 2, 1, 0, 2), pad = 0.28, pluck = 0.3, brightness = 0.4,
        )
    }

    /** The soundtrack file for [name], made on first use. */
    fun file(dir: File, name: String): File {
        dir.mkdirs()
        val f = File(dir, "memory-$name-v1.wav")
        if (f.length() > 1000) return f
        val tmp = File(dir, f.name + ".tmp")
        write(tmp, render(style(name)))
        tmp.renameTo(f)
        return f
    }

    private const val RATE = 32_000

    private fun freq(semi: Int) = 220.0 * 2.0.pow(semi / 12.0)

    private fun render(s: Style): ShortArray {
        val beat = 60.0 / s.bpm
        val barSec = beat * 4
        val bars = 8 // the 4 chords twice
        val n = (barSec * bars * RATE).toInt()
        val left = DoubleArray(n)
        val right = DoubleArray(n)
        // Pads: each chord held for a bar, slow attack and release, a little detune for width.
        for (bar in 0 until bars) {
            val chord = s.chords[bar % s.chords.size]
            val start = (bar * barSec * RATE).toInt()
            val len = (barSec * RATE * 1.25).toInt()
            for (note in chord) {
                val f = freq(note - 12)
                for (i in 0 until len) {
                    val t = i.toDouble() / RATE
                    val env = (t / 0.8).coerceAtMost(1.0) * exp(-0.35 * t) * (if (t > barSec) exp(-(t - barSec) * 4) else 1.0)
                    val v = (sin(2 * PI * f * t) + 0.5 * sin(2 * PI * f * 1.003 * t + 0.4) + s.brightness * 0.3 * sin(4 * PI * f * t)) *
                        env * s.pad / chord.size
                    val k = (start + i) % n
                    left[k] += v * 0.9
                    right[k] += v * 1.1
                }
            }
            // Arpeggio: one note per eighth, a soft bell-like pluck.
            for (step in 0 until 8) {
                val note = chord[s.arpeggio[step] % chord.size] + 12
                val f = freq(note)
                val st = start + (step * beat / 2 * RATE).toInt()
                val len = (1.6 * RATE).toInt()
                val pan = if (step % 2 == 0) 0.8 else 1.2
                for (i in 0 until len) {
                    val t = i.toDouble() / RATE
                    val env = (t / 0.008).coerceAtMost(1.0) * exp(-3.2 * t)
                    val v = (sin(2 * PI * f * t) + 0.35 * sin(2 * PI * f * 2 * t) * exp(-6 * t) + 0.12 * sin(2 * PI * f * 3.01 * t) * s.brightness) *
                        env * s.pluck
                    val k = (st + i) % n
                    left[k] += v * (2 - pan) * 0.5
                    right[k] += v * pan * 0.5
                }
            }
        }
        // Simple reverb: a few feedback delays (wraps around, so the loop stays seamless).
        for ((ms, g) in listOf(37 to 0.33, 53 to 0.28, 71 to 0.24, 97 to 0.2)) {
            val d = RATE * ms / 1000
            for (pass in 0 until 2) for (i in 0 until n) {
                val j = (i - d + n) % n
                left[i] += left[j] * g * 0.5
                right[i] += right[(j + 7) % n] * g * 0.5
            }
        }
        var peak = 1e-9
        for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(left[i]), kotlin.math.abs(right[i]))
        val scale = 0.8 * Short.MAX_VALUE / peak
        val out = ShortArray(n * 2)
        for (i in 0 until n) {
            out[2 * i] = (left[i] * scale).toInt().toShort()
            out[2 * i + 1] = (right[i] * scale).toInt().toShort()
        }
        return out
    }

    private fun write(file: File, pcm: ShortArray) {
        DataOutputStream(FileOutputStream(file).buffered()).use { d ->
            fun le32(v: Int) { d.write(v and 0xff); d.write((v shr 8) and 0xff); d.write((v shr 16) and 0xff); d.write((v shr 24) and 0xff) }
            fun le16(v: Int) { d.write(v and 0xff); d.write((v shr 8) and 0xff) }
            val bytes = pcm.size * 2
            d.writeBytes("RIFF"); le32(36 + bytes); d.writeBytes("WAVE")
            d.writeBytes("fmt "); le32(16); le16(1); le16(2); le32(RATE); le32(RATE * 4); le16(4); le16(16)
            d.writeBytes("data"); le32(bytes)
            for (v in pcm) le16(v.toInt())
        }
    }
}

/** Which soundtrack a memory plays (Settings › Memories › Music). */
object MemorySoundtrack {
    suspend fun uri(context: android.content.Context, settings: io.github.drtron1c.cloudphotos.settings.AppSettings): android.net.Uri? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (val choice = settings.memoryMusic) {
                "none" -> null
                "custom" -> settings.memoryMusicUri.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) }
                    ?.takeIf { u -> runCatching { context.contentResolver.openInputStream(u)?.close(); true }.getOrDefault(false) }
                    ?: android.net.Uri.fromFile(MemoryMusic.file(File(context.filesDir, "music"), "gentle"))
                else -> android.net.Uri.fromFile(MemoryMusic.file(File(context.filesDir, "music"), choice))
            }
        }
}
