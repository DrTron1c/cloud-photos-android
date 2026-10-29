package io.github.drtron1c.cloudphotos.identity

import com.android.apksig.ApkVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/** Builds real companion apps from the real template and checks they're valid, signed APKs. */
class LauncherApkTest {
    private val template = File("build/generated/launcherTemplate/launcher-template.apk")

    private fun key(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("PKCS12")
        File("signing/cloudphotos.p12").inputStream().use { ks.load(it, "cloudphotos".toCharArray()) }
        return ks.getKey("cloudphotos", "cloudphotos".toCharArray()) as PrivateKey to
            ks.getCertificate("cloudphotos") as X509Certificate
    }

    private fun png(c1: Color, c2: Color): ByteArray {
        val img = BufferedImage(432, 432, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.paint = GradientPaint(0f, 0f, c1, 432f, 432f, c2)
        g.fillRect(0, 0, 432, 432)
        g.color = Color.WHITE
        g.fillOval(126, 126, 180, 180)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun make(name: String, file: String, c1: Color, c2: Color): File {
        val out = File("build/launcher-test/$file").apply { parentFile.mkdirs(); delete() }
        val (k, c) = key()
        LauncherApk.build(template, name, png(c1, c2), k, c, out)
        return out
    }

    @Test
    fun buildsSignedApkWithNameAndPicture() {
        val name = "Mümmy’s Fotos 📸"
        val out = make(name, "CustomLauncher-unicode.apk", Color(0xFF, 0x6A, 0x00), Color(0xC0, 0x00, 0x60))

        val result = ApkVerifier.Builder(out).build().verify()
        assertTrue("errors: " + result.errors + " " + result.v2SchemeSigners.flatMap { it.errors }, result.isVerified)
        assertTrue(result.isVerifiedUsingV2Scheme)

        ZipFile(out).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            val strings = Axml.strings(manifest)
            assertTrue(strings.contains(name))
            assertFalse(strings.contains(LauncherApk.NAME_PLACEHOLDER))
            assertEquals(ZipEntry.STORED, zip.getEntry("resources.arsc").method)
            val pngs = zip.entries().asSequence().filter { it.name.startsWith("res/") && it.name.endsWith(".png") }.toList()
            assertEquals(1, pngs.size)
        }
    }

    /** Installed on the test phone by the CI smoke test; "AAA" sorts it first in the app drawer. */
    @Test
    fun buildsSmokeTestApp() {
        val out = make("AAA My Photos", "CustomLauncher.apk", Color(0x00, 0xB3, 0x8F), Color(0x1E, 0x3C, 0x72))
        assertTrue(ApkVerifier.Builder(out).build().verify().isVerified)
    }

    @Test
    fun cleansNames() {
        assertEquals("Holiday Pics", LauncherApk.cleanName("  Holiday\nPics  ").replace("  ", " "))
        assertEquals(LauncherApk.MAX_NAME, LauncherApk.cleanName("x".repeat(80)).length)
    }
}
