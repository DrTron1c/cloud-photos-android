package io.github.drtron1c.cloudphotos.ai

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Downloads (once per CI run) and checks the files the app itself downloads. */
object TestFiles {
    val cache: File by lazy { File(System.getProperty("aicore.cache") ?: "build/model-cache").apply { mkdirs() } }

    private fun open(url: String): HttpURLConnection {
        var u = url
        repeat(6) {
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

    fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    fun fetch(url: String, name: String): File {
        val f = File(cache, name)
        if (!f.exists() || f.length() == 0L) open(url).inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    /** A pinned Hugging Face file; fails loudly if it ever changes. */
    fun hf(repo: String, revision: String, path: String, sha: String): File {
        val f = fetch("https://huggingface.co/$repo/resolve/$revision/$path", repo.replace('/', '_') + "_" + path.replace('/', '_'))
        val actual = sha256(f)
        println("FILE $repo/$path size=${f.length()} sha256=$actual")
        check(actual == sha) { "$path changed: $actual" }
        return f
    }

    fun pixels(file: File): Triple<IntArray, Int, Int> {
        val img = ImageIO.read(file)
        return Triple(img.getRGB(0, 0, img.width, img.height, null, 0, img.width), img.width, img.height)
    }
}
