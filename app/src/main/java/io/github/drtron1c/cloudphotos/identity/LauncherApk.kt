package io.github.drtron1c.cloudphotos.identity

import com.android.apksig.ApkSigner
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Turns the companion-app template into a real app with your name and picture.
 * Plain JVM code (no Android APIs) so it can be tested off-device.
 */
object LauncherApk {
    const val PACKAGE = "io.github.drtron1c.cloudphotos.launcher"
    const val NAME_PLACEHOLDER = "CloudPhotosCustomLauncherNamePlaceholder"
    const val MAX_NAME = 30

    fun cleanName(name: String): String = name.replace(Regex("[\\r\\n\\t]"), " ").trim().take(MAX_NAME)

    /**
     * Copies [template] with the label replaced by [name] and the icon picture replaced by
     * [iconPng], then signs the result into [out].
     */
    fun build(template: File, name: String, iconPng: ByteArray, key: PrivateKey, cert: X509Certificate, out: File) {
        val label = cleanName(name)
        require(label.isNotEmpty()) { "Name can't be empty" }
        val unsigned = File(out.parentFile, out.name + ".unsigned")
        var pictures = 0
        var manifest = false
        ZipFile(template).use { zip ->
            ZipOutputStream(unsigned.outputStream().buffered()).use { zos ->
                for (e in zip.entries().asSequence()) {
                    if (e.isDirectory || e.name.startsWith("META-INF/")) continue
                    val original = zip.getInputStream(e).use { it.readBytes() }
                    val bytes = when {
                        e.name == "AndroidManifest.xml" -> {
                            manifest = true
                            Axml.replaceString(original, NAME_PLACEHOLDER, label)
                        }
                        e.name.startsWith("res/") && e.name.endsWith(".png") -> {
                            pictures++
                            iconPng
                        }
                        else -> original
                    }
                    // resources.arsc (and pictures) must stay uncompressed for Android to load them.
                    val stored = e.method == ZipEntry.STORED || e.name.endsWith(".png")
                    val entry = ZipEntry(e.name)
                    if (stored) {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    } else {
                        entry.method = ZipEntry.DEFLATED
                    }
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
        try {
            check(manifest && pictures > 0) { "Companion app template is damaged" }
            // apksig also re-aligns uncompressed entries to 4 bytes, as Android requires.
            ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("CERT", key, listOf(cert)).build()))
                .setInputApk(unsigned)
                .setOutputApk(out)
                .setMinSdkVersion(30)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build()
                .sign()
        } finally {
            unsigned.delete()
        }
    }
}
