package io.github.drtron1c.cloudphotos.work

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
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
import io.github.drtron1c.cloudphotos.net.SessionExpiredException
import io.github.drtron1c.cloudphotos.settings.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Uploads photos and videos taken / saved on this device to iCloud Photos, like iCloud Photos
 * sync on an iPhone. Runs in the background every ~15 minutes when enabled.
 * Skips anything saved *from* iCloud by this app (Pictures/Movies › Cloud Photos).
 */
class AutoUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as CloudPhotosApp).graph
        val prefs = graph.prefs
        if (!prefs.getBoolean(Keys.AUTO_UPLOAD, false)) return Result.success()
        fun status(s: String) = prefs.edit().putString(Keys.AUTO_UPLOAD_STATUS, s).apply()

        if (!hasMediaAccess(applicationContext)) {
            status("Needs permission to see your photos — open Settings › Auto-upload")
            return Result.success()
        }
        val signedIn = runCatching { graph.auth.isSignedIn && graph.auth.resume() }.getOrDefault(false)
        if (!signedIn) {
            status("Paused — sign in to Cloud Photos again to resume")
            return Result.success()
        }

        val since = prefs.getLong(Keys.AUTO_UPLOAD_SINCE, System.currentTimeMillis() / 1000)
        val screenshots = prefs.getBoolean(Keys.AUTO_UPLOAD_SCREENSHOTS, true)
        val pending = withContext(Dispatchers.IO) { findNew(applicationContext, since, screenshots) }
        if (pending.isEmpty()) {
            if (!prefs.contains(Keys.AUTO_UPLOAD_STATUS)) status("Up to date")
            return Result.success()
        }
        val started = System.currentTimeMillis()
        var uploaded = 0
        var failed = 0
        for (item in pending) {
            try {
                val (file, name, modified) = graph.media.stageForUpload(item.uri)
                try {
                    graph.photos.upload(file, name, modified, zone = "PrimarySync")
                    uploaded++
                } finally {
                    file.delete()
                }
                prefs.edit().putLong(Keys.AUTO_UPLOAD_SINCE, item.dateAdded).apply()
            } catch (e: SessionExpiredException) {
                status("Paused — sign in to Cloud Photos again to resume")
                return Result.success()
            } catch (e: Exception) {
                failed++
                if (failed >= 3) break
            }
            if (System.currentTimeMillis() - started > 8 * 60 * 1000L) break
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        status(
            when {
                uploaded > 0 && failed == 0 -> "Uploaded $uploaded at $time"
                uploaded > 0 -> "Uploaded $uploaded, $failed failed at $time — will retry"
                else -> "Upload failed at $time — will retry"
            }
        )
        return if (failed > 0 && uploaded == 0) Result.retry() else Result.success()
    }

    private data class Pending(val uri: android.net.Uri, val dateAdded: Long)

    private fun findNew(context: Context, since: Long, screenshots: Boolean): List<Pending> {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
        )
        val sel = StringBuilder()
            .append("(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)")
            .append(" AND ${MediaStore.Files.FileColumns.DATE_ADDED}>?")
            .append(" AND ${MediaStore.MediaColumns.IS_PENDING}=0")
            .append(" AND (${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL OR (")
            .append("${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?))")
        val args = arrayListOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            since.toString(),
            "Pictures/Cloud Photos%",
            "Movies/Cloud Photos%",
        )
        if (!screenshots) {
            sel.append(" AND (${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL OR ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?)")
            args += "%Screenshots%"
        }
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel.toString())
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Files.FileColumns.DATE_ADDED} ASC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 60)
        }
        val out = ArrayList<Pending>()
        context.contentResolver.query(uri, projection, query, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val base = if (c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += Pending(ContentUris.withAppendedId(base, id), c.getLong(addedCol))
            }
        }
        return out
    }

    companion object {
        private const val PERIODIC = "auto-upload"
        private const val NOW = "auto-upload-now"

        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun hasMediaAccess(context: Context): Boolean =
            permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** (Re)schedule according to the current settings. */
        fun schedule(context: Context, enabled: Boolean, wifiOnly: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(NOW)
                return
            }
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AutoUploadWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(wifiOnly)).build(),
            )
            wm.enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutoUploadWorker>().setConstraints(constraints(wifiOnly)).build(),
            )
        }
    }
}
