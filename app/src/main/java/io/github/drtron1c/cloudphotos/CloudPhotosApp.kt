package io.github.drtron1c.cloudphotos

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import io.github.drtron1c.cloudphotos.ai.SmartSearch
import io.github.drtron1c.cloudphotos.auth.AppleAuth
import io.github.drtron1c.cloudphotos.net.AuthState
import io.github.drtron1c.cloudphotos.net.PersistentCookieJar
import io.github.drtron1c.cloudphotos.net.SecureFile
import io.github.drtron1c.cloudphotos.net.buildHttpClient
import io.github.drtron1c.cloudphotos.photos.MediaActions
import io.github.drtron1c.cloudphotos.photos.PhotosApi
import io.github.drtron1c.cloudphotos.settings.AppSettings
import io.github.drtron1c.cloudphotos.work.OfflineStore
import io.github.drtron1c.cloudphotos.update.UpdateManager
import io.github.drtron1c.cloudphotos.vpn.VpnManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class CloudPhotosApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Never leave shared / uploaded / edited copies lying around between sessions.
        Thread {
            for (name in listOf("shared", "upload", "edit")) File(cacheDir, name).deleteRecursively()
        }.start()
    }
}

/** Long-lived objects shared by the whole app (UI, widget and background jobs). */
class AppGraph(context: Context) {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val settings = AppSettings(prefs)
    val authState = AuthState(SecureFile(File(context.filesDir, "session.bin")))
    val cookieJar = PersistentCookieJar(SecureFile(File(context.filesDir, "cookies.bin")))
    /** App-wide coroutines (updates, VPN). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var vpnGate: () -> Boolean = { true }
    val http = buildHttpClient(cookieJar, authState, prefs) { vpnGate() }
    val auth = AppleAuth(http, authState, cookieJar)

    /** Set by the UI layer: how to recover when CloudKit says the session is gone. */
    @Volatile var reauthHandler: suspend () -> Boolean = { false }
    val photos = PhotosApi(http, authState) { reauthHandler() }
    val media = MediaActions(context, http)
    val offline = OfflineStore(context)
    val indexDir: File = File(context.filesDir, "index").apply { mkdirs() }

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = http))
            add(VideoFrameDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "images"))
                .maxSizeBytes(1536L * 1024 * 1024)
                .build()
        }
        .crossfade(120)
        .build()

    // Closing pooled TLS connections touches the network: never on the main thread.
    val vpn = VpnManager(context.applicationContext as Application, settings, scope) { Thread { http.connectionPool.evictAll() }.start() }
    val updates = UpdateManager(context.applicationContext as Application, http, settings, scope)
    val smart = SmartSearch(context.applicationContext, settings)

    init {
        photos.zoneName = settings.zone
        vpnGate = { vpn.gateBlocking() }
    }
}
