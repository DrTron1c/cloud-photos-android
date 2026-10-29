package io.github.drtron1c.cloudphotos.memories

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.drtron1c.cloudphotos.CloudPhotosApp
import io.github.drtron1c.cloudphotos.R
import io.github.drtron1c.cloudphotos.photos.LibraryIndex
import io.github.drtron1c.cloudphotos.settings.Keys
import io.github.drtron1c.cloudphotos.ui.MainActivity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The daily "On This Day" notification. It only reads the library index already on the device
 * (no network); the notification shows no photo, and on the lock screen it doesn't even say
 * what it's about.
 */
class OnThisDayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as CloudPhotosApp).graph
        val settings = graph.settings
        if (!settings.memoriesNotify) return Result.success()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val prefs = settings.raw()
        if (prefs.getString(Keys.MEMORIES_LAST_NOTIFIED, "") == today.toString()) return Result.success()
        val entries = LibraryIndex.readCached(graph.indexDir, settings.zone) ?: return Result.success()
        val years = entries.asSequence()
            .filter { !it.video && it.date > 0 }
            .map { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }
            .filter { it.monthValue == today.monthValue && it.dayOfMonth == today.dayOfMonth && it.year < today.year }
            .groupingBy { it.year }.eachCount()
            .filter { it.value >= 3 }
        if (years.isEmpty()) return Result.success()
        val total = years.values.sum()
        val oldest = today.year - years.keys.min()
        val text = if (years.size == 1) {
            val y = years.keys.first()
            val ago = today.year - y
            "$total photos from ${if (ago == 1) "a year ago" else "$ago years ago"} today"
        } else "$total photos from this day over the last $oldest years"
        notify(applicationContext, "On This Day", text, settings.appName)
        prefs.edit().putString(Keys.MEMORIES_LAST_NOTIFIED, today.toString()).apply()
        return Result.success()
    }

    companion object {
        private const val NAME = "on-this-day"
        private const val CHANNEL = "memories"

        /** Once a day at about 9 in the morning. */
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) {
                wm.cancelUniqueWork(NAME); return
            }
            val now = LocalDateTime.now()
            var next = LocalDateTime.of(now.toLocalDate(), LocalTime.of(9, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMinutes()
            wm.enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<OnThisDayWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(delay, TimeUnit.MINUTES)
                    .build(),
            )
        }

        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun notify(context: Context, title: String, text: String, appName: String) {
            if (!canNotify(context)) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Memories", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A daily On This Day reminder"
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                }
            )
            val open = PendingIntent.getActivity(
                context, 7,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "onthisday")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val public = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(appName)
                .setContentText("New memory")
                .build()
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(4242, n) }
        }
    }
}
