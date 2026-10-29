package io.github.drtron1c.cloudphotos.photos

import io.github.drtron1c.cloudphotos.auth.AppleAuth
import io.github.drtron1c.cloudphotos.net.AuthState
import io.github.drtron1c.cloudphotos.net.HttpException
import io.github.drtron1c.cloudphotos.net.SessionExpiredException
import io.github.drtron1c.cloudphotos.net.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone
import java.util.UUID

data class UserAlbum(val recordName: String, val title: String)

class PhotosApiException(message: String) : Exception(message)

/**
 * The iCloud Photos library as CloudKit exposes it to icloud.com:
 * database "com.apple.photos.cloud", private scope, zone "PrimarySync" (or a shared-library zone).
 */
class PhotosApi(
    private val http: OkHttpClient,
    private val state: AuthState,
    /** Called once when CloudKit rejects the session; return true if a fresh session was obtained. */
    private val reauth: suspend () -> Boolean,
) {
    @Volatile var zoneName: String = "PrimarySync"

    private fun base(): String {
        val root = state.ckDatabaseUrl ?: throw SessionExpiredException()
        return "$root/database/1/com.apple.photos.cloud/production/private"
    }

    private fun params(): String {
        val dsid = state.dsid ?: throw SessionExpiredException()
        return "clientBuildNumber=${AppleAuth.CLIENT_BUILD}&clientMasteringNumber=${AppleAuth.CLIENT_MASTERING}" +
            "&clientId=${state.clientId}&dsid=$dsid&remapEnums=true&getCurrentSyncToken=true"
    }

    private fun zoneId() = JSONObject().put("zoneName", zoneName)

    private suspend fun post(path: String, body: JSONObject, retried: Boolean = false): JSONObject {
        val r = http.send("POST", "${base()}$path?${params()}", jsonBody = body, textPlain = true)
        if (r.code == 401 || r.code == 421 || r.code == 450) {
            if (!retried && reauth()) return post(path, body, retried = true)
            throw SessionExpiredException()
        }
        val j = r.json()
        if (!r.ok || j == null) {
            val reason = r.appleError() ?: j?.optString("serverErrorCode")?.takeIf { it.isNotEmpty() }
            if (reason == "AUTHENTICATION_FAILED" || reason == "ZONE_NOT_FOUND") {
                throw PhotosApiException("iCloud Photos isn't set up for this account yet. Turn on iCloud Photos on your iPhone, or open icloud.com/photos once, then try again.")
            }
            if (r.code == 503) throw HttpException(r.code, "iCloud is busy right now. Please try again in a moment.")
            throw HttpException(r.code, reason ?: "iCloud Photos request failed (error ${r.code}).")
        }
        return j
    }

    suspend fun indexingState(): String {
        val j = post("/records/query", JSONObject().put("query", JSONObject().put("recordType", "CheckIndexingState")).put("zoneID", zoneId()))
        return j.optJSONArray("records")?.optJSONObject(0)?.optJSONObject("fields")?.optJSONObject("state")
            ?.optString("value") ?: "UNKNOWN"
    }

    /** Zones in the private photos database: PrimarySync plus SharedSync-* for an iCloud Shared Library. */
    suspend fun zones(): List<String> {
        val j = post("/zones/list", JSONObject())
        val arr = j.optJSONArray("zones") ?: return listOf("PrimarySync")
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val z = arr.optJSONObject(i) ?: continue
            if (z.optBoolean("deleted", false)) continue
            val name = z.optJSONObject("zoneID")?.optString("zoneName") ?: continue
            out += name
        }
        return out
    }

    suspend fun count(spec: AlbumSpec): Int {
        val query = JSONObject()
            .put("filterBy", JSONObject()
                .put("fieldName", "indexCountID")
                .put("fieldValue", JSONObject().put("type", "STRING_LIST").put("value", JSONArray().put(spec.objType)))
                .put("comparator", "IN"))
            .put("recordType", "HyperionIndexCountLookup")
        val body = JSONObject().put(
            "batch", JSONArray().put(
                JSONObject().put("resultsLimit", 1).put("query", query).put("zoneWide", true).put("zoneID", zoneId())
            )
        )
        val j = post("/internal/records/query/batch", body)
        val rec = j.optJSONArray("batch")?.optJSONObject(0)?.optJSONArray("records")?.optJSONObject(0)
        return rec?.optJSONObject("fields")?.optJSONObject("itemCount")?.optLong("value")?.toInt() ?: 0
    }

    /** Assets of [spec] starting at iCloud rank [start] (0 = newest), roughly [want] of them. */
    suspend fun page(spec: AlbumSpec, start: Int, want: Int, keys: JSONArray = DESIRED_KEYS): List<Asset> {
        val filters = JSONArray()
            .put(JSONObject().put("fieldName", "startRank").put("comparator", "EQUALS")
                .put("fieldValue", JSONObject().put("type", "INT64").put("value", start)))
            .put(JSONObject().put("fieldName", "direction").put("comparator", "EQUALS")
                .put("fieldValue", JSONObject().put("type", "STRING").put("value", "ASCENDING")))
        spec.filters()?.let { extra -> for (i in 0 until extra.length()) filters.put(extra.get(i)) }
        // Each photo comes back as two records (asset + master); album lists add a third (the membership).
        val perAsset = if (spec.kind == AlbumKind.User) 3 else 2
        val body = JSONObject()
            .put("query", JSONObject().put("filterBy", filters).put("recordType", spec.listType))
            .put("resultsLimit", (want * perAsset).coerceAtMost(200))
            .put("desiredKeys", keys)
            .put("zoneID", zoneId())
        val j = post("/records/query", body)
        return Asset.fromRecords(j.optJSONArray("records") ?: JSONArray())
    }

    suspend fun userAlbums(): List<UserAlbum> {
        val all = ArrayList<UserAlbum>()
        var marker: String? = null
        do {
            val body = JSONObject()
                .put("query", JSONObject().put("recordType", "CPLAlbumByPositionLive"))
                .put("zoneID", zoneId())
                .put("resultsLimit", 200)
            marker?.let { body.put("continuationMarker", it) }
            val j = post("/records/query", body)
            val recs = j.optJSONArray("records") ?: JSONArray()
            for (i in 0 until recs.length()) {
                val r = recs.optJSONObject(i) ?: continue
                val name = r.optString("recordName")
                if (name == "----Root-Folder----" || name == "----Project-Root-Folder----") continue
                val f = r.optJSONObject("fields") ?: continue
                if (f.optJSONObject("isDeleted")?.optLong("value") == 1L) continue
                val type = f.optJSONObject("albumType")?.optLong("value") ?: 0L
                if (type == 3L) continue // folder
                val enc = f.optJSONObject("albumNameEnc")?.optString("value") ?: continue
                val title = runCatching {
                    String(java.util.Base64.getMimeDecoder().decode(enc), Charsets.UTF_8)
                }.getOrNull() ?: continue
                all += UserAlbum(name, title)
            }
            marker = j.optString("continuationMarker").ifEmpty { null }
        } while (marker != null && all.size < 2000)
        return all
    }

    /** Lightweight page for the on-device library index (dates, names, flags, location). */
    suspend fun indexPage(start: Int): List<Asset> = page(AlbumSpec.LIBRARY, start, 100, INDEX_KEYS)

    // ---- albums ----

    /** Create a new (empty) album; returns its record name. */
    suspend fun createAlbum(name: String): String {
        val recordName = UUID.randomUUID().toString().replace("-", "").uppercase()
        val enc = java.util.Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))
        fun typed(type: String, value: Any) = JSONObject().put("type", type).put("value", value)
        val record = JSONObject()
            .put("recordName", recordName)
            .put("recordType", "CPLAlbum")
            .put("fields", JSONObject()
                .put("albumNameEnc", typed("ENCRYPTED_BYTES", enc))
                .put("albumType", typed("INT64", 0))
                .put("isDeleted", typed("INT64", 0))
                .put("isExpunged", typed("INT64", 0))
                .put("position", typed("INT64", System.currentTimeMillis()))
                .put("sortType", typed("INT64", 1))
                .put("sortAscending", typed("INT64", 1)))
        val body = JSONObject()
            .put("operations", JSONArray().put(JSONObject().put("operationType", "create").put("record", record)))
            .put("zoneID", zoneId())
            .put("atomic", true)
        val j = post("/records/modify", body)
        val rec = j.optJSONArray("records")?.optJSONObject(0)
        rec?.optString("serverErrorCode")?.ifEmpty { null }?.let {
            throw PhotosApiException(rec.optString("reason").ifEmpty { "iCloud couldn't create the album ($it)." })
        }
        return rec?.optString("recordName")?.ifEmpty { null } ?: recordName
    }

    /** Add assets to an album (items already in it are left alone). Returns how many were added. */
    suspend fun addToAlbum(albumRecordName: String, assets: List<Asset>): Int {
        var added = 0
        for (chunk in assets.chunked(100)) {
            val ops = JSONArray()
            for (a in chunk) {
                val record = JSONObject()
                    .put("recordName", "${a.assetRecordName}-IN-$albumRecordName")
                    .put("recordType", "CPLContainerRelation")
                    .put("fields", JSONObject()
                        .put("itemId", JSONObject().put("type", "STRING").put("value", a.assetRecordName))
                        .put("position", JSONObject().put("type", "INT64").put("value", 1024))
                        .put("containerId", JSONObject().put("type", "STRING").put("value", albumRecordName)))
                ops.put(JSONObject().put("operationType", "create").put("record", record))
            }
            val j = post("/records/modify", JSONObject().put("operations", ops).put("zoneID", zoneId()).put("atomic", false))
            val recs = j.optJSONArray("records") ?: JSONArray()
            for (i in 0 until recs.length()) {
                val r = recs.optJSONObject(i) ?: continue
                val err = r.optString("serverErrorCode")
                if (err.isEmpty() || err == "CONFLICT") added++
            }
        }
        return added
    }

    /** Set or clear the caption shown under a photo (iPhone: "Add a Caption"). */
    suspend fun setCaption(asset: Asset, caption: String): Asset {
        val fields = JSONObject().put(
            "captionEnc",
            JSONObject().put("type", "ENCRYPTED_BYTES")
                .put("value", java.util.Base64.getEncoder().encodeToString(caption.trim().toByteArray(Charsets.UTF_8)))
        )
        val tag = updateAsset(asset, fields)
        return asset.copy(caption = caption.trim().ifEmpty { null }, changeTag = tag)
    }

    // ---- Shared Albums (legacy shared streams service) ----

    suspend fun sharedAlbums(): List<SharedAlbum> {
        val root = state.sharedStreamsUrl ?: return emptyList()
        val dsid = state.dsid ?: throw SessionExpiredException()
        val r = http.send("POST", "$root/$dsid/sharedstreams/webgetalbumslist?${params()}", rawBody = "{}", textPlain = true)
        if (r.code == 401 || r.code == 421) throw SessionExpiredException()
        val arr = r.json()?.optJSONArray("albums") ?: return emptyList()
        val out = ArrayList<SharedAlbum>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            out += SharedAlbum(
                name = a.optJSONObject("attributes")?.optString("name").orEmpty().ifEmpty { "Shared Album" },
                location = a.optString("albumlocation"),
                guid = a.optString("albumguid"),
                ctag = a.optString("albumctag"),
                owner = a.optString("ownerdsid"),
                sharingType = a.optString("sharingtype"),
            )
        }
        return out.filter { it.location.isNotEmpty() && it.guid.isNotEmpty() }
    }

    suspend fun sharedAlbumAssets(album: SharedAlbum): List<Asset> {
        val countRes = http.send(
            "POST", "${album.location}webgetassetcount?${params()}",
            jsonBody = JSONObject().put("albumguid", album.guid), textPlain = true,
        )
        if (countRes.code == 401 || countRes.code == 421) throw SessionExpiredException()
        val total = countRes.json()?.optInt("albumassetcount", -1) ?: -1
        val seen = LinkedHashMap<String, Asset>()
        var offset = 0
        while (true) {
            val r = http.send(
                "POST", "${album.location}webgetassets?${params()}",
                jsonBody = JSONObject()
                    .put("albumguid", album.guid)
                    .put("albumctag", album.ctag)
                    .put("limit", (offset + 200).toString())
                    .put("offset", offset.toString()),
                textPlain = true,
            )
            if (!r.ok) throw PhotosApiException(r.appleError() ?: "Couldn't load ${album.name} (error ${r.code}).")
            val page = Asset.fromRecords(r.json()?.optJSONArray("records") ?: JSONArray())
            var fresh = 0
            for (a in page) if (seen.put(a.id, a) == null) fresh++
            if (page.isEmpty() || fresh == 0) break
            offset += page.size
            if (total in 0..offset) break
            if (seen.size > 20000) break
        }
        return seen.values.sortedBy { it.date }
    }

    // ---- edits ----

    private suspend fun lookupTag(recordName: String): String? {
        val body = JSONObject()
            .put("records", JSONArray().put(JSONObject().put("recordName", recordName)))
            .put("zoneID", zoneId())
        val j = post("/records/lookup", body)
        return j.optJSONArray("records")?.optJSONObject(0)?.optString("recordChangeTag")?.ifEmpty { null }
    }

    /** Update fields on an asset record; returns the new change tag. Retries once on a stale tag. */
    private suspend fun updateAsset(asset: Asset, fields: JSONObject, tag: String = asset.changeTag, retried: Boolean = false): String {
        val record = JSONObject()
            .put("recordName", asset.assetRecordName)
            .put("recordType", asset.assetRecordType)
            .put("recordChangeTag", tag)
            .put("fields", fields)
        val body = JSONObject()
            .put("operations", JSONArray().put(JSONObject().put("operationType", "update").put("record", record)))
            .put("zoneID", zoneId())
            .put("atomic", true)
        val j = post("/records/modify", body)
        val rec = j.optJSONArray("records")?.optJSONObject(0)
        val err = rec?.optString("serverErrorCode")?.ifEmpty { null }
        if (err != null) {
            if (!retried && (err == "CONFLICT" || err == "OPLOCK" || err.contains("CHANGE_TAG"))) {
                val fresh = lookupTag(asset.assetRecordName) ?: throw PhotosApiException("This item changed on another device. Pull to refresh and try again.")
                return updateAsset(asset, fields, fresh, retried = true)
            }
            throw PhotosApiException(rec?.optString("reason")?.ifEmpty { null } ?: "iCloud refused the change ($err).")
        }
        return rec?.optString("recordChangeTag")?.ifEmpty { null } ?: tag
    }

    private fun flag(name: String, on: Boolean) = JSONObject().put(name, JSONObject().put("value", if (on) 1 else 0))

    suspend fun setFavorite(asset: Asset, favorite: Boolean): Asset =
        asset.copy(isFavorite = favorite, changeTag = updateAsset(asset, flag("isFavorite", favorite)))

    suspend fun setHidden(asset: Asset, hidden: Boolean): Asset =
        asset.copy(isHidden = hidden, changeTag = updateAsset(asset, flag("isHidden", hidden)))

    /** Moves to Recently Deleted (or back out of it). */
    suspend fun setDeleted(asset: Asset, deleted: Boolean): Asset =
        asset.copy(changeTag = updateAsset(asset, flag("isDeleted", deleted)))

    // ---- upload (Apple's 2026 three-step flow) ----

    suspend fun upload(file: File, fileName: String, lastModified: Long, zone: String = zoneName) {
        val uploadRoot = state.photosUploadUrl
            ?: throw PhotosApiException("Uploading isn't available for this account on the web.")
        val clientUuid = UUID.randomUUID().toString()
        val p = params()

        // 1. reserve an upload slot sized for the file
        val reserve = http.send(
            "POST", "$uploadRoot/photosupload/createUploadUrl?$p",
            jsonBody = JSONObject().put("zoneName", zone).put("assets", JSONObject().put(clientUuid, file.length())),
        )
        if (reserve.code == 401 || reserve.code == 421) throw SessionExpiredException()
        val target = reserve.json()?.optJSONObject("uploadUrls")?.optString(clientUuid)?.ifEmpty { null }
            ?: throw PhotosApiException(reserve.appleError() ?: "iCloud didn't accept the upload (error ${reserve.code}).")
        if (!target.startsWith("https://")) throw PhotosApiException("Refusing to upload over an insecure connection.")

        // 2. send the bytes
        val receipt = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(target)
                .post(file.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string() ?: ""
                if (!res.isSuccessful) throw PhotosApiException("Upload of $fileName failed (error ${res.code}).")
                JSONObject(text).optJSONObject("singleFile")
                    ?: throw PhotosApiException("Unexpected reply while uploading $fileName.")
            }
        }

        // 3. register it as a photo
        val tz = TimeZone.getDefault()
        val offsetMinutes = -tz.getOffset(System.currentTimeMillis()) / 60000
        val entry = JSONObject()
            .put("fileName", fileName)
            .put("lastModDate", lastModified)
            .put("timeZoneOffset", offsetMinutes)
            .put("singleFileUploadRequest", receipt)
        val put = http.send(
            "POST", "$uploadRoot/photosupload/putAsset?$p",
            jsonBody = JSONObject()
                .put("zoneName", zone)
                .put("files", JSONArray().put(entry))
                .put("localTimeZoneId", tz.id)
                .put("importGroup", clientUuid),
        )
        if (!put.ok) throw PhotosApiException(put.appleError() ?: "iCloud couldn't add $fileName (error ${put.code}).")
        val result = put.jsonArray()?.optJSONObject(0)
        val status = result?.optJSONObject("response")?.optInt("status", 200) ?: 200
        if (status >= 400 && status != 409) {
            throw PhotosApiException(result?.optJSONObject("response")?.optString("errorMessage")?.ifEmpty { null }
                ?: "iCloud couldn't add $fileName (status $status).")
        }
    }

    companion object {
        val INDEX_KEYS = JSONArray(
            listOf(
                "recordName", "recordType", "recordChangeTag", "masterRef", "assetDate", "addedDate", "isFavorite",
                "isHidden", "itemType", "filenameEnc", "resOriginalRes", "resOriginalFileType", "resOriginalWidth",
                "resOriginalHeight", "adjustmentType", "locationEnc", "duration", "originalCreationDate",
            )
        )
        val DESIRED_KEYS = JSONArray(
            listOf(
                "resJPEGFullWidth", "resJPEGFullHeight", "resJPEGFullFileType", "resJPEGFullFingerprint", "resJPEGFullRes",
                "resJPEGMedWidth", "resJPEGMedHeight", "resJPEGMedFileType", "resJPEGMedFingerprint", "resJPEGMedRes",
                "resJPEGThumbWidth", "resJPEGThumbHeight", "resJPEGThumbFileType", "resJPEGThumbFingerprint", "resJPEGThumbRes",
                "resVidFullWidth", "resVidFullHeight", "resVidFullFileType", "resVidFullFingerprint", "resVidFullRes",
                "resVidMedWidth", "resVidMedHeight", "resVidMedFileType", "resVidMedFingerprint", "resVidMedRes",
                "resVidSmallWidth", "resVidSmallHeight", "resVidSmallFileType", "resVidSmallFingerprint", "resVidSmallRes",
                "itemType", "dataClassType", "filenameEnc", "originalOrientation",
                "resOriginalWidth", "resOriginalHeight", "resOriginalFileType", "resOriginalFingerprint", "resOriginalRes",
                "resOriginalVidComplWidth", "resOriginalVidComplHeight", "resOriginalVidComplFileType",
                "resOriginalVidComplFingerprint", "resOriginalVidComplRes",
                "isDeleted", "isExpunged", "dateExpunged", "remappedRef", "recordName", "recordType", "recordChangeTag",
                "masterRef", "adjustmentRenderType", "assetDate", "addedDate", "isFavorite", "isHidden", "orientation",
                "duration", "assetSubtype", "assetSubtypeV2", "assetHDRType", "burstFlags", "burstFlagsExt", "burstId",
                "captionEnc", "locationEnc", "locationV2Enc", "adjustmentType", "timeZoneOffset",
                "vidComplDurValue", "vidComplDurScale", "vidComplDispValue", "vidComplDispScale",
                "vidComplVisibilityState", "customRenderedValue", "containerId", "itemId", "position", "isKeyAsset",
            )
        )
    }
}
