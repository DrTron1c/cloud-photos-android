package io.github.drtron1c.cloudphotos.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.drtron1c.cloudphotos.CloudPhotosApp
import io.github.drtron1c.cloudphotos.auth.AuthException
import io.github.drtron1c.cloudphotos.auth.AuthOutcome
import io.github.drtron1c.cloudphotos.auth.CodeDelivery
import io.github.drtron1c.cloudphotos.auth.TrustedPhone
import io.github.drtron1c.cloudphotos.auth.TwoFactorInfo
import io.github.drtron1c.cloudphotos.net.SessionExpiredException
import io.github.drtron1c.cloudphotos.photos.AlbumKind
import io.github.drtron1c.cloudphotos.photos.AlbumSpec
import io.github.drtron1c.cloudphotos.photos.Asset
import io.github.drtron1c.cloudphotos.photos.AssetList
import io.github.drtron1c.cloudphotos.photos.UserAlbum
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException

sealed interface Phase {
    data object Starting : Phase
    data class Login(val appleId: String, val error: String? = null, val busy: Boolean = false) : Phase
    data class TwoFactor(
        val info: TwoFactorInfo,
        val error: String? = null,
        val busy: Boolean = false,
        val notice: String? = null,
    ) : Phase
    data class Waiting(val message: String) : Phase
    data class Failed(val message: String) : Phase
    data object Ready : Phase
}

sealed interface Route {
    data class Album(val spec: AlbumSpec) : Route
    data class Viewer(val spec: AlbumSpec, val index: Int) : Route
    data object Settings : Route
    data object AllAlbums : Route
}

enum class Tab { Library, Albums }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as CloudPhotosApp).graph
    val imageLoader get() = graph.imageLoader
    val http get() = graph.http
    private val prefs get() = graph.prefs

    var phase by mutableStateOf<Phase>(Phase.Starting)
        private set
    var tab by mutableStateOf(Tab.Library)
    val stack = mutableStateListOf<Route>()
    /** Album key + index the viewer was closed on, so the grid can scroll to it. */
    var lastViewed by mutableStateOf<Pair<String, Int>?>(null)
    var librarySelecting by mutableStateOf(false)
    /** Bumped when the Library tab is tapped again: jump to the newest photos. */
    var scrollToNewest by mutableStateOf(0)
    private var lastForegroundCheck = 0L

    var locked by mutableStateOf(prefs.getBoolean(PREF_LOCK_APP, false))
        private set
    /** Set while the photo picker / share sheet is open so returning doesn't re-lock. */
    var expectingExternal = false

    var userAlbums by mutableStateOf<List<UserAlbum>>(emptyList())
        private set
    var albumsLoading by mutableStateOf(false)
        private set
    var zones by mutableStateOf(listOf("PrimarySync"))
        private set
    var zone by mutableStateOf(prefs.getString(PREF_ZONE, "PrimarySync") ?: "PrimarySync")
        private set

    var busy by mutableStateOf<String?>(null)
        private set
    var uploadStatus by mutableStateOf<String?>(null)
        private set

    var gridLevel by mutableStateOf(prefs.getInt(PREF_GRID, 1))
        private set
    var lockApp by mutableStateOf(prefs.getBoolean(PREF_LOCK_APP, false))
        private set
    var lockPrivateAlbums by mutableStateOf(prefs.getBoolean(PREF_LOCK_PRIVATE, true))
        private set
    var rememberPassword by mutableStateOf(graph.authState.savedPassword != null)
        private set

    val appleId get() = graph.authState.appleId
    val fullName get() = graph.authState.fullName
    val canUpload get() = graph.authState.photosUploadUrl != null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val lists = HashMap<String, AssetList>()
    private val reauthMutex = Mutex()

    init {
        graph.photos.zoneName = zone
        graph.reauthHandler = { reauthenticate() }
        viewModelScope.launch { startup() }
    }

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    private fun friendly(e: Throwable): String = when (e) {
        is AuthException -> e.message ?: "Sign-in failed."
        is UnknownHostException -> "You're offline. Check your internet connection."
        is SessionExpiredException -> "Your iCloud session expired. Please sign in again."
        is IOException -> e.message ?: "Network error."
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------------- sign-in ----------------

    private suspend fun startup() {
        phase = Phase.Starting
        val auth = graph.auth
        try {
            if (auth.resume()) {
                afterSignedIn(); return
            }
            val silent = runCatching { auth.silentSignIn() }.getOrNull()
            when (silent) {
                is AuthOutcome.SignedIn -> { afterSignedIn(); return }
                is AuthOutcome.NeedsTwoFactor -> { phase = Phase.TwoFactor(silent.info); return }
                null -> Unit
            }
            phase = Phase.Login(graph.authState.appleId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            phase = if (e is IOException && e !is SessionExpiredException) {
                Phase.Failed(if (e is UnknownHostException) friendly(e) else "Couldn't reach iCloud: ${friendly(e)}")
            } else {
                Phase.Login(graph.authState.appleId, friendly(e))
            }
        }
    }

    fun retryStartup() {
        viewModelScope.launch { startup() }
    }

    fun signIn(appleId: String, password: String, remember: Boolean) {
        if (appleId.isBlank() || password.isEmpty()) return
        phase = Phase.Login(appleId, busy = true)
        viewModelScope.launch {
            try {
                when (val r = graph.auth.signIn(appleId, password)) {
                    is AuthOutcome.SignedIn -> {
                        graph.auth.rememberPassword(if (remember) password else null)
                        rememberPassword = remember
                        afterSignedIn()
                    }
                    is AuthOutcome.NeedsTwoFactor -> {
                        graph.auth.rememberPassword(if (remember) password else null)
                        rememberPassword = remember
                        phase = Phase.TwoFactor(r.info)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                phase = Phase.Login(appleId, friendly(e))
            }
        }
    }

    fun submitCode(code: String) {
        val p = phase as? Phase.TwoFactor ?: return
        if (p.busy) return
        phase = p.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                graph.auth.verifyCode(code, p.info.delivery)
                afterSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                phase = p.copy(busy = false, error = friendly(e), notice = null)
            }
        }
    }

    fun resendToDevices() {
        val p = phase as? Phase.TwoFactor ?: return
        viewModelScope.launch {
            graph.auth.pushToDevices()
            phase = p.copy(info = p.info.copy(delivery = CodeDelivery.Devices), error = null, notice = "A new code was sent to your devices.")
        }
    }

    fun sendSms(phone: TrustedPhone) {
        val p = phase as? Phase.TwoFactor ?: return
        viewModelScope.launch {
            try {
                graph.auth.sendSms(phone)
                phase = p.copy(info = p.info.copy(delivery = CodeDelivery.Sms(phone)), error = null, notice = "Code sent by text to ${phone.number}.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                phase = p.copy(error = friendly(e))
            }
        }
    }

    fun cancelSignIn() {
        phase = Phase.Login(graph.authState.appleId)
    }

    private suspend fun afterSignedIn() {
        phase = Phase.Waiting("Opening your library…")
        try {
            graph.auth.ensureWebAccess { msg -> phase = Phase.Waiting(msg) }
            phase = Phase.Waiting("Opening your library…")
            val available = runCatching { graph.photos.zones() }.getOrDefault(listOf("PrimarySync"))
            zones = available.filter { it == "PrimarySync" || it.startsWith("SharedSync") }.ifEmpty { listOf("PrimarySync") }
            if (zone !in zones) setZoneInternal("PrimarySync")
            var state = graph.photos.indexingState()
            var tries = 0
            while (state != "FINISHED" && tries < 10) {
                phase = Phase.Waiting("iCloud is still preparing your library for the web…")
                delay(6000)
                state = graph.photos.indexingState()
                tries++
            }
            if (state != "FINISHED") {
                phase = Phase.Failed("iCloud hasn't finished preparing your photo library for web access yet. Try again in a few minutes.")
                return
            }
            phase = Phase.Ready
            loadAlbums()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            phase = Phase.Login(graph.authState.appleId, friendly(e))
        } catch (e: AuthException) {
            phase = Phase.Failed(friendly(e))
        } catch (e: Throwable) {
            phase = Phase.Failed(friendly(e))
        }
    }

    /** CloudKit rejected us mid-session: try to quietly get a new session. */
    private suspend fun reauthenticate(): Boolean = reauthMutex.withLock {
        withContext(Dispatchers.Main) {
            val auth = graph.auth
            if (runCatching { auth.resume() }.getOrDefault(false)) return@withContext true
            when (val r = runCatching { auth.silentSignIn() }.getOrNull()) {
                is AuthOutcome.SignedIn -> true
                is AuthOutcome.NeedsTwoFactor -> {
                    phase = Phase.TwoFactor(r.info); false
                }
                null -> {
                    phase = Phase.Login(graph.authState.appleId, "Your iCloud session expired. Please sign in again.")
                    false
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            busy = "Signing out…"
            graph.auth.signOut()
            lists.clear()
            userAlbums = emptyList()
            stack.clear()
            tab = Tab.Library
            rememberPassword = false
            busy = null
            phase = Phase.Login(graph.authState.appleId)
        }
    }

    // ---------------- library data ----------------

    private fun handleError(e: Throwable) {
        if (e is CancellationException) return
        if (e is SessionExpiredException) {
            if (phase is Phase.Ready) phase = Phase.Login(graph.authState.appleId, friendly(e))
            return
        }
        toast(friendly(e))
    }

    fun list(spec: AlbumSpec): AssetList =
        lists.getOrPut(spec.key + "@" + zone) {
            AssetList(spec, graph.photos, viewModelScope, ::handleError).also { it.start() }
        }

    fun loadAlbums() {
        if (albumsLoading) return
        albumsLoading = true
        viewModelScope.launch {
            try {
                userAlbums = if (zone == "PrimarySync") graph.photos.userAlbums() else emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleError(e)
            } finally {
                albumsLoading = false
            }
        }
    }

    fun refreshAll() {
        lists.values.forEach { it.refresh() }
        loadAlbums()
    }

    private fun setZoneInternal(z: String) {
        zone = z
        graph.photos.zoneName = z
        prefs.edit().putString(PREF_ZONE, z).apply()
    }

    fun switchZone(z: String) {
        if (z == zone) return
        setZoneInternal(z)
        stack.clear()
        lists.clear()
        userAlbums = emptyList()
        loadAlbums()
    }

    // ---------------- navigation ----------------

    fun open(route: Route) {
        stack.add(route)
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    // ---------------- edits ----------------

    private fun forEachListContaining(id: String, block: (AssetList) -> Unit) {
        lists.values.forEach { if (it.indexOf(id) >= 0) block(it) }
    }

    private fun listFor(spec: AlbumSpec) = lists[spec.key + "@" + zone]

    fun toggleFavorite(asset: Asset) {
        viewModelScope.launch {
            try {
                val updated = graph.photos.setFavorite(asset, !asset.isFavorite)
                forEachListContaining(asset.id) { it.update(updated) }
                listFor(AlbumSpec.FAVORITES)?.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleError(e)
            }
        }
    }

    fun setFavorite(assets: List<Asset>, favorite: Boolean, done: () -> Unit = {}) = batch(assets, {
        listFor(AlbumSpec.FAVORITES)?.refresh()
        done()
    }) { a ->
        val updated = graph.photos.setFavorite(a, favorite)
        forEachListContaining(a.id) { it.update(updated) }
    }

    fun delete(assets: List<Asset>, done: () -> Unit = {}) = batch(assets, {
        removeEverywhere(assets, except = AlbumSpec.RECENTLY_DELETED)
        listFor(AlbumSpec.RECENTLY_DELETED)?.refresh()
        done()
    }) { a -> graph.photos.setDeleted(a, true) }

    fun recover(assets: List<Asset>, done: () -> Unit = {}) = batch(assets, {
        listFor(AlbumSpec.RECENTLY_DELETED)?.removeAll(assets.map { it.id }.toSet())
        lists.values.filter { it.spec.kind != AlbumKind.Deleted }.forEach { it.refresh() }
        done()
    }) { a -> graph.photos.setDeleted(a, false) }

    fun setHidden(assets: List<Asset>, hidden: Boolean, done: () -> Unit = {}) = batch(assets, {
        if (hidden) {
            removeEverywhere(assets, except = AlbumSpec.HIDDEN)
            listFor(AlbumSpec.HIDDEN)?.refresh()
        } else {
            listFor(AlbumSpec.HIDDEN)?.removeAll(assets.map { it.id }.toSet())
            lists.values.filter { it.spec.kind != AlbumKind.Hidden }.forEach { it.refresh() }
        }
        done()
    }) { a -> graph.photos.setHidden(a, hidden) }

    private fun removeEverywhere(assets: List<Asset>, except: AlbumSpec) {
        val ids = assets.map { it.id }.toSet()
        lists.values.filter { it.spec.key != except.key }.forEach { it.removeAll(ids) }
    }

    private fun batch(assets: List<Asset>, after: () -> Unit, action: suspend (Asset) -> Unit) {
        if (assets.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            if (assets.size > 1) busy = "Updating ${assets.size} items…"
            for (a in assets) {
                try {
                    action(a)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SessionExpiredException) {
                    handleError(e); break
                } catch (e: Throwable) {
                    failed++
                    if (failed == 1) toast(friendly(e))
                }
            }
            busy = null
            after()
        }
    }

    // ---------------- files ----------------

    fun saveToDevice(assets: List<Asset>) {
        if (assets.isEmpty()) return
        viewModelScope.launch {
            var saved = 0
            assets.forEachIndexed { i, a ->
                busy = if (assets.size == 1) "Downloading…" else "Downloading ${i + 1} of ${assets.size}…"
                try {
                    graph.media.saveToGallery(a)
                    saved++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    toast("${a.filename}: ${friendly(e)}")
                }
            }
            busy = null
            if (saved > 0) toast(if (saved == 1) "Saved to Gallery › Cloud Photos" else "$saved items saved to Gallery › Cloud Photos")
        }
    }

    fun share(assets: List<Asset>, onReady: (Intent) -> Unit) {
        if (assets.isEmpty()) return
        viewModelScope.launch {
            try {
                busy = "Preparing…"
                val intent = graph.media.shareIntent(assets) { i, n ->
                    busy = if (n == 1) "Preparing…" else "Preparing ${i + 1} of $n…"
                }
                busy = null
                expectingExternal = true
                onReady(Intent.createChooser(intent, null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                busy = null
                toast(friendly(e))
            }
        }
    }

    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            uris.forEachIndexed { i, uri ->
                uploadStatus = if (uris.size == 1) "Uploading…" else "Uploading ${i + 1} of ${uris.size}…"
                try {
                    val (file, name, modified) = graph.media.stageForUpload(uri)
                    try {
                        graph.photos.upload(file, name, modified)
                        ok++
                    } finally {
                        file.delete()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    toast(friendly(e))
                }
            }
            uploadStatus = if (ok > 0) "Uploaded $ok — iCloud is processing…" else null
            if (ok > 0) {
                delay(6000)
                listFor(AlbumSpec.LIBRARY)?.refresh()
                delay(1500)
                uploadStatus = null
            }
        }
    }

    // ---------------- settings ----------------

    fun changeGridLevel(level: Int) {
        gridLevel = level
        prefs.edit().putInt(PREF_GRID, level).apply()
    }

    fun changeLockApp(on: Boolean) {
        lockApp = on
        prefs.edit().putBoolean(PREF_LOCK_APP, on).apply()
    }

    fun changeLockPrivateAlbums(on: Boolean) {
        lockPrivateAlbums = on
        prefs.edit().putBoolean(PREF_LOCK_PRIVATE, on).apply()
    }

    fun forgetPassword() {
        graph.auth.rememberPassword(null)
        rememberPassword = false
    }

    fun unlock() {
        locked = false
    }

    fun onResumed() {
        expectingExternal = false
    }

    /** Back in the app after a while: pick up photos taken on the iPhone meanwhile. */
    fun onForeground() {
        if (phase != Phase.Ready) return
        val now = System.currentTimeMillis()
        if (now - lastForegroundCheck < 3 * 60 * 1000L) return
        lastForegroundCheck = now
        lists.values.forEach { it.checkForChanges() }
    }

    fun selectTab(t: Tab) {
        if (t == tab && t == Tab.Library) scrollToNewest++
        tab = t
    }

    fun onBackgrounded() {
        if (expectingExternal) {
            expectingExternal = false
            return
        }
        if (lockApp) locked = true
    }

    companion object {
        const val PREF_GRID = "grid_level"
        const val PREF_LOCK_APP = "lock_app"
        const val PREF_LOCK_PRIVATE = "lock_private_albums"
        const val PREF_ZONE = "zone"
    }
}
