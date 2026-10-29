package io.github.drtron1c.cloudphotos.ai

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Runs the real MobileCLIP model (downloaded from Hugging Face, as the app does) on real and
 * synthetic pictures, and prints the numbers used to tune search.
 */
class ClipModelTest {
    companion object {
        private const val REPO = "Xenova/mobileclip_s0"
        private lateinit var cache: File
        private lateinit var files: List<String>
        private lateinit var revision: String

        @BeforeClass
        @JvmStatic
        fun setUp() {
            cache = File(System.getProperty("aicore.cache") ?: "build/model-cache").apply { mkdirs() }
            val info = JSONObject(fetchText("https://huggingface.co/api/models/$REPO"))
            revision = info.getString("sha")
            val siblings = info.getJSONArray("siblings")
            files = (0 until siblings.length()).map { siblings.getJSONObject(it).getString("rfilename") }
            println("MODEL_REVISION=$revision")
            println("MODEL_FILES=" + files.joinToString(","))
        }

        private fun open(url: String): HttpURLConnection {
            var u = url
            repeat(5) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 30_000
                c.readTimeout = 120_000
                c.setRequestProperty("User-Agent", "CloudPhotos-CI")
                val code = c.responseCode
                if (code in 300..399) {
                    u = URL(URL(u), c.getHeaderField("Location")).toString()
                    c.disconnect()
                } else {
                    check(code == 200) { "HTTP $code for $u" }
                    return c
                }
            }
            error("Too many redirects for $url")
        }

        private fun fetchText(url: String): String = open(url).inputStream.use { String(it.readBytes()) }

        fun download(path: String): File {
            val f = File(cache, path.replace('/', '_'))
            if (!f.exists() || f.length() == 0L) {
                open("https://huggingface.co/$REPO/resolve/$revision/$path").inputStream.use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            println("FILE $path size=${f.length()} sha256=$sha")
            return f
        }

        private fun fetchImage(url: String, name: String): File {
            val f = File(cache, name)
            if (!f.exists()) open(url).inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            return f
        }
    }

    private fun tokenizer() = ClipTokenizer.fromTokenizerJson(download("tokenizer.json").readText())

    private fun pixels(file: File): Triple<IntArray, Int, Int> {
        val img = ImageIO.read(file)
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return Triple(px, img.width, img.height)
    }

    private fun solid(rgb: Int, w: Int = 300, h: Int = 200) = Triple(IntArray(w * h) { rgb or (0xff shl 24) }, w, h)

    @Test
    fun tokenizerMatchesClip() {
        val ids = tokenizer().encode("A photo of a cat")
        println("TOKENS " + ids.take(10))
        assertArrayEquals(intArrayOf(49406, 320, 1125, 539, 320, 2368, 49407), ids.take(7).toIntArray())
        assertTrue(ids.size == 77)
    }

    @Test
    fun picturesMatchTheirWords() {
        val tok = tokenizer()
        val textFile = listOf("onnx/text_model_quantized.onnx", "onnx/text_model.onnx").first { it in files }
        val visionCandidates = listOf(
            "onnx/vision_model_quantized.onnx", "onnx/vision_model_uint8.onnx", "onnx/vision_model_fp16.onnx", "onnx/vision_model.onnx",
        ).filter { it in files }
        val cats = pixels(fetchImage("http://images.cocodataset.org/val2017/000000039769.jpg", "cats.jpg"))
        val pictures = mapOf("cats" to cats, "red" to solid(0xD01818), "blue" to solid(0x1830D0))
        val phrases = listOf("two cats", "a red hat", "a car", "red", "blue", "a beach at sunset")

        var fullVision: Map<String, FloatArray>? = null
        for (visionFile in visionCandidates.reversed()) {
            ClipModel(download(visionFile), download(textFile), tok).use { model ->
                val t0 = System.nanoTime()
                val img = pictures.mapValues { (_, p) -> model.embedImage(ImagePrep.toChw(p.first, p.second, p.third)) }
                val ms = (System.nanoTime() - t0) / 1e6 / pictures.size
                val txt = phrases.associateWith { model.embedText("a photo of $it") }
                println("VISION $visionFile  ${"%.1f".format(ms)} ms/picture  dim=${img.values.first().size}")
                for ((name, e) in img) {
                    println("  $name: " + txt.entries.joinToString("  ") { (p, t) -> "$p=${"%.3f".format(dot(e, t))}" })
                }
                fullVision?.let { ref ->
                    println("  agreement with fp32: " + img.entries.joinToString { (k, v) -> "$k=${"%.3f".format(dot(v, ref.getValue(k)))}" })
                }
                if (visionFile == "onnx/vision_model.onnx") fullVision = img

                val catScores = txt.mapValues { dot(img.getValue("cats"), it.value) }
                assertTrue("cats picture should match 'two cats' best: $catScores", catScores.maxBy { it.value }.key == "two cats")
                assertTrue(dot(img.getValue("red"), txt.getValue("red")) > dot(img.getValue("red"), txt.getValue("blue")))
                assertTrue(dot(img.getValue("blue"), txt.getValue("blue")) > dot(img.getValue("blue"), txt.getValue("red")))

                val packed = Packed.of(img.getValue("cats"))
                val exact = dot(img.getValue("cats"), txt.getValue("two cats"))
                assertTrue(kotlin.math.abs(packed.similarity(txt.getValue("two cats")) - exact) < 0.01f)
            }
        }
    }
}
