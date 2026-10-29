package io.github.drtron1c.cloudphotos.identity

import com.android.apksig.ApkVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Builds real companion apps from the real template and checks they're valid, signed APKs. */
class LauncherApkTest {
    private val template = File("build/generated/launcherTemplate/launcher-template.apk")

    /** A throwaway key made for this test run (no real key is ever in the repository). */
    private fun key(): Pair<PrivateKey, X509Certificate> {
        val file = File("build/launcher-test/test-key.p12")
        if (!file.exists()) {
            file.parentFile.mkdirs()
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(
                keytool, "-genkeypair", "-keystore", file.path, "-storetype", "PKCS12", "-storepass", "testpass",
                "-keypass", "testpass", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
                "-dname", "CN=Cloud Photos test",
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            check(p.waitFor() == 0 && file.exists()) { "keytool failed" }
        }
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, "testpass".toCharArray()) }
        return ks.getKey("test", "testpass".toCharArray()) as PrivateKey to ks.getCertificate("test") as X509Certificate
    }

    /** A 432×432 diagonal-gradient PNG with a white dot (no java.awt on the Android test classpath). */
    private fun png(c1: Int, c2: Int): ByteArray {
        val size = 432
        val raw = ByteArrayOutputStream()
        for (y in 0 until size) {
            raw.write(0)
            for (x in 0 until size) {
                val t = (x + y) / (2f * size)
                val dx = x - 216; val dy = y - 216
                val dot = dx * dx + dy * dy < 90 * 90
                for (shift in intArrayOf(16, 8, 0)) {
                    val a = (c1 shr shift) and 0xff
                    val b = (c2 shr shift) and 0xff
                    raw.write(if (dot) 255 else (a + (b - a) * t).toInt())
                }
            }
        }
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val t = type.toByteArray()
            data.write(t); data.write(body)
            data.writeInt(CRC32().apply { update(t); update(body) }.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(size); writeInt(size); write(byteArrayOf(8, 2, 0, 0, 0)) } }
        chunk("IHDR", ihdr.toByteArray())
        val z = ByteArrayOutputStream().also { DeflaterOutputStream(it, Deflater(9)).use { d -> d.write(raw.toByteArray()) } }
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun make(name: String, file: String, c1: Int, c2: Int): File {
        val out = File("build/launcher-test/$file").apply { parentFile.mkdirs(); delete() }
        val (k, c) = key()
        LauncherApk.build(template, name, png(c1, c2), k, c, out)
        return out
    }

    @Test
    fun buildsSignedApkWithNameAndPicture() {
        val name = "Mümmy’s Fotos 📸"
        val out = make(name, "CustomLauncher-unicode.apk", 0xFF6A00, 0xC00060)

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
        val out = make("AAA My Photos", "CustomLauncher.apk", 0x00B38F, 0x1E3C72)
        assertTrue(ApkVerifier.Builder(out).build().verify().isVerified)
    }

    @Test
    fun cleansNames() {
        assertEquals("Holiday Pics", LauncherApk.cleanName("  Holiday\nPics  ").replace("  ", " "))
        assertEquals(LauncherApk.MAX_NAME, LauncherApk.cleanName("x".repeat(80)).length)
    }
}
