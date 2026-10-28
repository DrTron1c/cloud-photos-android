package io.github.drtron1c.cloudphotos.photos

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** One stored rendition of an asset (thumbnail, original, edited, paired Live Photo video, ...). */
data class Version(
    val url: String,
    val size: Long,
    val checksum: String,
    val fileType: String?,
    val width: Int,
    val height: Int,
)

data class Asset(
    val id: String,               // CPLMaster recordName (stable across edits)
    val assetRecordName: String,  // CPLAsset recordName (what gets favorited / deleted)
    val assetRecordType: String,
    val changeTag: String,
    val date: Long,
    val addedDate: Long,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val itemType: String,
    val filename: String,
    val width: Int,
    val height: Int,
    val isVideo: Boolean,
    val durationSec: Double?,
    val thumb: Version?,
    val medium: Version?,
    val edited: Version?,         // rendered edit (photo JPEG or video)
    val original: Version?,
    val liveVideo: Version?,      // Live Photo motion
    val videoMedium: Version?,
) {
    val isLive get() = !isVideo && liveVideo != null

    /** What the iPhone shows, shares and exports: the edit if there is one, else the original. */
    val current: Version? get() = edited ?: original ?: medium

    /** Best image the phone can decode for full-screen viewing. */
    val displayImage: Version?
        get() {
            edited?.takeIf { !isVideo }?.let { return it }
            val o = original
            if (o != null && !isVideo && decodable(o.fileType ?: itemType)) return o
            return medium ?: thumb
        }

    val thumbnail: Version? get() = thumb ?: medium

    /** Best stream for playback. */
    val playbackVideo: Version?
        get() = if (isVideo) edited ?: original ?: videoMedium else null

    val currentFilename: String
        get() {
            val cur = current ?: return filename
            if (cur === original) return filename
            val base = filename.substringBeforeLast('.', filename)
            return base + "." + extensionFor(cur.fileType, if (isVideo) "MOV" else "JPG")
        }

    companion object {
        private fun decodable(type: String): Boolean {
            val t = type.lowercase()
            return t.contains("jpeg") || t.contains("heic") || t.contains("heif") || t.contains("png") ||
                t.contains("gif") || t.contains("webp")
        }

        fun extensionFor(fileType: String?, fallback: String): String = when (fileType?.lowercase()) {
            "public.jpeg" -> "JPG"
            "public.heic" -> "HEIC"
            "public.heif" -> "HEIF"
            "public.png" -> "PNG"
            "com.compuserve.gif" -> "GIF"
            "com.apple.quicktime-movie" -> "MOV"
            "public.mpeg-4" -> "MP4"
            "com.adobe.raw-image" -> "DNG"
            else -> fallback
        }

        fun mimeFor(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "dng" -> "image/x-adobe-dng"
            "mov" -> "video/quicktime"
            "mp4", "m4v" -> "video/mp4"
            else -> "application/octet-stream"
        }

        private fun JSONObject.fieldValue(name: String): Any? = optJSONObject(name)?.opt("value")

        private fun JSONObject.longField(name: String): Long? = when (val v = fieldValue(name)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }

        private fun version(fields: JSONObject, prefix: String): Version? {
            val res = fields.optJSONObject("${prefix}Res")?.optJSONObject("value") ?: return null
            val url = res.optString("downloadURL")
            if (url.isEmpty()) return null
            return Version(
                url = url,
                size = res.optLong("size"),
                checksum = res.optString("fileChecksum").ifEmpty { url.substringBefore('?') },
                fileType = fields.optJSONObject("${prefix}FileType")?.optString("value"),
                width = fields.longField("${prefix}Width")?.toInt() ?: 0,
                height = fields.longField("${prefix}Height")?.toInt() ?: 0,
            )
        }

        /** Asset-level renditions win over master ones (they reflect edits), like icloudpd. */
        private fun pick(asset: JSONObject, master: JSONObject, prefix: String) =
            version(asset, prefix) ?: version(master, prefix)

        private fun decodeFilename(master: JSONObject, fallbackId: String, itemType: String): String {
            val f = master.optJSONObject("filenameEnc")
            if (f != null) {
                val value = f.optString("value")
                val name = when (f.optString("type")) {
                    "STRING" -> value
                    else -> runCatching { String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
                }
                if (!name.isNullOrBlank()) return name
            }
            val clean = fallbackId.replace(Regex("[^0-9A-Za-z]"), "_").take(12)
            return clean + "." + extensionFor(itemType, "JPG")
        }

        /**
         * Turn one page of CloudKit records (CPLMaster + CPLAsset + others) into assets,
         * in the order the masters appear.
         */
        fun fromRecords(records: JSONArray): List<Asset> {
            val assetsByMaster = HashMap<String, JSONObject>()
            val masters = ArrayList<JSONObject>()
            for (i in 0 until records.length()) {
                val r = records.optJSONObject(i) ?: continue
                when (r.optString("recordType")) {
                    "CPLAsset" -> {
                        val masterId = r.optJSONObject("fields")?.optJSONObject("masterRef")
                            ?.optJSONObject("value")?.optString("recordName")
                        if (!masterId.isNullOrEmpty()) assetsByMaster[masterId] = r
                    }
                    "CPLMaster" -> masters += r
                }
            }
            val out = ArrayList<Asset>(masters.size)
            for (m in masters) {
                val id = m.optString("recordName")
                val a = assetsByMaster[id] ?: continue
                val mf = m.optJSONObject("fields") ?: JSONObject()
                val af = a.optJSONObject("fields") ?: JSONObject()
                val itemType = mf.optJSONObject("itemType")?.optString("value") ?: ""
                val originalType = mf.optJSONObject("resOriginalFileType")?.optString("value") ?: itemType
                val isVideo = itemType.contains("movie") || itemType.contains("mpeg") || itemType.contains("video") ||
                    originalType.contains("movie") || originalType.contains("mpeg-4")
                val original = version(mf, "resOriginal")
                val duration = af.longField("duration")?.toDouble()
                    ?: run {
                        val v = mf.longField("vidComplDurValue") ?: af.longField("vidComplDurValue")
                        val s = mf.longField("vidComplDurScale") ?: af.longField("vidComplDurScale")
                        if (isVideo && v != null && s != null && s > 0) v.toDouble() / s else null
                    }
                out += Asset(
                    id = id,
                    assetRecordName = a.optString("recordName"),
                    assetRecordType = a.optString("recordType", "CPLAsset"),
                    changeTag = a.optString("recordChangeTag"),
                    date = af.longField("assetDate") ?: af.longField("addedDate") ?: 0L,
                    addedDate = af.longField("addedDate") ?: 0L,
                    isFavorite = (af.longField("isFavorite") ?: 0L) == 1L,
                    isHidden = (af.longField("isHidden") ?: 0L) == 1L,
                    itemType = itemType,
                    filename = decodeFilename(mf, id, itemType),
                    width = original?.width?.takeIf { it > 0 } ?: (mf.longField("resOriginalWidth")?.toInt() ?: 0),
                    height = original?.height?.takeIf { it > 0 } ?: (mf.longField("resOriginalHeight")?.toInt() ?: 0),
                    isVideo = isVideo,
                    durationSec = if (isVideo) duration else null,
                    thumb = pick(af, mf, "resJPEGThumb"),
                    medium = pick(af, mf, "resJPEGMed"),
                    edited = if (isVideo) version(af, "resVidFull") else version(af, "resJPEGFull"),
                    original = original,
                    liveVideo = if (isVideo) null else (pick(af, mf, "resVidMed") ?: version(mf, "resOriginalVidCompl")),
                    videoMedium = if (isVideo) pick(af, mf, "resVidMed") else null,
                )
            }
            return out
        }
    }
}

enum class AlbumKind { Library, Smart, User, Hidden, Deleted }

/** How to query one album / collection through CloudKit. */
data class AlbumSpec(
    val key: String,
    val title: String,
    val kind: AlbumKind,
    val objType: String,
    val listType: String,
    val filterJson: String? = null,
    val icon: String = "photo",
) {
    fun filters(): JSONArray? = filterJson?.let { JSONArray(it) }

    companion object {
        private fun smart(key: String, title: String, smart: String, objSuffix: String, icon: String) = AlbumSpec(
            key = key,
            title = title,
            kind = AlbumKind.Smart,
            objType = "CPLAssetInSmartAlbumByAssetDate:$objSuffix",
            listType = "CPLAssetAndMasterInSmartAlbumByAssetDate",
            filterJson = """[{"fieldName":"smartAlbum","comparator":"EQUALS","fieldValue":{"type":"STRING","value":"$smart"}}]""",
            icon = icon,
        )

        val LIBRARY = AlbumSpec(
            "library", "Library", AlbumKind.Library,
            "CPLAssetByAssetDateWithoutHiddenOrDeleted", "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted",
        )
        val FAVORITES = smart("favorites", "Favourites", "FAVORITE", "Favorite", "favorite")
        val VIDEOS = smart("videos", "Videos", "VIDEO", "Video", "video")
        val LIVE = smart("live", "Live Photos", "LIVE", "Live", "live")
        val PANORAMAS = smart("panoramas", "Panoramas", "PANORAMA", "Panorama", "panorama")
        val SLOMO = smart("slomo", "Slo-mo", "SLOMO", "Slomo", "slomo")
        val TIMELAPSE = smart("timelapse", "Time-lapse", "TIMELAPSE", "Timelapse", "timelapse")
        val SCREENSHOTS = smart("screenshots", "Screenshots", "SCREENSHOT", "Screenshot", "screenshot")
        val BURSTS = AlbumSpec(
            "bursts", "Bursts", AlbumKind.Smart,
            "CPLAssetBurstStackAssetByAssetDate", "CPLBurstStackAssetAndMasterByAssetDate", icon = "burst",
        )
        val HIDDEN = AlbumSpec(
            "hidden", "Hidden", AlbumKind.Hidden,
            "CPLAssetHiddenByAssetDate", "CPLAssetAndMasterHiddenByAssetDate", icon = "hidden",
        )
        val RECENTLY_DELETED = AlbumSpec(
            "deleted", "Recently Deleted", AlbumKind.Deleted,
            "CPLAssetDeletedByExpungedDate", "CPLAssetAndMasterDeletedByExpungedDate", icon = "deleted",
        )
        val MEDIA_TYPES = listOf(VIDEOS, LIVE, PANORAMAS, SLOMO, TIMELAPSE, SCREENSHOTS, BURSTS)

        fun user(recordName: String, title: String) = AlbumSpec(
            key = "album:$recordName",
            title = title,
            kind = AlbumKind.User,
            objType = "CPLContainerRelationNotDeletedByAssetDate:$recordName",
            listType = "CPLContainerRelationLiveByAssetDate",
            filterJson = """[{"fieldName":"parentId","comparator":"EQUALS","fieldValue":{"type":"STRING","value":"$recordName"}}]""",
            icon = "album",
        )
    }
}
