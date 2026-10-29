package io.github.drtron1c.cloudphotos.memories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class MemoryMusicTest {
    @Test
    fun rendersPlayableLoopsForEveryStyle() {
        val dir = Files.createTempDirectory("music").toFile()
        for ((style, _) in MemoryMusic.STYLES) {
            val f = MemoryMusic.file(dir, style)
            val b = f.readBytes()
            assertEquals("RIFF", String(b, 0, 4))
            assertEquals("WAVE", String(b, 8, 4))
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2, bb.getShort(22).toInt())        // stereo
            val rate = bb.getInt(24)
            val dataBytes = bb.getInt(40)
            val seconds = dataBytes / (rate * 4.0)
            println("$style: %.1f s, %d KB".format(seconds, b.size / 1024))
            assertTrue(seconds in 15.0..40.0)
            // Not silent, not clipped.
            var peak = 0
            for (i in 44 until b.size - 1 step 2) peak = maxOf(peak, kotlin.math.abs(bb.getShort(i).toInt()))
            assertTrue(peak in 10_000..32_000)
            // Second call reuses the file.
            assertEquals(f.lastModified(), MemoryMusic.file(dir, style).lastModified())
        }
    }
}
