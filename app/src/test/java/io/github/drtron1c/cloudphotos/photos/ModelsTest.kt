package io.github.drtron1c.cloudphotos.photos

import io.github.drtron1c.cloudphotos.ui.formatDuration
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Record shape taken from a recorded iCloud response (icloudpd's test cassettes). */
class ModelsTest {
    private fun records(durationMs: Long) = JSONArray(
        """
        [ {
          "recordName" : "AY6c+BsE0jjaXx9tmVGJM1D2VcEO", "recordType" : "CPLMaster",
          "fields" : {
            "itemType" : { "value" : "com.apple.quicktime-movie", "type" : "STRING" },
            "filenameEnc" : { "value" : "SU1HXzAwMDEuTU9W", "type" : "ENCRYPTED_BYTES" },
            "resOriginalRes" : { "value" : { "size" : 13077558, "downloadURL" : "https://cvws.icloud-content.com/B/x/${'$'}{f}?o=1", "fileChecksum" : "AbCd" }, "type" : "ASSETID" },
            "resOriginalFileType" : { "value" : "com.apple.quicktime-movie", "type" : "STRING" },
            "resOriginalWidth" : { "value" : 1920, "type" : "INT64" },
            "resOriginalHeight" : { "value" : 1080, "type" : "INT64" }
          }, "recordChangeTag" : "m1"
        }, {
          "recordName" : "F2A23C38-0020-42FE-A273-2923ADE3CAED", "recordType" : "CPLAsset",
          "fields" : {
            "masterRef" : { "value" : { "recordName" : "AY6c+BsE0jjaXx9tmVGJM1D2VcEO", "action" : "DELETE_SELF" }, "type" : "REFERENCE" },
            "assetDate" : { "value" : 1532940108000, "type" : "TIMESTAMP" },
            "duration" : { "value" : $durationMs, "type" : "INT64" },
            "isFavorite" : { "value" : 1, "type" : "INT64" }
          }, "recordChangeTag" : "a1"
        } ]
        """.trimIndent()
    )

    @Test
    fun videoDurationIsMilliseconds() {
        val a = Asset.fromRecords(records(11902)).single()
        assertTrue(a.isVideo)
        assertEquals(11.902, a.durationSec!!, 0.0001)
        assertEquals("0:12", formatDuration(a.durationSec))
        assertEquals("1:40", formatDuration(Asset.fromRecords(records(99807)).single().durationSec))
        assertEquals("13:10", formatDuration(Asset.fromRecords(records(789582)).single().durationSec))
    }

    @Test
    fun parsesNameDateAndFlags() {
        val a = Asset.fromRecords(records(1950)).single()
        assertEquals("IMG_0001.MOV", a.filename)
        assertEquals(1532940108000L, a.date)
        assertTrue(a.isFavorite)
        assertEquals("F2A23C38-0020-42FE-A273-2923ADE3CAED", a.assetRecordName)
        assertEquals("a1", a.changeTag)
        assertEquals(1920, a.width)
    }
}
