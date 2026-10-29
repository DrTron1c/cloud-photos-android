package io.github.drtron1c.cloudphotos.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseParserTest {
    private val body = File("src/main/assets/changelog.md").readText().let { log ->
        "Unofficial iCloud Photos client for Android 11+. Install CloudPhotos.apk.\n\nversionCode: 23\n\n" +
            log.lines().dropWhile { !it.startsWith("## ") }.takeWhile { true }.joinToString("\n").substringBefore("\n## Build 17")
    }

    private fun json(body: String) = org.json.JSONObject()
        .put("name", "Cloud Photos build 23")
        .put("body", body)
        .put(
            "assets",
            org.json.JSONArray().put(
                org.json.JSONObject().put("name", "CloudPhotos.apk").put("state", "uploaded").put("size", 1234)
                    .put("browser_download_url", "https://github.com/x/y/releases/download/latest/CloudPhotos.apk"),
            ),
        ).toString()

    @Test
    fun readsBuildApkAndNotes() {
        val info = ReleaseParser.parse(json(body))!!
        assertEquals(23, info.versionCode)
        assertEquals(1234L, info.sizeBytes)
        assertTrue(info.apkUrl.endsWith("CloudPhotos.apk"))
        assertTrue(info.notes.any { it.title.startsWith("Smart Search") })
        assertTrue(ReleaseParser.isNewer(23, 17))
    }

    @Test
    fun noApkMeansNoRelease() {
        assertNull(ReleaseParser.parse("""{"name":"Cloud Photos build 5","body":"","assets":[]}"""))
    }

    @Test
    fun bundledChangelogParses() {
        val entries = Changelog.parse(File("src/main/assets/changelog.md").readText())
        assertTrue(entries.size >= 3)
        assertTrue(entries.first().build >= 20)
        assertTrue(entries.first().items.size >= 4)
        // Someone updating from build 17 sees only the newest section.
        assertEquals(1, Changelog.unseen(entries, 0, 99).size)
    }
}
