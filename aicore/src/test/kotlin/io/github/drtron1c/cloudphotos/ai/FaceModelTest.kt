package io.github.drtron1c.cloudphotos.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Runs the real face models (the exact files the app downloads) and checks them against what
 * OpenCV's own implementation gives for the same pictures.
 */
class FaceModelTest {
    private fun model() = FaceModel(
        TestFiles.hf(FacePins.DET_REPO, FacePins.DET_REV, FacePins.DET_FILE, FacePins.DET_SHA),
        TestFiles.hf(FacePins.REC_REPO, FacePins.REC_REV, FacePins.REC_FILE, FacePins.REC_SHA),
    )

    private val messi = "https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/messi5.jpg"
    private val lena = "https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/lena.jpg"

    @Test
    fun findsFacesWhereOpenCvDoes() {
        model().use { m ->
            println(m.describe())
            val (px, w, h) = TestFiles.pixels(TestFiles.fetch(messi, "messi.jpg"))
            val faces = m.detect(px, w, h)
            println("messi faces: " + faces.joinToString { "%.1f,%.1f %.1fx%.1f s=%.2f".format(it.x, it.y, it.w, it.h, it.score) })
            assertEquals(1, faces.size)
            // OpenCV: [225.8, 93.3, 30.9, 39.8], left eye at (239.3, 106.4)
            val f = faces[0]
            assertTrue("x ${f.x}", abs(f.x - 225.8f) < 4f)
            assertTrue("y ${f.y}", abs(f.y - 93.3f) < 4f)
            assertTrue("w ${f.w}", abs(f.w - 30.9f) < 5f)
            assertTrue("eye ${f.landmarks[0]}", abs(f.landmarks[0] - 239.3f) < 4f && abs(f.landmarks[1] - 106.4f) < 4f)

            val (lp, lw, lh) = TestFiles.pixels(TestFiles.fetch(lena, "lena.jpg"))
            val lf = m.detect(lp, lw, lh)
            println("lena faces: " + lf.joinToString { "%.1f,%.1f %.1fx%.1f".format(it.x, it.y, it.w, it.h) })
            assertEquals(1, lf.size)
            assertTrue(abs(lf[0].x - 207.8f) < 5f && abs(lf[0].y - 182.5f) < 5f)
        }
    }

    @Test
    fun samePersonMatchesDifferentPeopleDont() {
        model().use { m ->
            val (lp, lw, lh) = TestFiles.pixels(TestFiles.fetch(lena, "lena.jpg"))
            val (mp, mw, mh) = TestFiles.pixels(TestFiles.fetch(messi, "messi.jpg"))
            // The same face mirrored and shrunk still has to match.
            val flipped = IntArray(lp.size) { i -> val y = i / lw; val x = i % lw; lp[y * lw + (lw - 1 - x)] }
            val small = IntArray(256 * 256) { i -> FaceModel.bilinear(lp, lw, lh, (i % 256) * 2f + 0.5f, (i / 256) * 2f + 0.5f) }
            fun emb(px: IntArray, w: Int, h: Int) = m.embed(px, w, h, m.detect(px, w, h).first())
            val a = emb(lp, lw, lh)
            val b = emb(flipped, lw, lh)
            val c = emb(small, 256, 256)
            val d = emb(mp, mw, mh)
            println("lena~flipped=%.3f lena~small=%.3f lena~messi=%.3f".format(dot(a, b), dot(a, c), dot(a, d)))
            // OpenCV gives 0.912, 0.947 and 0.160 with this model.
            assertTrue(dot(a, b) > 0.8f)
            assertTrue(dot(a, c) > 0.8f)
            assertTrue(dot(a, d) < 0.35f)
            assertTrue(abs(Packed.of(a).similarity(b) - dot(a, b)) < 0.02f)
        }
    }

    @Test
    fun similarityTransformRecoversAKnownOne() {
        // Rotate by 30°, scale 2, shift (5, -3): the estimate must give the same matrix back.
        val ang = Math.toRadians(30.0)
        val a = (2 * Math.cos(ang)).toFloat(); val b = (2 * Math.sin(ang)).toFloat()
        val dst = FaceModel.REFERENCE
        val src = FloatArray(10) { 0f }
        // src = inverse(M)·dst
        val m = floatArrayOf(a, -b, 5f, b, a, -3f)
        val inv = FaceModel.invert(m)
        for (i in 0 until 5) {
            src[2 * i] = inv[0] * dst[2 * i] + inv[1] * dst[2 * i + 1] + inv[2]
            src[2 * i + 1] = inv[3] * dst[2 * i] + inv[4] * dst[2 * i + 1] + inv[5]
        }
        val est = FaceModel.similarity(src, dst)
        for (k in 0 until 6) assertTrue("$k ${est[k]} vs ${m[k]}", abs(est[k] - m[k]) < 1e-3f)
    }
}
