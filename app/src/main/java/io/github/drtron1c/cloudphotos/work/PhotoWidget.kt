package io.github.drtron1c.cloudphotos.work

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
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
import io.github.drtron1c.cloudphotos.R
import io.github.drtron1c.cloudphotos.photos.AlbumSpec
import io.github.drtron1c.cloudphotos.photos.LibraryIndex
import io.github.drtron1c.cloudphotos.settings.Keys
import io.github.drtron1c.cloudphotos.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Home-screen widget showing a photo from the library ("On this day", favourites or random). */
class PhotoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetWorker.refreshNow(context)
    }

    override fun onEnabled(context: Context) {
        WidgetWorker.schedule(context, true)
    }

    override fun onDisabled(context: Context) {
        WidgetWorker.schedule(context, false)
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val manager = AppWidgetManager.getInstance(ctx)
        val ids = manager.getAppWidgetIds(ComponentName(ctx, PhotoWidgetProvider::class.java))
        if (ids.isEmpty()) return Result.success()
        val graph = (ctx as CloudPhotosApp).graph
        val prefs = graph.prefs

        val signedIn = runCatching { graph.auth.isSignedIn && graph.auth.resume() }.getOrDefault(false)
        if (!signedIn) {
            render(ctx, manager, ids, null, "Open Cloud Photos to sign in")
            return Result.success()
        }

        val zone = prefs.getString(Keys.ZONE, "PrimarySync") ?: "PrimarySync"
        val mode = prefs.getString(Keys.WIDGET_MODE, "onthisday") ?: "onthisday"
        val entries = withContext(Dispatchers.IO) { LibraryIndex.readCached(graph.indexDir, zone) }
            ?.withIndex()?.filter { !it.value.video }
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)

        var rank: Int
        var caption: String
        if (!entries.isNullOrEmpty()) {
            val pool = when (mode) {
                "favourites" -> entries.filter { it.value.favourite }
                "onthisday" -> entries.filter {
                    val d = Instant.ofEpochMilli(it.value.date).atZone(zoneId).toLocalDate()
                    d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year
                }
                else -> entries
            }.ifEmpty { entries }
            val pick = pool[Random.nextInt(pool.size)]
            rank = pick.index
            val d = Instant.ofEpochMilli(pick.value.date).atZone(zoneId).toLocalDate()
            caption = if (mode == "onthisday" && d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year) {
                val years = today.year - d.year
                "On this day · " + if (years == 1) "1 year ago" else "$years years ago"
            } else {
                d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
            }
        } else {
            val count = runCatching { graph.photos.count(AlbumSpec.LIBRARY) }.getOrDefault(0)
            if (count <= 0) return Result.success()
            rank = Random.nextInt(count)
            caption = ""
        }

        val asset = runCatching { graph.photos.page(AlbumSpec.LIBRARY, rank, 1).firstOrNull() }.getOrNull()
            ?: return Result.retry()
        if (caption.isEmpty()) {
            caption = Instant.ofEpochMilli(asset.date).atZone(zoneId).toLocalDate()
                .format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
        }
        val version = asset.medium ?: asset.thumbnail ?: return Result.success()
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val url = version.url.replace("\${f}", URLEncoder.encode(asset.filename, "UTF-8"))
                graph.http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                    val bytes = res.body?.bytes() ?: return@use null
                    decodeScaled(bytes, 720)
                }
            }.getOrNull()
        } ?: return Result.retry()
        render(ctx, manager, ids, bitmap, caption)
        return Result.success()
    }

    private fun decodeScaled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun render(ctx: Context, manager: AppWidgetManager, ids: IntArray, bitmap: Bitmap?, caption: String) {
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (id in ids) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_photo)
            if (bitmap != null) views.setImageViewBitmap(R.id.widget_image, bitmap)
            views.setTextViewText(R.id.widget_caption, caption)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        private const val PERIODIC = "widget-refresh"
        private const val NOW = "widget-now"

        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun refreshNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetWorker>().setConstraints(constraints()).build(),
            )
        }

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(PERIODIC); return
            }
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetWorker>(3, TimeUnit.HOURS).setConstraints(constraints()).build(),
            )
            refreshNow(context)
        }
    }
}
