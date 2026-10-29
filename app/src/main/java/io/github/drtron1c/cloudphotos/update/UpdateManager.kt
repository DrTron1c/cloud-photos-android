package io.github.drtron1c.cloudphotos.update

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.github.drtron1c.cloudphotos.BuildConfig
import io.github.drtron1c.cloudphotos.CloudPhotosApp
import io.github.drtron1c.cloudphotos.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAtMs: Long) : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val info: ReleaseInfo, val bytes: Long) : UpdateState
    /** The APK was handed to Android's installer; you confirm there. */
    data class Installing(val info: ReleaseInfo) : UpdateState
    /** "Install unknown apps" is off for Cloud Photos; you've been taken to the setting. */
    data class NeedsPermission(val info: ReleaseInfo) : UpdateState
    /** [info] is set when a newer build is known, so you can simply try again. */
    data class Failed(val message: String, val info: ReleaseInfo?) : UpdateState
}

/**
 * Looks at the project's rolling GitHub release ("latest") for a build newer than the installed
 * one, downloads its APK and hands it to Android's installer. Android only accepts it if it is
 * signed with the same key as the installed app, and you confirm every install. Only GitHub is
 * contacted.
 */
class UpdateManager(
    private val app: Application,
    private val http: OkHttpClient,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val installedVersionCode: Int = BuildConfig.VERSION_CODE,
    private val repository: String = "DrTron1c/cloud-photos-android",
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The banner can be dismissed; it comes back with the next launch or a newer build. */
    private val _bannerDismissed = MutableStateFlow(false)
    val bannerDismissed: StateFlow<Boolean> = _bannerDismissed.asStateFlow()

    private var lastCheckStartedAt = 0L
    private var work: Job? = null

    fun checkOnLaunch() {
        if (settings.checkUpdates) scope.launch { check() }
    }

    /** Back in the app: at most every half hour. */
    fun checkOnForeground() {
        val since = SystemClock.elapsedRealtime() - lastCheckStartedAt
        if (lastCheckStartedAt != 0L && since < FOREGROUND_INTERVAL_MS) return
        checkOnLaunch()
    }

    fun checkNow() {
        scope.launch { check() }
    }

    private suspend fun check() {
        if (busy()) return
        lastCheckStartedAt = SystemClock.elapsedRealtime()
        _state.value = UpdateState.Checking
        _state.value = try {
            val info = fetchLatest()
            if (ReleaseParser.isNewer(info.versionCode, installedVersionCode)) {
                _bannerDismissed.value = false
                UpdateState.Available(info)
            } else {
                UpdateState.UpToDate(System.currentTimeMillis())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateState.Failed(describe(e), null)
        }
    }

    private fun busy(): Boolean = when (_state.value) {
        is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> true
        else -> false
    }

    private suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases/tags/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateException(
                    when (response.code) {
                        404 -> "GitHub didn't show a release yet."
                        403 -> "GitHub refused the request (rate limit). Try again later."
                        else -> "GitHub returned HTTP ${response.code}"
                    }
                )
            }
            ReleaseParser.parse(response.body?.string().orEmpty())
                ?: throw UpdateException("The latest release has no installable ${ReleaseParser.APK_NAME} yet.")
        }
    }

    fun dismissBanner() {
        _bannerDismissed.value = true
    }

    /** "Download & install" (also "Try again"). */
    fun downloadAndInstall() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.NeedsPermission -> s.info
            is UpdateState.Failed -> s.info
            else -> null
        } ?: return
        if (work?.isActive == true) return
        _bannerDismissed.value = false
        work = scope.launch {
            if (!app.packageManager.canRequestPackageInstalls()) {
                _state.value = UpdateState.NeedsPermission(info)
                openInstallPermissionSettings()
                return@launch
            }
            _state.value = UpdateState.Downloading(info, 0L)
            try {
                val file = download(info)
                _state.value = UpdateState.Installing(info)
                withContext(Dispatchers.IO) { install(file) }
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(info)
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(describe(e), info)
            }
        }
    }

    fun cancelDownload() {
        work?.cancel()
    }

    /** Back from the "Install unknown apps" screen: carry on if it was switched on. */
    fun onAppForeground() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && app.packageManager.canRequestPackageInstalls()) downloadAndInstall()
    }

    /** True while the app is sending you to Android's own screens for an update. */
    fun isHandingOff(): Boolean = _state.value is UpdateState.Installing || _state.value is UpdateState.NeedsPermission

    private fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    private suspend fun download(info: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "CloudPhotos-${info.versionCode}.apk")
        dir.listFiles()?.forEach { if (it.name != target.name) it.delete() }
        if (target.exists() && info.sizeBytes > 0 && target.length() == info.sizeBytes) return@withContext target

        val part = File(dir, target.name + ".part")
        val request = Request.Builder()
            .url(info.apkUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", USER_AGENT)
            .build()
        val client = http.newBuilder().cache(null).readTimeout(60, TimeUnit.SECONDS).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UpdateException("Download failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Empty download")
            var done = 0L
            var lastReported = 0L
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done - lastReported >= REPORT_EVERY_BYTES) {
                            lastReported = done
                            _state.value = UpdateState.Downloading(info, done)
                        }
                    }
                }
            }
            if (info.sizeBytes > 0 && done != info.sizeBytes) {
                part.delete()
                throw UpdateException("The download was cut short. Check your connection and try again.")
            }
        }
        if (!part.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }) {
            part.delete()
            throw UpdateException("The downloaded file isn't an APK.")
        }
        if (!part.renameTo(target)) throw IOException("Couldn't save the update")
        target
    }

    private fun install(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("CloudPhotos.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val result = Intent(app, UpdateInstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(app, id, result, flags).intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val info = (_state.value as? UpdateState.Installing)?.info
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Failed("The install was cancelled.", info)
            else -> UpdateState.Failed("Android couldn't install the update" + (message?.let { ": $it" } ?: "."), info)
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is UpdateException -> e.message.orEmpty()
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "No connection"
        is SecurityException -> "Android didn't allow the install. Turn on “Install unknown apps” for Cloud Photos and try again."
        else -> e.message ?: "Unexpected error"
    }

    private class UpdateException(message: String) : IOException(message)

    companion object {
        private const val USER_AGENT = "CloudPhotos (unofficial; Android)"
        private const val FOREGROUND_INTERVAL_MS = 30 * 60_000L
        private const val REPORT_EVERY_BYTES = 256 * 1024L
    }
}

/** Android's answer to an update install; opens its confirmation screen when it asks for one. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirm) }
            return
        }
        val app = context.applicationContext as? CloudPhotosApp ?: return
        app.graph.updates.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
