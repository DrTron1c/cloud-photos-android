package io.github.drtron1c.cloudphotos.work

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.drtron1c.cloudphotos.CloudPhotosApp
import io.github.drtron1c.cloudphotos.net.SecureFile
import io.github.drtron1c.cloudphotos.photos.AlbumKind
import io.github.drtron1c.cloudphotos.photos.AlbumSpec
import io.github.drtron1c.cloudphotos.photos.Asset
import io.github.drtron1c.cloudphotos.photos.SharedAlbum
import io.github.drtron1c.cloudphotos.photos.Version
import io.github.drtron1c.cloudphotos.settings.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Albums kept on the device for viewing without internet. Files are named by content checksum. */
class OfflineStore(context: Context) {
    val dir: File = File(context.filesDir, "offline").apply { mkdirs() }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

    fun nameFor(v: Version): String = safe(v.checksum) + "." + Asset.extensionFor(v.fileType, "bin").lowercase()

    /** The local copy of a rendition, if it has been downloaded. */
    fun fileFor(v: Version?): File? {
        if (v == null) return null
        val f = File(dir, nameFor(v))
        return if (f.isFile && f.length() > 0) f else null
    }

    private fun manifestFile(key: String) = SecureFile(File(dir, "m-" + safe(key) + "-" + Integer.toHexString(key.hashCode()) + ".bin"))

    fun albums(prefs: SharedPreferences): List<AlbumSpec> = runCatching {
        val arr = JSONArray(prefs.getString(Keys.OFFLINE_ALBUMS, "[]"))
        List(arr.length()) { AlbumSpec.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun setAlbums(prefs: SharedPreferences, specs: List<AlbumSpec>) {
        val arr = JSONArray()
        specs.distinctBy { it.key }.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(Keys.OFFLINE_ALBUMS, arr.toString()).apply()
    }

    fun manifest(key: String): List<Asset>? {
        val text = manifestFile(key).read() ?: return null
        return runCatching {
            val arr = JSONObject(text).getJSONArray("assets")
            List(arr.length()) { Asset.fromJson(arr.getJSONObject(it)) }
        }.getOrNull()
    }

    fun writeManifest(key: String, assets: List<Asset>) {
        val arr = JSONArray()
        assets.forEach { arr.put(it.toJson()) }
        manifestFile(key).write(JSONObject().put("assets", arr).toString())
    }

    fun hasAnyContent(prefs: SharedPreferences) = albums(prefs).any { manifest(it.key)?.isNotEmpty() == true }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Delete downloaded files not in [keep] and manifests of albums no longer offline. */
    fun cleanup(prefs: SharedPreferences, keep: Set<String>) {
        val manifests = albums(prefs).map { manifestFile(it.key) }
        dir.listFiles()?.forEach { f ->
            val isManifest = f.name.startsWith("m-") && f.name.endsWith(".bin")
            if (isManifest) {
                val stillUsed = albums(prefs).any { f.name.startsWith("m-" + safe(it.key) + "-" + Integer.toHexString(it.key.hashCode())) }
                if (!stillUsed) f.delete()
            } else if (f.name !in keep && !f.name.endsWith(".tmp")) {
                f.delete()
            }
        }
        if (manifests.isEmpty()) dir.listFiles()?.forEach { it.delete() }
    }

    fun clearAll(prefs: SharedPreferences) {
        setAlbums(prefs, emptyList())
        dir.listFiles()?.forEach { it.delete() }
    }

    suspend fun download(http: OkHttpClient, v: Version) = withContext(Dispatchers.IO) {
        if (fileFor(v) != null) return@withContext
        val url = v.url.replace("\${f}", URLEncoder.encode(nameFor(v), "UTF-8"))
        val tmp = File(dir, nameFor(v) + ".tmp")
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Download failed (${res.code})")
            val body = res.body ?: throw IOException("Empty download")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out, 256 * 1024) }
        }
        if (!tmp.renameTo(File(dir, nameFor(v)))) tmp.delete()
    }
}

/** Downloads every item of the albums marked "Keep offline" and refreshes their lists. */
class OfflineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as CloudPhotosApp).graph
        val prefs = graph.prefs
        val store = graph.offline
        val albums = store.albums(prefs)
        if (albums.isEmpty()) {
            store.cleanup(prefs, emptySet())
            return Result.success()
        }
        fun status(s: String) = prefs.edit().putString(Keys.OFFLINE_STATUS, s).apply()

        val signedIn = runCatching { graph.auth.isSignedIn && graph.auth.resume() }.getOrDefault(false)
        if (!signedIn) {
            status("Waiting for you to sign in again")
            return Result.success()
        }
        val started = System.currentTimeMillis()
        val keep = HashSet<String>()
        val withVideos = prefs.getBoolean(Keys.OFFLINE_VIDEOS, true)
        var total = 0
        for (spec in albums) {
            val assets = try {
                loadAll(graph, spec)
            } catch (e: Exception) {
                // Keep what we have for this album rather than deleting it.
                store.manifest(spec.key)?.forEach { a -> listOfNotNull(a.thumbnail, a.displayImage, a.offlineVideo).forEach { keep += store.nameFor(it) } }
                continue
            }
            assets.forEachIndexed { i, a ->
                val wanted = buildList {
                    a.thumbnail?.let { add(it) }
                    if (a.isVideo) {
                        if (withVideos) a.offlineVideo?.let { add(it) }
                    } else {
                        a.displayImage?.let { add(it) }
                    }
                }
                for (v in wanted) {
                    keep += store.nameFor(v)
                    try {
                        store.download(graph.http, v)
                    } catch (_: IOException) {
                    }
                }
                if (i % 10 == 0) status("Downloading ${spec.title}: ${i + 1} of ${assets.size}")
                if (System.currentTimeMillis() - started > 8 * 60 * 1000L) {
                    store.writeManifest(spec.key, assets)
                    status("Downloading ${spec.title}: ${i + 1} of ${assets.size} (continuing soon)")
                    return Result.retry()
                }
            }
            store.writeManifest(spec.key, assets)
            total += assets.size
        }
        store.cleanup(prefs, keep)
        status("Up to date · $total items")
        return Result.success()
    }

    private suspend fun loadAll(graph: io.github.drtron1c.cloudphotos.AppGraph, spec: AlbumSpec): List<Asset> {
        if (spec.kind == AlbumKind.Shared) {
            val album = SharedAlbum.fromJson(JSONObject(spec.filterJson ?: "{}"))
            return graph.photos.sharedAlbumAssets(album)
        }
        val count = graph.photos.count(spec)
        val out = ArrayList<Asset>(count)
        var start = 0
        while (start < count) {
            val page = graph.photos.page(spec, start, 100)
            if (page.isEmpty()) break
            out.addAll(page)
            start += page.size
        }
        return out.reversed() // oldest first, like the grid
    }

    companion object {
        private const val NOW = "offline-sync"
        private const val DAILY = "offline-daily"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<OfflineWorker>().setConstraints(constraints()).build(),
            )
        }

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(DAILY); return
            }
            wm.enqueueUniquePeriodicWork(
                DAILY, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<OfflineWorker>(24, TimeUnit.HOURS).setConstraints(constraints()).build(),
            )
        }
    }
}
