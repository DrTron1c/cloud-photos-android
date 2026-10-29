package io.github.drtron1c.cloudphotos.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Reads text from pictures with the exact model files the app downloads. */
class TextModelTest {
    private fun model() = TextModel(
        TestFiles.hf(TextPins.REPO, TextPins.REV, TextPins.DET_FILE, TextPins.DET_SHA),
        TestFiles.hf(TextPins.REPO, TextPins.REV, TextPins.REC_FILE, TextPins.REC_SHA),
    )

    private fun render(lines: List<String>, w: Int = 1200, h: Int = 700): Triple<IntArray, Int, Int> {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(235, 228, 210)
        g.fillRect(0, 0, w, h)
        g.color = Color(30, 30, 40)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 64)
        lines.forEachIndexed { i, s -> g.drawString(s, 60, 140 + i * 160) }
        g.dispose()
        return Triple(img.getRGB(0, 0, w, h, null, 0, w), w, h)
    }

    @Test
    fun readsPrintedText() {
        model().use { m ->
            println("characters: ${m.characters.size}")
            assertTrue(m.characters.size > 6000)
            val (px, w, h) = render(listOf("OPEN 24 HOURS", "Coffee 3.50", "Platform 9"))
            val t0 = System.nanoTime()
            val lines = m.read(px, w, h)
            println("read in %.0f ms: ".format((System.nanoTime() - t0) / 1e6) + lines.joinToString(" | ") { "${it.text} (%.2f)".format(it.confidence) })
            val all = lines.joinToString(" ") { it.text }.uppercase()
            assertTrue(all, all.contains("OPEN") && all.contains("24") && all.contains("HOURS"))
            assertTrue(all, all.contains("COFFEE") && all.contains("3.50"))
            assertTrue(all, all.contains("PLATFORM"))
            // Lines come back top to bottom.
            assertTrue(lines.first().text.uppercase().contains("OPEN"))
        }
    }

    @Test
    fun blankPictureHasNoText() {
        model().use { m ->
            val w = 640; val h = 480
            val px = IntArray(w * h) { 0xff808890.toInt() }
            assertTrue(m.read(px, w, h).isEmpty())
        }
    }
}
