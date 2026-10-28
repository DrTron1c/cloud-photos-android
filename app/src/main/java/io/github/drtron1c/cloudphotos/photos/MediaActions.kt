package io.github.drtron1c.cloudphotos.photos

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder

/** Downloading originals to the gallery and preparing files for the share sheet. */
class MediaActions(private val context: Context, private val http: OkHttpClient) {

    private fun urlFor(v: Version, filename: String): String =
        v.url.replace("\${f}", URLEncoder.encode(filename, "UTF-8").replace("+", "%20"))

    private suspend fun <T> open(v: Version, filename: String, block: (InputStream, Long) -> T): T =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(urlFor(v, filename)).build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("Download failed (error ${res.code})")
                val body = res.body ?: throw IOException("Empty download")
                body.byteStream().use { block(it, body.contentLength()) }
            }
        }

    private fun copy(input: InputStream, output: OutputStream, total: Long, onProgress: (Float) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
        }
    }

    /** Save the current version (edit if present, else original) into Pictures/Movies › Cloud Photos. */
    suspend fun saveToGallery(asset: Asset, onProgress: (Float) -> Unit = {}): Uri {
        val v = asset.current ?: throw IOException("Nothing to download for this item")
        val name = asset.currentFilename
        val mime = Asset.mimeFor(name)
        val isVideo = mime.startsWith("video/")
        val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Cloud Photos"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (asset.date > 0) put(MediaStore.MediaColumns.DATE_TAKEN, asset.date)
        }
        val resolver = context.contentResolver
        val uri = withContext(Dispatchers.IO) { resolver.insert(collection, values) }
            ?: throw IOException("Couldn't create a file in your gallery")
        try {
            open(v, name) { input, total ->
                resolver.openOutputStream(uri)?.use { out -> copy(input, out, total, onProgress) }
                    ?: throw IOException("Couldn't write to your gallery")
            }
            withContext(Dispatchers.IO) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.IO) { runCatching { resolver.delete(uri, null, null) } }
            throw e
        }
        return uri
    }

    /** Download items into the app cache and build a share intent for them. */
    suspend fun shareIntent(assets: List<Asset>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Intent {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
        val uris = ArrayList<Uri>()
        val mimes = HashSet<String>()
        assets.forEachIndexed { i, asset ->
            onProgress(i, assets.size)
            val v = asset.current ?: return@forEachIndexed
            var name = asset.currentFilename.replace('/', '_')
            var n = 1
            while (File(dir, name).exists()) {
                name = asset.currentFilename.substringBeforeLast('.') + "_" + (n++) + "." + asset.currentFilename.substringAfterLast('.', "jpg")
            }
            val file = File(dir, name)
            open(v, name) { input, total -> file.outputStream().use { copy(input, it, total) {} } }
            uris += FileProvider.getUriForFile(context, context.packageName + ".files", file)
            mimes += Asset.mimeFor(name)
        }
        if (uris.isEmpty()) throw IOException("Nothing to share")
        val mime = when {
            mimes.size == 1 -> mimes.first()
            mimes.all { it.startsWith("image/") } -> "image/*"
            mimes.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** Copy a picked file into the cache so its size is known up front (Apple reserves by size). */
    suspend fun stageForUpload(uri: Uri): Triple<File, String, Long> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "upload"
        var modified = System.currentTimeMillis()
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (ni >= 0) c.getString(ni)?.let { name = it }
                val ti = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                val mi = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                when {
                    ti >= 0 && !c.isNull(ti) && c.getLong(ti) > 0 -> modified = c.getLong(ti)
                    mi >= 0 && !c.isNull(mi) && c.getLong(mi) > 0 -> modified = c.getLong(mi) * 1000
                }
            }
        }
        if (!name.contains('.')) {
            val type = resolver.getType(uri) ?: ""
            name += when {
                type.contains("heic") -> ".HEIC"
                type.contains("png") -> ".PNG"
                type.contains("mp4") -> ".MP4"
                type.startsWith("video") -> ".MOV"
                else -> ".JPG"
            }
        }
        val dir = File(context.cacheDir, "upload").apply { mkdirs() }
        val file = File(dir, System.nanoTime().toString() + "_" + name.replace('/', '_'))
        resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it, 256 * 1024) } }
            ?: throw IOException("Couldn't read $name")
        Triple(file, name, modified)
    }
}
