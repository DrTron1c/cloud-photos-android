package io.github.drtron1c.cloudphotos.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.drtron1c.cloudphotos.BuildConfig
import io.github.drtron1c.cloudphotos.ai.ModelState
import io.github.drtron1c.cloudphotos.ai.SmartIndexWorker
import io.github.drtron1c.cloudphotos.CloudPhotosApp
import io.github.drtron1c.cloudphotos.update.ChangeEntry
import io.github.drtron1c.cloudphotos.update.Changelog
import io.github.drtron1c.cloudphotos.vpn.VpnState
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
import io.github.drtron1c.cloudphotos.photos.IndexEntry
import io.github.drtron1c.cloudphotos.photos.LocalCopies
import io.github.drtron1c.cloudphotos.photos.LibraryIndex
import io.github.drtron1c.cloudphotos.photos.MediaList
import io.github.drtron1c.cloudphotos.photos.SharedAlbum
import io.github.drtron1c.cloudphotos.photos.StaticList
import io.github.drtron1c.cloudphotos.photos.SubsetList
import io.github.drtron1c.cloudphotos.photos.UserAlbum
import io.github.drtron1c.cloudphotos.photos.Version
import io.github.drtron1c.cloudphotos.identity.CustomLauncher
import io.github.drtron1c.cloudphotos.identity.LauncherApk
import io.github.drtron1c.cloudphotos.settings.AppIdentity
import io.github.drtron1c.cloudphotos.settings.Keys
import io.github.drtron1c.cloudphotos.work.AutoUploadWorker
import io.github.drtron1c.cloudphotos.work.OfflineWorker
import io.github.drtron1c.cloudphotos.work.WidgetWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

/** The activity side of picture-in-picture (kept weakly by the view model). */
interface PipHost {
    fun setPipCandidate(playing: Boolean, w: Int, h: Int)
    fun enterPipNow()
}

sealed interface Route {
    /** A grid of any registered list (album, shared album, search results, utilities…). */
    data class Grid(val key: String) : Route
    data class Viewer(val key: String, val index: Int) : Route
    data class Slideshow(val key: String, val index: Int) : Route
    data class Editor(val key: String, val index: Int) : Route
    data object Settings : Route
    data object AllAlbums : Route
    data object AppIdentity : Route
    data object Vpn : Route
    data object Memories : Route
    data class MemoryPlayer(val id: String) : Route
    data object People : Route
    data class Person(val id: String) : Route
    data object Places : Route
    data object Similar : Route
    data class Trim(val key: String, val index: Int) : Route

    /** Covers the whole window (photo viewer, slideshow, editor…), even next to a sidebar. */
    val fullScreen: Boolean
        get() = this is Viewer || this is Slideshow || this is Editor || this is MemoryPlayer || this is Trim
}

enum class Tab { Library, Albums, Search }

/** One block in the Days view: a day header or a photo position. */
sealed interface GridEntry {
    data class Header(val title: String, val subtitle: String?, val key: String) : GridEntry
    data class Item(val index: Int) : GridEntry
}

/** A month or year card in the Months / Years views. */
data class Period(val key: String, val title: String, val subtitle: String, val first: Int, val last: Int, val cover: Int)

/** Export: either [assets], or all of [list]. */
class ExportRequest(val title: String, val assets: List<Asset>, val list: MediaList?) {
    val size get() = if (assets.isNotEmpty()) assets.size else list?.count ?: 0
}

/** Marks drags that start in Cloud Photos (so dropping them back in doesn't re-upload). */
val DRAG_FROM_HERE = Any()

/** The photo's thumbnail under your finger while dragging it to another app. */
class ThumbShadow(view: android.view.View, private val bmp: Bitmap) : android.view.View.DragShadowBuilder(view) {
    private val side = (view.resources.displayMetrics.density * 120).toInt()
    private val w = if (bmp.width >= bmp.height) side else side * bmp.width / bmp.height.coerceAtLeast(1)
    private val h = if (bmp.height >= bmp.width) side else side * bmp.height / bmp.width.coerceAtLeast(1)

    override fun onProvideShadowMetrics(outShadowSize: android.graphics.Point, outShadowTouchPoint: android.graphics.Point) {
        outShadowSize.set(w.coerceAtLeast(1), h.coerceAtLeast(1))
        outShadowTouchPoint.set(w / 2, h / 2)
    }

    override fun onDrawShadow(canvas: android.graphics.Canvas) {
        canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as CloudPhotosApp).graph
    val settings = graph.settings
    val imageLoader get() = graph.imageLoader
    val http get() = graph.http
    val mediaCache get() = graph.mediaCache
    val vpn get() = graph.vpn
    val updates get() = graph.updates
    private val prefs get() = graph.prefs

    var phase by mutableStateOf<Phase>(Phase.Starting)
        private set
    var tab by mutableStateOf(
        when (settings.startTab) {
            "albums" -> Tab.Albums
            "search" -> Tab.Search
            else -> Tab.Library
        }
    )
    val stack = mutableStateListOf<Route>()
    /** List key + index the viewer was closed on, so the grid can scroll to it. */
    var lastViewed by mutableStateOf<Pair<String, Int>?>(null)
    var librarySelecting by mutableStateOf(false)
    /** Bumped when the Library tab is tapped again: jump to the newest photos. */
    var scrollToNewest by mutableIntStateOf(0)
    /** Where a Years/Months tap should land in the next view: (view, position). */
    var pendingJump by mutableStateOf<Pair<String, Int>?>(null)
    private var lastForegroundCheck = 0L

    /** No connection at start-up, showing albums kept offline. */
    var offlineMode by mutableStateOf(false)
        private set

    var locked by mutableStateOf(settings.lockApp)
        private set
    /** Set while the photo picker / share sheet is open so returning doesn't re-lock. */
    var expectingExternal = false

    var userAlbums by mutableStateOf<List<UserAlbum>>(emptyList())
        private set
    var sharedAlbums by mutableStateOf<List<SharedAlbum>>(emptyList())
        private set
    var albumsLoading by mutableStateOf(false)
        private set
    var zones by mutableStateOf(listOf("PrimarySync"))
        private set
    val zone get() = settings.zone

    var busy by mutableStateOf<String?>(null)
        private set
    var uploadStatus by mutableStateOf<String?>(null)
        private set
    var rememberPassword by mutableStateOf(graph.authState.savedPassword != null)
        private set
    /** Names of downloaded offline files (kept in memory so cells don't touch the disk). */
    private var offlineNames by mutableStateOf<Set<String>>(emptySet())
    var offlineAlbums by mutableStateOf<List<AlbumSpec>>(emptyList())
        private set
    var recentlyViewed by mutableStateOf<List<String>>(emptyList())
        private set

    val appleId get() = graph.authState.appleId
    val fullName get() = graph.authState.fullName
    val canUpload get() = graph.authState.photosUploadUrl != null && !offlineMode

    val index = LibraryIndex(graph.photos, viewModelScope, graph.indexDir)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val lists = HashMap<String, MediaList>()
    private val reauthMutex = Mutex()

    /** Change-log sections to announce after an update (declared before init, which fills it). */
    var whatsNew by mutableStateOf<List<ChangeEntry>>(emptyList())

    // Your own app name & icon (declared before init, which reads them).
    /** Picture handed over by the viewer's "Use as App Icon". */
    var iconSource by mutableStateOf<Any?>(null)
    /** Label of the installed companion app, or null when it isn't installed. */
    var companionLabel by mutableStateOf<String?>(null)
    /** Bumped whenever the saved name / picture change. */
    var identityVersion by mutableIntStateOf(0)
    var needsInstallPermission by mutableStateOf(false)
    var companionConflict by mutableStateOf(false)
    private var pendingCompanion: Pair<String, Bitmap>? = null

    init {
        graph.photos.zoneName = zone
        graph.reauthHandler = { reauthenticate() }
        recentlyViewed = loadRecentlyViewed()
        refreshOffline()
        refreshCompanion()
        loadWhatsNew()
        // Start-up first: it brings the VPN up (if set) before anything else goes online.
        viewModelScope.launch { startup() }
        updates.checkOnLaunch()
        if (settings.aiSearch || settings.aiPeople || settings.aiText) {
            viewModelScope.launch { smart.index(); vision.load() }
            SmartIndexWorker.start(getApplication(), settings)
        }
        io.github.drtron1c.cloudphotos.settings.AppShortcuts.publish(getApplication())
        if (settings.memoriesNotify) io.github.drtron1c.cloudphotos.memories.OnThisDayWorker.schedule(getApplication(), true)
        viewModelScope.launch {
            CustomLauncher.events.collect {
                toast(it)
                refreshCompanion()
            }
        }
    }

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    private fun friendly(e: Throwable): String = when (e) {
        is AuthException -> e.message ?: "Sign-in failed."
        is UnknownHostException -> e.message?.takeIf { it.contains("encrypted DNS") } ?: "You're offline. Check your internet connection."
        is SessionExpiredException -> "Your iCloud session expired. Please sign in again."
        is IOException -> e.message ?: "Network error."
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------------- sign-in ----------------

    private suspend fun startup() {
        phase = Phase.Starting
        val auth = graph.auth
        // Proton VPN first, so nothing reaches the network outside it.
        if (vpn.shouldAutoConnect()) {
            val ok = vpn.connect()
            if (!ok && settings.vpnRequire) {
                phase = Phase.Failed(
                    "Proton VPN couldn't connect, and “Only use the internet through the VPN” is on, so Cloud Photos is waiting. " +
                        ((vpn.state.value as? VpnState.Failed)?.message ?: "")
                )
                return
            }
        }
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
            if (e is IOException && e !is SessionExpiredException) {
                if (offlineAlbums.isNotEmpty() && withContext(Dispatchers.IO) { graph.offline.hasAnyContent(prefs) }) {
                    enterOfflineMode(); return
                }
                phase = Phase.Failed(if (e is UnknownHostException) friendly(e) else "Couldn't reach iCloud: ${friendly(e)}")
            } else {
                phase = Phase.Login(graph.authState.appleId, friendly(e))
            }
        }
    }

    private fun enterOfflineMode() {
        offlineMode = true
        lists.clear()
        phase = Phase.Ready
        toast("You're offline — showing albums kept on this device")
    }

    fun retryStartup() {
        offlineMode = false
        lists.clear()
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
        offlineMode = false
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
            index.open(zone)
            watchLibraryForIndex()
            loadAlbums()
            scheduleBackgroundWork()
            consumePendingOpen()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            phase = Phase.Login(graph.authState.appleId, friendly(e))
        } catch (e: Throwable) {
            phase = Phase.Failed(friendly(e))
        }
    }

    private var indexWatcherStarted = false

    private fun watchLibraryForIndex() {
        if (indexWatcherStarted) return
        indexWatcherStarted = true
        viewModelScope.launch {
            snapshotFlow { if (phase == Phase.Ready && !offlineMode) list(AlbumSpec.LIBRARY).count else -1 }
                .distinctUntilChanged()
                .collect { if (it > 0) index.sync(it) }
        }
    }

    private fun scheduleBackgroundWork() {
        val ctx = getApplication<Application>()
        AutoUploadWorker.schedule(ctx, settings.autoUpload, settings.autoUploadWifiOnly)
        OfflineWorker.schedule(ctx, offlineAlbums.isNotEmpty())
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
            sharedAlbums = emptyList()
            stack.clear()
            tab = Tab.Library
            rememberPassword = false
            settings.autoUpload = false
            AutoUploadWorker.schedule(getApplication(), false, true)
            // Leave nothing of the library behind on this device.
            clearOffline()
            clearRecentlyViewed()
            offlineMode = false
            index.reset()
            withContext(Dispatchers.IO) { imageLoader.diskCache?.clear() }
            imageLoader.memoryCache?.clear()
            busy = null
            phase = Phase.Login(graph.authState.appleId)
        }
    }

    // ---------------- lists ----------------

    private fun handleError(e: Throwable) {
        if (e is CancellationException) return
        if (e is SessionExpiredException) {
            if (phase is Phase.Ready && !offlineMode) phase = Phase.Login(graph.authState.appleId, friendly(e))
            return
        }
        if (offlineMode) return
        toast(friendly(e))
    }

    private fun keyFor(spec: AlbumSpec): String = when (spec.kind) {
        AlbumKind.Shared, AlbumKind.Virtual -> spec.key + if (settings.newestOnTop) "/t" else "/b"
        else -> spec.key + "@" + zone + (if (settings.newestOnTop) "/t" else "/b") + (if (offlineMode) "/offline" else "")
    }

    /** The list for a CloudKit / shared album (created on first use). */
    fun list(spec: AlbumSpec): MediaList {
        val key = keyFor(spec)
        return lists.getOrPut(key) { create(spec, key).also { it.start() } }
    }

    fun listByKey(key: String): MediaList? = lists[key]

    private fun create(spec: AlbumSpec, key: String): MediaList {
        val newestTop = settings.newestOnTop
        if (offlineMode) {
            return StaticList(key, spec, viewModelScope, ::handleError, staleMs = 0) {
                val items = withContext(Dispatchers.IO) { graph.offline.manifest(spec.key) } ?: emptyList()
                if (newestTop) items.reversed() else items
            }
        }
        return when (spec.kind) {
            AlbumKind.Shared -> StaticList(key, spec, viewModelScope, ::handleError) {
                val album = SharedAlbum.fromJson(JSONObject(spec.filterJson ?: "{}"))
                val items = graph.photos.sharedAlbumAssets(album)
                if (newestTop) items.reversed() else items
            }
            else -> AssetList(key, spec, graph.photos, viewModelScope, newestTop, ::handleError)
        }
    }

    private fun library(): AssetList? = list(AlbumSpec.LIBRARY) as? AssetList

    val indexMatchesLibrary: Boolean
        get() {
            val lib = library() ?: return false
            return index.ready && lib.count >= 0 && index.entries.size == lib.count
        }

    /**
     * A collection computed from the library index (search results, filters, utilities).
     * Returns null while the index is still being built.
     */
    fun virtualList(
        id: String,
        title: String,
        icon: String = "photo",
        order: ((List<IndexedValue<IndexEntry>>) -> List<IndexedValue<IndexEntry>>)? = null,
        filter: (IndexEntry) -> Boolean,
    ): MediaList? {
        val lib = library() ?: return null
        if (!indexMatchesLibrary) return null
        val matches = index.entries.withIndex().filter { filter(it.value) }
        val ordered = order?.invoke(matches) ?: matches.sortedBy { lib.positionOfRank(it.index) }
        val spec = AlbumSpec.virtual(id, title, icon)
        val list = SubsetList(
            key = keyFor(spec),
            spec = spec,
            base = lib,
            positions = ordered.map { lib.positionOfRank(it.index) },
            ids = ordered.map { it.value.id },
            onMoved = { viewModelScope.launch { indexOutOfStep() } },
        )
        lists[list.key] = list
        return list
    }

    fun openList(list: MediaList) = open(Route.Grid(list.key))

    fun openAlbum(spec: AlbumSpec) = openList(list(spec))

    // ----- utilities / filters / search built on the index -----

    private val zoneId: ZoneId get() = ZoneId.systemDefault()
    private fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()

    fun recentlyEditedList() = virtualList("edited", "Recently Edited", "edited") { it.edited }

    fun duplicatesList(): MediaList? {
        if (!indexMatchesLibrary) return null
        val groups = index.entries.withIndex()
            .filter { it.value.checksum.isNotEmpty() }
            .groupBy { it.value.checksum }
            .filter { it.value.size > 1 }
        val dupIds = groups.values.flatten().map { it.value.id }.toSet()
        val lib = library() ?: return null
        return virtualList("duplicates", "Duplicates", "duplicates", order = { matches ->
            matches.sortedWith(compareBy({ groups[it.value.checksum]?.minOf { g -> lib.positionOfRank(g.index) } ?: 0 }, { lib.positionOfRank(it.index) }))
        }) { it.id in dupIds }
    }

    fun onThisDayList(): MediaList? {
        val today = LocalDate.now(zoneId)
        return virtualList("onthisday", "On This Day", "onthisday") {
            val d = day(it.date)
            d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year
        }
    }

    fun recentlyViewedList(): MediaList? {
        val order = recentlyViewed
        val pos = order.withIndex().associate { it.value to it.index }
        return virtualList("viewed", "Recently Viewed", "viewed", order = { m -> m.sortedBy { pos[it.value.id] ?: 0 } }) { it.id in pos }
    }

    /** Library filter (Favourites / Edited / Photos / Videos). Null = whole library. */
    fun filteredLibrary(filter: String): MediaList? = when (filter) {
        "favourites" -> list(AlbumSpec.FAVORITES)
        "videos" -> list(AlbumSpec.VIDEOS)
        "photos" -> virtualList("filter:photos", "Photos") { !it.video }
        "edited" -> recentlyEditedList()
        else -> null
    }

    fun search(query: String): MediaList? {
        val m = SearchQuery.parse(query) ?: return null
        return virtualList("search:" + query.trim().lowercase(), query.trim(), "search") { m.matches(it, zoneId) }
    }

    /** Smart search is on and has analysed photos. */
    val smartReady: Boolean get() = settings.aiSearch && smart.modelState.value is ModelState.Ready && smart.indexed.value > 0

    /**
     * Search and open the results. File names win when they match (e.g. "IMG_1234"); otherwise,
     * with on-device AI search on, the words are matched against what's in the pictures.
     */
    fun runSearch(query: String) {
        var m = SearchQuery.parse(query) ?: return
        if (!indexMatchesLibrary) {
            toast("Still organising your library… ${(index.progress * 100).toInt()}%")
            return
        }
        // A person's name ("Anna beach 2025") narrows the search to their photos.
        var personIds: Set<String>? = null
        var personName: String? = null
        if (settings.aiPeople && m.hasPhrase) {
            val words = m.phrase.split(" ")
            for (p in people.filter { it.person.name.isNotBlank() }) {
                val name = p.person.name.lowercase()
                val first = name.substringBefore(' ')
                val hit = when {
                    m.phrase.contains(name) -> name
                    first.length >= 3 && first in words -> first
                    else -> null
                }
                if (hit != null) {
                    personIds = p.assetIds.toSet()
                    personName = p.person.name
                    SearchQuery.parse(query.lowercase().replace(hit, " "))?.let { m = it }
                    break
                }
            }
        }
        val title = query.trim()
        if (personIds != null && !m.hasPhrase) {
            val ids = personIds
            virtualList("search:" + title.lowercase(), title, "search") { it.id in ids && m.matchesFilters(it, zoneId) }?.let { openList(it) }
            return
        }
        val byName = index.entries.count { m.matches(it, zoneId) && (personIds == null || it.id in personIds) }
        val textReady = settings.aiText && vision.textDone.value > 0
        if (!m.hasPhrase || byName > 0 || (!smartReady && !textReady)) {
            val ids = personIds
            virtualList("search:" + title.lowercase(), title, "search") { m.matches(it, zoneId) && (ids == null || it.id in ids) }?.let { openList(it) }
            if (byName == 0 && m.hasPhrase && (settings.aiSearch || settings.aiText) && !smartReady && !textReady) {
                toast("On-device AI search is still getting ready — see Settings › Smart Search")
            }
            return
        }
        val phrase = m.phrase
        viewModelScope.launch {
            busy = "Searching…"
            try {
                // Only the visible library: the index never contains Hidden or Recently Deleted items.
                val candidates = index.entries.filter { m.matchesFilters(it, zoneId) && (personIds == null || it.id in personIds) }.map { it.id }
                // Words written in the photo (signs, documents, screenshots) come first…
                val byText = if (textReady) withContext(Dispatchers.Default) { vision.searchText(phrase, candidates) } else emptyList()
                // …then what the picture shows.
                val (ranked, confident) = if (smartReady) smart.rank(phrase, candidates) else emptyList<Pair<String, Float>>() to false
                busy = null
                val order = LinkedHashMap<String, Int>()
                byText.forEach { order.putIfAbsent(it, order.size) }
                ranked.forEach { order.putIfAbsent(it.first, order.size) }
                if (order.isEmpty()) {
                    toast("Nothing found for “$title”")
                    return@launch
                }
                val list = virtualList(
                    "smart:" + title.lowercase(), title, "search",
                    order = { matches -> matches.sortedBy { order[it.value.id] ?: Int.MAX_VALUE } },
                ) { it.id in order }
                if (list != null) {
                    if (!confident && byText.isEmpty()) toast("No clear matches — showing the closest photos")
                    else if (personName != null) toast("Photos of $personName")
                    openList(list)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                busy = null
                toast("Smart search failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    val smart get() = graph.smart

    fun setAiSearch(on: Boolean) {
        settings.aiSearch = on
        val app = getApplication<Application>()
        if (on) {
            viewModelScope.launch { smart.index() }
            SmartIndexWorker.start(app, settings)
        } else SmartIndexWorker.stop(app)
    }

    fun setAiChargingOnly(on: Boolean) {
        settings.aiChargingOnly = on
        val app = getApplication<Application>()
        if (settings.aiSearch) {
            SmartIndexWorker.stop(app)
            SmartIndexWorker.start(app, settings)
        }
    }

    fun deleteAiData() {
        val app = getApplication<Application>()
        SmartIndexWorker.stop(app)
        settings.aiSearch = false
        smart.deleteAll()
        prefs.edit().remove(Keys.AI_STATUS).apply()
        toast("On-device AI data deleted")
    }

    fun aiStatus(): String = prefs.getString(Keys.AI_STATUS, "") ?: ""

    fun searchCount(query: String): Int {
        val m = SearchQuery.parse(query) ?: return 0
        if (!index.ready) return -1
        return index.entries.count { m.matches(it, zoneId) }
    }

    /** Years present in the library, newest first (for search suggestions). */
    fun years(): List<Int> = index.entries.asSequence().map { day(it.date).year }.distinct().sortedDescending().toList()

    /** Dates of the whole library in display order (for Days / Months / Years). */
    private fun libraryDates(): List<Long>? {
        val lib = library() ?: return null
        if (!indexMatchesLibrary) return null
        val e = index.entries
        val n = e.size
        return List(n) { d -> e[if (settings.newestOnTop) d else n - 1 - d].date }
    }

    fun daySections(): List<GridEntry>? {
        val dates = libraryDates() ?: return null
        val out = ArrayList<GridEntry>(dates.size + dates.size / 8)
        var current: LocalDate? = null
        val fmtThisYear = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())
        val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())
        val thisYear = LocalDate.now(zoneId).year
        dates.forEachIndexed { i, millis ->
            val d = day(millis)
            if (d != current) {
                current = d
                out += GridEntry.Header(if (d.year == thisYear) d.format(fmtThisYear) else d.format(fmt), null, "day:$d:$i")
            }
            out += GridEntry.Item(i)
        }
        return out
    }

    fun periods(byYear: Boolean): List<Period>? {
        val dates = libraryDates() ?: return null
        val out = ArrayList<Period>()
        var start = 0
        fun keyOf(m: Long): Int { val d = day(m); return if (byYear) d.year else d.year * 100 + d.monthValue }
        for (i in 1..dates.size) {
            if (i == dates.size || keyOf(dates[i]) != keyOf(dates[start])) {
                val d = day(dates[start])
                val n = i - start
                val title = if (byYear) d.year.toString() else d.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                val cover = if (settings.newestOnTop) start else i - 1
                // Start index keeps keys unique even if a stray date splits a year/month in two.
                out += Period("${keyOf(dates[start])}:$start", title, if (n == 1) "1 item" else "%,d items".format(n), start, i - 1, cover)
                start = i
            }
        }
        return out
    }

    // ---------------- albums ----------------

    fun loadAlbums() {
        if (albumsLoading || offlineMode) return
        albumsLoading = true
        viewModelScope.launch {
            try {
                userAlbums = if (zone == "PrimarySync") graph.photos.userAlbums() else emptyList()
                sharedAlbums = runCatching { graph.photos.sharedAlbums() }.getOrDefault(emptyList())
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
        settings.zone = z
        graph.photos.zoneName = z
    }

    fun switchZone(z: String) {
        if (z == zone) return
        setZoneInternal(z)
        stack.clear()
        lists.clear()
        userAlbums = emptyList()
        index.open(z)
        loadAlbums()
    }

    fun setNewestOnTop(on: Boolean) {
        if (settings.newestOnTop == on) return
        settings.newestOnTop = on
        stack.removeAll { it !is Route.Settings }
        lists.clear()
    }

    fun createAlbum(name: String, assets: List<Asset> = emptyList(), done: () -> Unit = {}) {
        val title = name.trim()
        if (title.isEmpty()) return
        viewModelScope.launch {
            busy = "Creating “$title”…"
            try {
                val id = graph.photos.createAlbum(title)
                if (assets.isNotEmpty()) graph.photos.addToAlbum(id, assets)
                userAlbums = userAlbums + UserAlbum(id, title)
                toast(if (assets.isEmpty()) "Album “$title” created" else "Added ${assets.size} to “$title”")
                delay(1500)
                loadAlbums()
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleError(e)
            } finally {
                busy = null
            }
        }
    }

    fun addToAlbum(album: UserAlbum, assets: List<Asset>, done: () -> Unit = {}) {
        if (assets.isEmpty()) return
        viewModelScope.launch {
            busy = "Adding to “${album.title}”…"
            try {
                graph.photos.addToAlbum(album.recordName, assets)
                lists[keyFor(AlbumSpec.user(album.recordName, album.title))]?.refresh()
                toast(if (assets.size == 1) "Added to “${album.title}”" else "Added ${assets.size} items to “${album.title}”")
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleError(e)
            } finally {
                busy = null
            }
        }
    }

    // ---------------- offline ----------------

    private fun refreshOffline() {
        offlineAlbums = graph.offline.albums(prefs)
        viewModelScope.launch {
            offlineNames = withContext(Dispatchers.IO) { graph.offline.dir.list()?.toHashSet() ?: emptySet() }
        }
    }

    fun isOffline(spec: AlbumSpec) = offlineAlbums.any { it.key == spec.key }

    fun setOffline(spec: AlbumSpec, keep: Boolean) {
        val now = graph.offline.albums(prefs).filter { it.key != spec.key }
        val next = if (keep) now + spec else now
        graph.offline.setAlbums(prefs, next)
        offlineAlbums = next
        val ctx = getApplication<Application>()
        OfflineWorker.schedule(ctx, next.isNotEmpty())
        OfflineWorker.syncNow(ctx)
        toast(if (keep) "“${spec.title}” will be downloaded for offline viewing" else "“${spec.title}” removed from offline")
    }

    fun clearOffline() {
        graph.offline.clearAll(prefs)
        offlineAlbums = emptyList()
        offlineNames = emptySet()
        OfflineWorker.schedule(getApplication(), false)
    }

    fun offlineBytes(): Long = graph.offline.totalBytes()
    fun offlineStatus(): String = prefs.getString(Keys.OFFLINE_STATUS, "") ?: ""

    /** Local file if this rendition is kept offline, else its iCloud link (for Coil). */
    fun model(v: Version): Any {
        val name = graph.offline.nameFor(v)
        return if (name in offlineNames) File(graph.offline.dir, name) else v.url
    }

    fun mediaUri(v: Version): Uri {
        val name = graph.offline.nameFor(v)
        return if (name in offlineNames) Uri.fromFile(File(graph.offline.dir, name)) else Uri.parse(v.url)
    }

    // ---------------- navigation ----------------

    fun open(route: Route) {
        // Photo open beside the grid: tapping another photo swaps it rather than stacking up.
        if (route is Route.Viewer && splitActive) {
            val i = stack.indexOfFirst { it is Route.Viewer }
            if (i >= 0) while (stack.size > i) stack.removeAt(stack.lastIndex)
        }
        stack.add(route)
    }

    /** Sidebar / shortcut: straight to a tab. */
    fun goHome(t: Tab) {
        stack.clear()
        tab = t
    }

    /** Sidebar / shortcut: straight to a screen. */
    fun goTo(route: Route) {
        stack.clear()
        stack.add(route)
    }

    // ---------------- keyboard ----------------

    val keys = KeyRouter()
    var showShortcuts by mutableStateOf(false)
    /** Bumped by Ctrl+F: the Search tab focuses its field. */
    var focusSearch by mutableIntStateOf(0)
    /** Bumped by Ctrl+U: opens the upload picker. */
    var uploadRequest by mutableIntStateOf(0)
    /** Set by the layout: a photo is open beside the grid. */
    var splitActive = false
    /** The photo open beside the grid (highlighted in it). */
    var viewingId by mutableStateOf<String?>(null)
    // ---------------- picture-in-picture ----------------

    var pipHost: java.lang.ref.WeakReference<PipHost>? = null
    /** The app is shrunk to the floating video window. */
    var inPip by mutableStateOf(false)
    fun pipCandidate(playing: Boolean, w: Int, h: Int) = pipHost?.get()?.setPipCandidate(playing, w, h)
    fun enterPip() = pipHost?.get()?.enterPipNow()

    /** Opened from an app shortcut, the Quick Settings tile or a notification. */
    fun openFromOutside(target: String) {
        pendingOpen = target
        if (phase == Phase.Ready) consumePendingOpen()
    }

    private var pendingOpen: String? = null

    private fun consumePendingOpen() {
        val t = pendingOpen ?: return
        pendingOpen = null
        when (t) {
            "search" -> { goHome(Tab.Search); focusSearch++ }
            "favourites" -> { stack.clear(); openAlbum(AlbumSpec.FAVORITES) }
            "memories" -> goTo(Route.Memories)
            "places" -> goTo(Route.Places)
            "people" -> goTo(Route.People)
            "upload" -> { goHome(Tab.Library); if (canUpload) uploadRequest++ }
            "vpn" -> goTo(Route.Vpn)
            "onthisday" -> {
                stack.clear()
                val l = onThisDayList()
                if (l != null) openList(l) else { pendingOpen = "onthisday"; waitForIndexThenOpen() }
            }
        }
    }

    private fun waitForIndexThenOpen() {
        viewModelScope.launch {
            snapshotFlow { indexMatchesLibrary }.first { it }
            consumePendingOpen()
        }
    }

    /** Something is being dragged over the app (drop to upload). */
    var dropHover by mutableStateOf(false)

    /** A key from a hardware keyboard. [typing] = a text field has the keyboard. */
    fun handleKey(e: android.view.KeyEvent, typing: Boolean): Boolean {
        val code = e.keyCode
        if (typing && !e.ctrl) return false
        if (keys.dispatch(e)) return true
        if (phase != Phase.Ready) return false
        return when {
            (e.ctrl && code == android.view.KeyEvent.KEYCODE_F) || (e.plain && code == android.view.KeyEvent.KEYCODE_SLASH) -> {
                goHome(Tab.Search); focusSearch++; true
            }
            e.ctrl && code == android.view.KeyEvent.KEYCODE_1 -> { goHome(Tab.Library); true }
            e.ctrl && code == android.view.KeyEvent.KEYCODE_2 -> { goHome(Tab.Albums); true }
            e.ctrl && code == android.view.KeyEvent.KEYCODE_3 -> { goHome(Tab.Search); true }
            e.ctrl && code == android.view.KeyEvent.KEYCODE_M && !offlineMode -> { goTo(Route.Memories); true }
            e.ctrl && code == android.view.KeyEvent.KEYCODE_COMMA -> {
                if (stack.lastOrNull() != Route.Settings) open(Route.Settings); true
            }
            e.ctrl && code == android.view.KeyEvent.KEYCODE_U -> { if (canUpload) uploadRequest++; true }
            (e.ctrl && code == android.view.KeyEvent.KEYCODE_R) || code == android.view.KeyEvent.KEYCODE_F5 -> {
                if (!offlineMode) { refreshAll(); toast("Refreshing…") }; true
            }
            e.ctrl && code == android.view.KeyEvent.KEYCODE_SLASH -> { showShortcuts = true; true }
            code == android.view.KeyEvent.KEYCODE_ESCAPE || (e.isAltPressed && code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -> back()
            else -> false
        }
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun selectTab(t: Tab) {
        if (t == tab && t == Tab.Library) scrollToNewest++
        tab = t
    }

    // ---------------- edits ----------------

    private fun forEachListContaining(id: String, block: (MediaList) -> Unit) {
        lists.values.toList().forEach { if (it.indexOf(id) >= 0) block(it) }
    }

    private fun listFor(spec: AlbumSpec) = lists[keyFor(spec)]

    fun toggleFavorite(asset: Asset) = setFavorite(listOf(asset), !asset.isFavorite)

    fun setFavorite(assets: List<Asset>, favorite: Boolean, done: () -> Unit = {}) = batch(assets, {
        listFor(AlbumSpec.FAVORITES)?.refresh()
        done()
    }) { a ->
        val updated = graph.photos.setFavorite(a, favorite)
        forEachListContaining(a.id) { it.update(updated) }
        index.update(updated)
    }

    fun setCaption(asset: Asset, caption: String) {
        viewModelScope.launch {
            try {
                val updated = graph.photos.setCaption(asset, caption)
                forEachListContaining(asset.id) { it.update(updated) }
                toast(if (caption.isBlank()) "Caption removed" else "Caption saved")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleError(e)
            }
        }
    }

    fun delete(assets: List<Asset>, done: () -> Unit = {}) = batch(assets, {
        removeEverywhere(assets, except = AlbumSpec.RECENTLY_DELETED)
        index.remove(assets.map { it.id }.toSet())
        // Smart Search, People and Text never keep or search anything in Recently Deleted.
        viewModelScope.launch { smart.forget(assets.map { it.id }.toSet()); vision.forget(assets.map { it.id }.toSet()) }
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
            index.remove(assets.map { it.id }.toSet())
            // …or in Hidden.
            viewModelScope.launch { smart.forget(assets.map { it.id }.toSet()); vision.forget(assets.map { it.id }.toSet()) }
            listFor(AlbumSpec.HIDDEN)?.refresh()
        } else {
            listFor(AlbumSpec.HIDDEN)?.removeAll(assets.map { it.id }.toSet())
            lists.values.filter { it.spec.kind != AlbumKind.Hidden }.forEach { it.refresh() }
        }
        done()
    }) { a -> graph.photos.setHidden(a, hidden) }

    private fun removeEverywhere(assets: List<Asset>, except: AlbumSpec) {
        val ids = assets.map { it.id }.toSet()
        // Filtered views first (they look their items up in the base list), then the base lists.
        lists.values.filter { it is SubsetList }.forEach { it.removeAll(ids) }
        lists.values.filter { it !is SubsetList && it.spec.key != except.key }.forEach { it.removeAll(ids) }
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

    fun noteViewed(asset: Asset) {
        if (!settings.recentlyViewedEnabled) return
        val next = (recentlyViewed.filter { it != asset.id } + asset.id).takeLast(200)
        recentlyViewed = next
        prefs.edit().putString(Keys.RECENTLY_VIEWED_IDS, JSONArray(next).toString()).apply()
    }

    fun clearRecentlyViewed() {
        recentlyViewed = emptyList()
        prefs.edit().remove(Keys.RECENTLY_VIEWED_IDS).apply()
    }

    private fun loadRecentlyViewed(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(Keys.RECENTLY_VIEWED_IDS, "[]"))
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())

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
                val intent = graph.media.shareIntent(assets, compatible = settings.shareCompatible) { i, n ->
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

    // ---------------- collections: memories, people & pets, places, similar ----------------

    val vision get() = graph.vision
    private val geo by lazy { io.github.drtron1c.cloudphotos.places.Geo.get(getApplication()) }

    /** Library rank of each photo id (for covers and thumbnails of computed collections). */
    private var rankCache: Pair<List<IndexEntry>, Map<String, Int>>? = null
    private fun rankOf(id: String): Int? {
        val e = index.entries
        val c = rankCache?.takeIf { it.first === e } ?: (e to e.withIndex().associate { it.value.id to it.index }).also { rankCache = it }
        return c.second[id]
    }

    /** Photos found away from where the index put them, until the index is rebuilt. */
    private var movedCache: Pair<List<IndexEntry>, HashMap<String, Asset>>? = null
    private var lastIndexRebuild = 0L

    /**
     * The library item with this id (loads its page if needed; null until it arrives). Only ever
     * the photo with exactly this id: covers and Similar Photos' clean-up depend on it.
     */
    fun assetFor(id: String): Asset? {
        val lib = library() ?: return null
        val rank = rankOf(id) ?: return null
        val pos = lib.positionOfRank(rank)
        val a = lib[pos]
        if (a != null && a.id == id) return a
        // Photos were added or removed since the index was made, so something else sits in its
        // place. Look among what's loaded, load around it, and rebuild the index.
        val e = index.entries
        val moved = movedCache?.takeIf { it.first === e }?.second ?: HashMap<String, Asset>().also { movedCache = e to it }
        moved[id]?.let { return it }
        val j = lib.indexOf(id)
        if (j >= 0) lib[j]?.takeIf { it.id == id }?.let { moved[id] = it; return it }
        if (a == null) { lib.ensure(pos); return null }
        viewModelScope.launch { indexOutOfStep() }
        val c = lib.count
        for (d in intArrayOf(-150, 150, -300, 300)) { val q = pos + d; if (q in 0 until c) lib.ensure(q) }
        return null
    }

    /** The index no longer matches the library's order: rebuild it (at most every few minutes). */
    private fun indexOutOfStep() {
        val lib = library() ?: return
        val now = System.currentTimeMillis()
        if (index.building || lib.count <= 0 || now - lastIndexRebuild < 3 * 60_000) return
        lastIndexRebuild = now
        index.sync(lib.count, force = true)
    }

    /** A grid of these photos: in this order ([keepOrder]) or the library's. */
    fun idsList(key: String, title: String, ids: List<String>, icon: String = "photo", keepOrder: Boolean = true): MediaList? {
        val order = ids.withIndex().associate { it.value to it.index }
        return virtualList(key, title, icon, order = if (keepOrder) { m -> m.sortedBy { order[it.value.id] ?: Int.MAX_VALUE } } else null) { it.id in order }
    }

    var memories by mutableStateOf<List<io.github.drtron1c.cloudphotos.memories.Memory>>(emptyList())
        private set
    var memoriesLoading by mutableStateOf(false)
        private set
    private var memoriesFor: Any? = null

    /** (Re)builds memories when the library changed. All on the device. */
    fun refreshMemories(force: Boolean = false) {
        if (!indexMatchesLibrary) return
        val entries = index.entries
        val key = entries to people.size
        if (!force && memoriesFor == key) return
        memoriesFor = key
        memoriesLoading = true
        viewModelScope.launch {
            val ppl = people.filter { it.person.name.isNotBlank() }.map { it.person.name to it.assetIds }
            val petsNow = pets
            memories = withContext(Dispatchers.Default) {
                runCatching {
                    io.github.drtron1c.cloudphotos.memories.MemoryMaker.make(entries, LocalDate.now(zoneId), zoneId, geo, ppl, petsNow)
                }.getOrDefault(emptyList())
            }
            memoriesLoading = false
        }
    }

    /** Memories made on the spot (a person's photos, a place…). */
    private val extraMemories = HashMap<String, io.github.drtron1c.cloudphotos.memories.Memory>()

    fun memoryById(id: String) = memories.firstOrNull { it.id == id } ?: extraMemories[id]

    fun playAsMemory(title: String, subtitle: String, ids: List<String>) {
        val set = ids.toSet()
        val group = index.entries.filter { it.id in set }
        val picked = io.github.drtron1c.cloudphotos.memories.MemoryMaker.pick(group, 45)
        if (picked.isEmpty()) { toast("No photos to show"); return }
        val m = io.github.drtron1c.cloudphotos.memories.Memory(
            "adhoc:" + title + ":" + ids.size, title, subtitle, "person", picked.map { it.id }, picked[picked.size / 3].id, picked.first().date,
        )
        extraMemories[m.id] = m
        playMemory(m)
    }
    fun memoryList(m: io.github.drtron1c.cloudphotos.memories.Memory): MediaList? = idsList("memory:" + m.id, m.title, m.ids, "onthisday")

    fun playMemory(m: io.github.drtron1c.cloudphotos.memories.Memory) {
        if (memoryList(m) == null) { toast("Still organising your library…"); return }
        open(Route.MemoryPlayer(m.id))
    }

    fun setMemoriesNotify(on: Boolean) {
        settings.memoriesNotify = on
        io.github.drtron1c.cloudphotos.memories.OnThisDayWorker.schedule(getApplication(), on)
    }

    // People & Pets

    var people by mutableStateOf<List<io.github.drtron1c.cloudphotos.ai.PersonPhotos>>(emptyList())
        private set
    var pets by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    var peopleLoading by mutableStateOf(false)
        private set

    fun refreshPeople() {
        if (!indexMatchesLibrary) return
        val visible = index.entries.map { it.id }.toSet()
        peopleLoading = true
        viewModelScope.launch {
            try {
                if (settings.aiPeople) people = vision.people(visible)
                if (settings.aiSearch && smartReady) pets = smart.pets(index.entries.filter { !it.video }.map { it.id })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't group faces: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                peopleLoading = false
            }
        }
    }

    fun personList(id: String): MediaList? {
        val p = people.firstOrNull { it.person.id == id } ?: return null
        return idsList("person:$id", p.person.name.ifBlank { "Person" }, p.assetIds, "people", keepOrder = false)
    }

    fun petList(kind: String): MediaList? {
        val ids = pets[kind] ?: return null
        return idsList("pets:$kind", if (kind == "dogs") "Dogs" else "Cats", ids, "people", keepOrder = false)
    }

    fun renamePerson(id: String, name: String) {
        viewModelScope.launch { vision.rename(id, name); refreshPeople(); memoriesFor = null }
    }

    fun mergePeople(into: String, other: String) {
        viewModelScope.launch { vision.merge(into, other); refreshPeople() }
    }

    fun hidePerson(id: String) {
        viewModelScope.launch { vision.hide(id); refreshPeople(); toast("They won't be shown in People") }
    }

    fun namesIn(assetId: String): List<String> = if (settings.aiPeople) vision.namesIn(assetId) else emptyList()

    fun setAiPeople(on: Boolean) {
        settings.aiPeople = on
        restartAnalysis()
    }

    fun setAiText(on: Boolean) {
        settings.aiText = on
        restartAnalysis()
    }

    private fun restartAnalysis() {
        val app = getApplication<Application>()
        SmartIndexWorker.stop(app)
        if (settings.aiSearch || settings.aiPeople || settings.aiText) SmartIndexWorker.start(app, settings)
    }

    fun deletePeopleData() {
        settings.aiPeople = false
        vision.deleteFaces()
        people = emptyList()
        restartAnalysis()
        toast("Faces and names deleted from this device")
    }

    fun deleteTextData() {
        settings.aiText = false
        vision.deleteText()
        restartAnalysis()
        toast("Text from photos deleted from this device")
    }

    /** Text-with-positions already read this session (Live Text). */
    private val textLayouts = HashMap<String, io.github.drtron1c.cloudphotos.ai.TextLayout>()

    /**
     * Live Text: the words in this photo and where they are, read on the device (the text reader
     * is downloaded once if needed). [quiet] = don't download or complain (long-press on a Live Photo).
     */
    fun readText(asset: Asset, quiet: Boolean = false, onResult: (io.github.drtron1c.cloudphotos.ai.TextLayout?) -> Unit) {
        textLayouts[asset.id]?.let { onResult(it); return }
        viewModelScope.launch {
            vision.load()
            if (!vision.textPack.present()) {
                if (quiet || offlineMode) { if (!quiet) toast("The text reader isn't downloaded yet"); onResult(null); return@launch }
                busy = "Getting the text reader (${megabytes(vision.textPack.downloadSize)}, once)…"
                val ok = vision.textPack.download(http)
                busy = null
                if (!ok) { toast("Couldn't download the text reader"); onResult(null); return@launch }
            }
            if (!quiet) busy = "Reading text…"
            try {
                val v = asset.edited ?: asset.medium ?: asset.thumbnail
                val bytes = withContext(Dispatchers.IO) {
                    val local = v?.let { model(it) } as? File
                    local?.readBytes() ?: v?.let { ver ->
                        imageLoader.diskCache?.openSnapshot(ver.checksum)?.use { File(it.data.toString()).readBytes() }
                            ?: http.newCall(okhttp3.Request.Builder().url(ver.url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                    }
                }
                val layout = bytes?.let { b -> withContext(Dispatchers.Default) { vision.readLayout(asset.id, b) } }
                busy = null
                if (layout == null) { if (!quiet) toast("Couldn't read this photo"); onResult(null); return@launch }
                textLayouts[asset.id] = layout
                if (settings.aiText) vision.save()
                onResult(layout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                busy = null
                if (!quiet) toast("Couldn't read the text: ${e.message ?: e.javaClass.simpleName}")
                onResult(null)
            }
        }
    }

    /** Whether the text reader is on the device (long-press can select text straight away). */
    val textReaderReady get() = vision.textPack.present()

    // Places

    class PlaceGroup(val label: String, val ids: List<String>, val lat: Double, val lon: Double)

    var places by mutableStateOf<List<PlaceGroup>>(emptyList())
        private set
    private var placesFor: Any? = null

    /** Photos with a location, grouped by town (offline place names). */
    fun refreshPlaces() {
        if (!indexMatchesLibrary) return
        val entries = index.entries
        if (placesFor === entries) return
        placesFor = entries
        viewModelScope.launch {
            places = withContext(Dispatchers.Default) {
                entries.filter { it.lat != null && it.lon != null && !(it.lat == 0.0 && it.lon == 0.0) }
                    .groupBy { geo.label(it.lat!!, it.lon!!) ?: "Elsewhere" }
                    .map { (label, list) -> PlaceGroup(label, list.map { it.id }, list.map { it.lat!! }.average(), list.map { it.lon!! }.average()) }
                    .sortedByDescending { it.ids.size }
            }
        }
    }

    fun mapPoints(): List<io.github.drtron1c.cloudphotos.places.MapPoint> =
        index.entries.filter { it.lat != null && it.lon != null && !(it.lat == 0.0 && it.lon == 0.0) }
            .map { io.github.drtron1c.cloudphotos.places.MapPoint(it.id, it.lat!!, it.lon!!) }

    fun placeLabel(lat: Double, lon: Double): String? = runCatching { geo.label(lat, lon) }.getOrNull()
    fun geoOrNull(): io.github.drtron1c.cloudphotos.places.Geo? = runCatching { geo }.getOrNull()

    /** Library photos taken within about a kilometre of here. */
    fun photosNear(lat: Double, lon: Double): List<String> =
        index.entries.filter { e ->
            val la = e.lat; val lo = e.lon
            la != null && lo != null && io.github.drtron1c.cloudphotos.memories.MemoryMaker.distanceKm(lat, lon, la, lo) <= 1.0
        }.map { it.id }

    // Similar photos

    var similar by mutableStateOf<List<List<String>>?>(null)
        private set

    fun refreshSimilar() {
        if (!indexMatchesLibrary || !smartReady) return
        viewModelScope.launch {
            val ordered = index.entries.filter { !it.video }.sortedBy { it.date }.map { it.id to it.date }
            similar = smart.similarGroups(ordered)
        }
    }

    /** Best of a group to keep: favourite, then edited, then the biggest file, then the first taken. */
    fun bestOf(ids: List<String>): String {
        val byId = index.entries.associateBy { it.id }
        return ids.maxWith(compareBy<String>({ byId[it]?.favourite == true }, { byId[it]?.edited == true }, { byId[it]?.size ?: 0L }, { -(byId[it]?.date ?: 0L) }))
    }

    /** Delete by id (Similar Photos clean-up): moved to Recently Deleted, like the iPhone. */
    fun deleteIds(ids: Collection<String>, done: () -> Unit = {}) {
        val assets = ids.mapNotNull { assetFor(it) }
        if (assets.size < ids.size) { toast("Still loading those photos — try again in a moment"); return }
        delete(assets) {
            similar = similar?.map { g -> g.filter { it !in ids } }?.filter { it.size >= 2 }
            done()
        }
    }

    // Trim

    /** Saves the part of a video between [startMs] and [endMs] as a new clip (to iCloud, or this device). */
    fun trimVideo(asset: Asset, startMs: Long, endMs: Long, done: () -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            busy = "Downloading the video…"
            val dir = File(app.cacheDir, "trim").apply { mkdirs() }
            var input: File? = null
            var output: File? = null
            try {
                input = graph.media.downloadCurrent(asset, dir, compatible = false)
                output = File(dir, asset.filename.substringBeforeLast('.') + "_trim.MP4")
                VideoTrimmer.trim(app, input, output, startMs, endMs) { p -> busy = "Trimming… ${(p * 100).toInt()}%" }
                if (canUpload) {
                    busy = "Adding to your library…"
                    graph.photos.upload(output, output.name, asset.date + startMs)
                    busy = null
                    toast("Saved as a new video — it'll appear in your library shortly")
                    afterUpload(1)
                } else {
                    busy = "Saving…"
                    withContext(Dispatchers.IO) { VideoTrimmer.saveToGallery(app, output, asset.date + startMs) }
                    busy = null
                    toast("Saved to Gallery › Cloud Photos")
                }
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                busy = null
                toast("Couldn't trim the video: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
                input?.delete()
                output?.delete()
            }
        }
    }

    // ---------------- Shared Albums: posting, comments, likes ----------------

    fun sharedAlbumOf(list: MediaList): SharedAlbum? =
        if (list.spec.kind == AlbumKind.Shared) runCatching { SharedAlbum.fromJson(JSONObject(list.spec.filterJson ?: "{}")) }.getOrNull() else null

    /** Photos and videos from this device into a shared album. */
    fun postToShared(album: SharedAlbum, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            uris.forEachIndexed { i, uri ->
                uploadStatus = if (uris.size == 1) "Adding to “${album.name}”…" else "Adding ${i + 1} of ${uris.size} to “${album.name}”…"
                try {
                    val (file, name, _) = graph.media.stageForUpload(uri)
                    try { graph.photos.sharedUpload(album, file, name); ok++ } finally { file.delete() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    toast(friendly(e))
                }
            }
            afterSharedPost(album, ok)
        }
    }

    /** Library photos into a shared album (each original is fetched, then posted). */
    fun addToShared(album: SharedAlbum, assets: List<Asset>, done: () -> Unit = {}) {
        if (assets.isEmpty()) return
        val dir = File(getApplication<Application>().cacheDir, "upload").apply { mkdirs() }
        viewModelScope.launch {
            var ok = 0
            assets.forEachIndexed { i, a ->
                uploadStatus = if (assets.size == 1) "Adding to “${album.name}”…" else "Adding ${i + 1} of ${assets.size} to “${album.name}”…"
                try {
                    val file = graph.media.downloadCurrent(a, dir, compatible = false)
                    try { graph.photos.sharedUpload(album, file, file.name); ok++ } finally { file.delete() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    toast("${a.filename}: ${friendly(e)}")
                }
            }
            done()
            afterSharedPost(album, ok)
        }
    }

    private suspend fun afterSharedPost(album: SharedAlbum, ok: Int) {
        uploadStatus = if (ok > 0) "Added $ok to “${album.name}” — iCloud is processing…" else null
        if (ok > 0) {
            delay(6000)
            lists[keyFor(AlbumSpec.shared(album))]?.refresh()
            delay(1500)
            uploadStatus = null
        }
    }

    fun loadComments(album: SharedAlbum, asset: Asset, onResult: (List<io.github.drtron1c.cloudphotos.photos.SharedComment>) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(graph.photos.sharedComments(album, asset))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't load comments: ${friendly(e)}")
                onResult(emptyList())
            }
        }
    }

    fun addComment(album: SharedAlbum, asset: Asset, text: String, done: () -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            try {
                graph.photos.sharedComment(album, asset, t)
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't post the comment: ${friendly(e)}")
            }
        }
    }

    /** Like, or take your like back. */
    fun toggleLike(album: SharedAlbum, asset: Asset, comments: List<io.github.drtron1c.cloudphotos.photos.SharedComment>, done: () -> Unit) {
        viewModelScope.launch {
            try {
                val mine = comments.firstOrNull { it.isLike && it.byMe }
                if (mine != null) graph.photos.sharedDeleteComment(album, asset, mine) else graph.photos.sharedComment(album, asset, null)
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't update your like: ${friendly(e)}")
            }
        }
    }

    fun deleteComment(album: SharedAlbum, asset: Asset, c: io.github.drtron1c.cloudphotos.photos.SharedComment, done: () -> Unit) {
        viewModelScope.launch {
            try {
                graph.photos.sharedDeleteComment(album, asset, c)
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't delete the comment: ${friendly(e)}")
            }
        }
    }

    // ---------------- copy, drag & drop, frames, export ----------------

    /** Ctrl+C / Copy: the photo on the clipboard, ready to paste into another app. */
    fun copyToClipboard(asset: Asset) {
        val app = getApplication<Application>()
        fun put(uri: Uri, name: String) {
            val cm = app.getSystemService(android.content.ClipboardManager::class.java) ?: return
            cm.setPrimaryClip(android.content.ClipData.newUri(app.contentResolver, name, uri))
            toast("Copied")
        }
        val cached = if (!asset.isVideo) LocalCopies.cachedCopy(app, imageLoader, asset) { model(it) } else null
        if (cached != null) {
            put(cached.first, asset.filename); return
        }
        if (offlineMode) { toast("Not available offline"); return }
        viewModelScope.launch {
            busy = "Preparing…"
            try {
                val dir = File(app.cacheDir, "drag").apply { mkdirs() }
                val file = graph.media.downloadCurrent(asset, dir, settings.shareCompatible)
                put(androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file), file.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(friendly(e))
            } finally {
                busy = null
            }
        }
    }

    /** Long-press drags the photo out when another app is on screen too (split screen, floating window). */
    fun canDragOut(context: android.content.Context): Boolean =
        context.findActivity()?.isInMultiWindowMode == true

    fun startDragOut(view: android.view.View, asset: Asset) {
        val app = getApplication<Application>()
        val copy = LocalCopies.cachedCopy(app, imageLoader, asset) { model(it) }
        if (copy == null) { toast("Still loading — try again in a moment"); return }
        val clip = android.content.ClipData(
            android.content.ClipDescription(asset.filename, arrayOf(copy.second)),
            android.content.ClipData.Item(copy.first),
        )
        val thumb = asset.thumbnail?.let { imageLoader.memoryCache?.get(coil3.memory.MemoryCache.Key(it.checksum))?.image }
        val bmp = (thumb as? coil3.BitmapImage)?.bitmap
        val shadow = if (bmp != null) ThumbShadow(view, bmp) else android.view.View.DragShadowBuilder(view)
        val flags = android.view.View.DRAG_FLAG_GLOBAL or android.view.View.DRAG_FLAG_GLOBAL_URI_READ
        if (!view.startDragAndDrop(clip, shadow, DRAG_FROM_HERE, flags)) toast("Couldn't start dragging")
    }

    /** Photos dropped onto the app from another app go to iCloud. */
    fun onDrop(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!canUpload) { toast("Uploading isn't available right now"); return }
        upload(uris)
    }

    /** Save the frame on screen as a photo (to this device, or to your iCloud library). */
    fun saveFrame(asset: Asset, frame: Bitmap, positionMs: Long, toLibrary: Boolean) {
        val app = getApplication<Application>()
        val base = asset.filename.substringBeforeLast('.')
        val name = "${base}_frame_${positionMs / 1000}s.JPG"
        val taken = if (asset.date > 0) asset.date + positionMs else System.currentTimeMillis()
        viewModelScope.launch {
            busy = "Saving frame…"
            try {
                if (toLibrary) {
                    val dir = File(app.cacheDir, "frames").apply { mkdirs() }
                    val file = File(dir, name)
                    withContext(Dispatchers.IO) { file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
                    try {
                        graph.photos.upload(file, name, taken)
                    } finally {
                        file.delete()
                    }
                    busy = null
                    toast("Frame added to your library")
                    afterUpload(1)
                } else {
                    withContext(Dispatchers.IO) { LocalCopies.saveBitmapToGallery(app, frame, name, taken) }
                    toast("Frame saved to Gallery › Cloud Photos")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleError(e)
            } finally {
                busy = null
                frame.recycle()
            }
        }
    }

    /** Waiting for the folder picker: what to export and in which format. */
    var exportRequest by mutableStateOf<ExportRequest?>(null)

    fun exportAssets(assets: List<Asset>, title: String) {
        if (assets.isEmpty()) return
        exportRequest = ExportRequest(title, assets, null)
    }

    fun exportAll(list: MediaList) {
        exportRequest = ExportRequest(list.spec.title, emptyList(), list)
    }

    /** Folder picked: copy everything there, one at a time (keep the app open for big exports). */
    fun runExport(request: ExportRequest, tree: Uri, compatible: Boolean) {
        exportRequest = null
        viewModelScope.launch {
            try {
                val assets = request.assets.ifEmpty {
                    uploadStatus = "Getting ${request.title} ready…"
                    loadAll(request.list ?: return@launch)
                }
                var done = 0
                var failed = 0
                assets.forEachIndexed { i, a ->
                    uploadStatus = "Exporting ${i + 1} of ${assets.size}…"
                    try {
                        graph.media.exportTo(tree, a, compatible)
                        done++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failed++
                        if (failed == 1) toast("${a.filename}: ${friendly(e)}")
                    }
                }
                toast(if (failed == 0) "Exported $done items" else "Exported $done items · $failed couldn't be exported")
            } finally {
                uploadStatus = null
            }
        }
    }

    /** Every item of a list (loading the pages it hasn't fetched yet). */
    private suspend fun loadAll(list: MediaList): List<Asset> {
        val out = ArrayList<Asset>()
        var waited = 0
        while (list.count < 0 && waited < 300) { delay(100); waited++ }
        for (i in 0 until list.count.coerceAtLeast(0)) {
            var a = list[i]
            var tries = 0
            while (a == null && tries < 200) {
                list.ensure(i); delay(50); tries++; a = list[i]
            }
            if (a != null) out += a
        }
        return out
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
            afterUpload(ok)
        }
    }

    private suspend fun afterUpload(ok: Int) {
        uploadStatus = if (ok > 0) "Uploaded $ok — iCloud is processing…" else null
        if (ok > 0) {
            delay(8000)
            listFor(AlbumSpec.LIBRARY)?.refresh()
            listFor(AlbumSpec.RECENTLY_SAVED)?.refresh()
            delay(1500)
            uploadStatus = null
        }
    }

    /** Render an edit and upload it as a new photo next to the original (iPhone "Save as copy"). */
    fun saveEdit(asset: Asset, bitmap: Bitmap, crop: FloatArray, matrix: FloatArray, done: () -> Unit) {
        viewModelScope.launch {
            busy = "Saving copy…"
            try {
                val file = withContext(Dispatchers.Default) {
                    EditRender.render(getApplication<Application>().cacheDir, asset, bitmap, crop, matrix)
                }
                val base = asset.filename.substringBeforeLast('.')
                try {
                    graph.photos.upload(file, "${base}_edit.JPG", asset.date)
                } finally {
                    file.delete()
                }
                busy = null
                done()
                toast("Saved as a new photo — it'll appear in your library shortly")
                afterUpload(1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                busy = null
                handleError(e)
            }
        }
    }

    // ---------------- settings ----------------

    fun changeGridLevel(level: Int) {
        settings.gridLevel = level
    }

    fun changeLockApp(on: Boolean) {
        settings.lockApp = on
    }

    fun changeLockPrivateAlbums(on: Boolean) {
        settings.lockPrivateAlbums = on
    }

    fun forgetPassword() {
        graph.auth.rememberPassword(null)
        rememberPassword = false
    }

    fun setDns(provider: String) {
        settings.dnsProvider = provider
        // Closing pooled TLS connections touches the network: not on the main thread.
        viewModelScope.launch(Dispatchers.IO) { graph.http.connectionPool.evictAll() }
    }

    fun setAutoUpload(on: Boolean, fromNow: Boolean = true) {
        if (on) {
            prefs.edit()
                .putLong(Keys.AUTO_UPLOAD_SINCE, if (fromNow) System.currentTimeMillis() / 1000 else 0L)
                .putString(Keys.AUTO_UPLOAD_STATUS, "Waiting to run…")
                .apply()
        }
        settings.autoUpload = on
        AutoUploadWorker.schedule(getApplication(), on, settings.autoUploadWifiOnly)
    }

    fun setAutoUploadWifiOnly(on: Boolean) {
        settings.autoUploadWifiOnly = on
        AutoUploadWorker.schedule(getApplication(), settings.autoUpload, on)
    }

    fun autoUploadStatus(): String = prefs.getString(Keys.AUTO_UPLOAD_STATUS, "") ?: ""

    fun setWidgetMode(mode: String) {
        settings.widgetMode = mode
        WidgetWorker.refreshNow(getApplication())
    }

    fun setLauncherName(alias: String) {
        settings.launcherName = alias
        val ctx = getApplication<Application>()
        if (settings.hideOriginalIcon && CustomLauncher.isInstalled(ctx)) {
            toast("Saved — the original icon is hidden while your own app is installed")
        } else {
            AppIdentity.showWanted(ctx, settings)
            toast("The new name shows on your home screen after you leave the app")
        }
    }

    // ---------------- your own app name & icon ----------------

    fun refreshCompanion() {
        val ctx = getApplication<Application>()
        companionLabel = if (CustomLauncher.isInstalled(ctx)) CustomLauncher.installedLabel(ctx) ?: "" else null
    }

    fun useAsAppIcon(asset: Asset) {
        val v = asset.medium ?: asset.thumbnail ?: return
        iconSource = model(v)
        open(Route.AppIdentity)
    }

    /** Builds your companion app with this name and picture and asks Android to install it. */
    fun installCompanion(name: String, icon: Bitmap) {
        val ctx = getApplication<Application>()
        val clean = LauncherApk.cleanName(name)
        if (clean.isEmpty()) {
            toast("Type a name first"); return
        }
        if (!CustomLauncher.canInstall(ctx)) {
            pendingCompanion = clean to icon
            needsInstallPermission = true
            return
        }
        pendingCompanion = null
        viewModelScope.launch {
            busy = "Making “$clean”…"
            try {
                val apk = withContext(Dispatchers.IO) {
                    if (!CustomLauncher.canUpdateInstalled(ctx)) null
                    else CustomLauncher.make(ctx, clean, CustomLauncher.saveIcon(ctx, icon))
                }
                if (apk == null) {
                    companionConflict = true
                } else {
                    settings.customName = clean
                    identityVersion++
                    expectingExternal = true
                    withContext(Dispatchers.IO) { CustomLauncher.install(ctx, apk) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't make your app: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    /** Saves the name / picture for inside the app (lock screen, Recents) without installing. */
    fun saveIdentity(name: String, icon: Bitmap?) {
        val ctx = getApplication<Application>()
        settings.customName = LauncherApk.cleanName(name)
        viewModelScope.launch {
            if (icon != null) withContext(Dispatchers.IO) { CustomLauncher.saveIcon(ctx, icon) }
            identityVersion++
        }
    }

    fun allowInstallsIntent(): Intent {
        expectingExternal = true
        return CustomLauncher.allowInstallsIntent(getApplication()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Uninstall prompt for the companion app; the original icon comes back first. */
    fun removeCompanionIntent(): Intent {
        AppIdentity.showOriginalNow(getApplication(), settings)
        expectingExternal = true
        return CustomLauncher.uninstallIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun setHideOriginal(on: Boolean) {
        settings.hideOriginalIcon = on
        // Showing happens now; hiding when you leave the app.
        AppIdentity.showWanted(getApplication(), settings)
    }

    fun pinIdentityShortcut(name: String, icon: Bitmap) {
        val clean = LauncherApk.cleanName(name).ifEmpty { "Cloud Photos" }
        val ok = AppIdentity.pinShortcut(getApplication(), clean, icon)
        if (!ok) toast("Your home screen doesn't allow adding shortcuts — try “Add as App” instead")
        else toast("If nothing appears, allow “Home screen shortcuts” for Cloud Photos in Android settings")
    }

    fun pinShortcut(name: String, color: Int) {
        val ok = AppIdentity.pinShortcut(getApplication(), name, color)
        if (!ok) toast("Your launcher doesn't allow adding shortcuts")
    }

    /** Space the temporary video cache uses now (read off the main thread). */
    suspend fun mediaCacheBytes(): Long = withContext(Dispatchers.IO) { graph.mediaCache.used() }

    fun clearMediaCache(done: () -> Unit = {}) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.mediaCache.clear() }
            toast("Cached videos cleared from this device")
            done()
        }
    }

    fun clearMediaCacheQuietly() {
        val c = graph.mediaCache
        Thread { runCatching { c.clear() } }.start()
    }

    /** A cache setting changed: apply it now (size limit, how long things are kept, off). */
    fun applyMediaCache() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { graph.mediaCache.trim() } }
    }

    fun clearImageCache() {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
        toast("Cached photos cleared from this device")
    }

    fun unlock() {
        locked = false
    }

    fun onResumed() {
        expectingExternal = false
        val ctx = getApplication<Application>()
        CustomLauncher.reconcile(ctx, appOpen = true)
        refreshCompanion()
        // Came back from allowing installs: carry on with the companion app.
        val pending = pendingCompanion
        if (pending != null && CustomLauncher.canInstall(ctx)) {
            needsInstallPermission = false
            installCompanion(pending.first, pending.second)
        }
    }

    /** Back in the app after a while: pick up photos taken on the iPhone meanwhile. */
    fun onForeground() {
        vpn.onAppOpened()
        updates.onAppForeground()
        updates.checkOnForeground()
        refreshOffline()
        if (phase != Phase.Ready || offlineMode) return
        val now = System.currentTimeMillis()
        if (now - lastForegroundCheck < 3 * 60 * 1000L) return
        lastForegroundCheck = now
        lists.values.forEach { it.checkForChanges() }
    }

    /** Leaving the app (not just for a picker / installer): apply launcher icon changes now. */
    fun applyLauncherChanges() {
        if (!expectingExternal) AppIdentity.applyAll(getApplication(), settings)
    }

    /** Leaving the app: "Disconnect when you leave" (not when just opening a picker, share sheet or installer). */
    fun onLeftApp() {
        if (!expectingExternal && !updates.isHandingOff()) vpn.onAppLeft()
    }

    /** Settings › Proton VPN › "Only use the internet through the VPN" made start-up wait: switch it off and go. */
    fun continueWithoutRequiringVpn() {
        settings.vpnRequire = false
        retryStartup()
    }

    // ---------------- updates / what's new ----------------

    private fun loadWhatsNew() {
        val app = getApplication<Application>()
        if (settings.lastSeenChangelog == 0 && graph.authState.appleId.isBlank()) {
            // Fresh install: nothing is "new" yet.
            settings.lastSeenChangelog = BuildConfig.VERSION_CODE
            return
        }
        whatsNew = Changelog.unseen(loadChangelog(app), settings.lastSeenChangelog, BuildConfig.VERSION_CODE)
    }

    fun dismissWhatsNew() {
        settings.lastSeenChangelog = BuildConfig.VERSION_CODE
        whatsNew = emptyList()
    }

    fun onBackgrounded() {
        if (expectingExternal) {
            expectingExternal = false
            return
        }
        if (settings.lockApp) locked = true
    }
}

/**
 * Plain-language search over the library index: dates ("late 2026", "summer 2025", "last month",
 * "christmas"), file names and types. Words that aren't dates or types are, with on-device AI
 * search on, matched against what's in the pictures ([phrase]).
 */
class SearchQuery private constructor(
    private val dateTests: List<(LocalDate) -> Boolean>,
    private val video: Boolean?,
    private val favourite: Boolean,
    private val edited: Boolean,
    private val png: Boolean,
    private val located: Boolean,
    private val words: List<String>,
) {
    /** What's left for picture-content search, e.g. "red hat". */
    val phrase: String = words.filter { it !in STOP_WORDS }.joinToString(" ")
    val hasPhrase get() = phrase.isNotBlank()

    /** Dates / types / flags only (no file-name words). */
    fun matchesFilters(e: IndexEntry, zone: ZoneId): Boolean {
        if (video != null && e.video != video) return false
        if (favourite && !e.favourite) return false
        if (edited && !e.edited) return false
        if (png && !e.name.lowercase().endsWith(".png")) return false
        if (located && (e.lat == null || e.lon == null)) return false
        if (dateTests.isNotEmpty()) {
            val d = Instant.ofEpochMilli(e.date).atZone(zone).toLocalDate()
            if (!dateTests.all { it(d) }) return false
        }
        return true
    }

    fun matches(e: IndexEntry, zone: ZoneId): Boolean {
        if (!matchesFilters(e, zone)) return false
        val name = e.name.lowercase()
        return words.filter { it !in STOP_WORDS }.all { name.contains(it) }
    }

    companion object {
        fun parse(query: String, today: LocalDate = LocalDate.now()): SearchQuery? {
            val tokens = query.lowercase().split(Regex("[\\s,./-]+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return null
            var year: Int? = null
            var month: Int? = null
            var day: Int? = null
            var exact: LocalDate? = null
            var range: ClosedRange<LocalDate>? = null
            var part: IntRange? = null      // months of the year: early / mid / late
            var season: Set<Int>? = null    // months
            var christmas = false
            var weekend = false
            var video: Boolean? = null
            var fav = false
            var edited = false
            var png = false
            var located = false
            val words = ArrayList<String>()
            var i = 0
            while (i < tokens.size) {
                val t = tokens[i]
                val next = tokens.getOrNull(i + 1)
                val n = t.toIntOrNull()
                when {
                    n != null && n in 1900..2100 -> year = n
                    n != null && n in 1..31 -> day = n
                    t.matches(Regex("(19|20)\\d\\ds")) -> {
                        val start = t.dropLast(1).toInt()
                        range = LocalDate.of(start, 1, 1)..LocalDate.of(start + 9, 12, 31)
                    }
                    t == "today" -> exact = today
                    t == "yesterday" -> exact = today.minusDays(1)
                    (t == "this" || t == "last" || t == "past") && next in setOf("year", "month", "week") -> {
                        val back = if (t == "this") 0L else 1L
                        range = when (next) {
                            "year" -> today.minusYears(back).withDayOfYear(1).let { it..it.plusYears(1).minusDays(1) }
                            "month" -> today.minusMonths(back).withDayOfMonth(1).let { it..it.plusMonths(1).minusDays(1) }
                            else -> today.minusWeeks(back).with(java.time.DayOfWeek.MONDAY).let { it..it.plusDays(6) }
                        }
                        i++
                    }
                    t in setOf("early", "beginning", "start") -> part = 1..4
                    t in setOf("mid", "middle") -> part = 5..8
                    t in setOf("late", "end") -> part = 9..12
                    t == "spring" -> season = setOf(3, 4, 5)
                    t == "summer" -> season = setOf(6, 7, 8)
                    t == "autumn" || t == "fall" -> season = setOf(9, 10, 11)
                    t == "winter" -> season = setOf(12, 1, 2)
                    t == "christmas" || t == "xmas" -> christmas = true
                    t == "weekend" || t == "weekends" -> weekend = true
                    t in setOf("video", "videos", "movie", "movies", "clip", "clips") -> video = true
                    t in setOf("photo", "photos", "picture", "pictures", "pic", "pics", "image", "images") -> video = false
                    t in setOf("favourite", "favourites", "favorite", "favorites", "fav", "favs", "loved", "liked") -> fav = true
                    t == "edited" || t == "edits" -> edited = true
                    t.startsWith("screenshot") -> png = true
                    t in setOf("location", "located", "places", "place", "map") -> located = true
                    monthIndex(t) >= 0 -> month = monthIndex(t) + 1
                    else -> words += t
                }
                i++
            }
            val tests = ArrayList<(LocalDate) -> Boolean>()
            exact?.let { e -> tests.add { it == e } }
            range?.let { r -> tests.add { it in r } }
            year?.let { y -> tests.add { it.year == y } }
            month?.let { m -> tests.add { it.monthValue == m } }
            day?.let { dd -> tests.add { it.dayOfMonth == dd } }
            part?.let { p -> tests.add { it.monthValue in p } }
            season?.let { ss -> tests.add { it.monthValue in ss } }
            if (christmas) tests.add { it.monthValue == 12 && it.dayOfMonth in 20..31 }
            if (weekend) tests.add { it.dayOfWeek == java.time.DayOfWeek.SATURDAY || it.dayOfWeek == java.time.DayOfWeek.SUNDAY }
            // "late" with no year means late this year.
            if ((part != null || season != null) && year == null && range == null && exact == null) {
                val y = today.year
                tests.add { it.year == y || (season == setOf(12, 1, 2) && it.year == y - 1 && it.monthValue == 12) }
            }
            return SearchQuery(tests, video, fav, edited, png, located, words)
        }

        private val STOP_WORDS = setOf("in", "on", "at", "from", "during", "of", "the", "a", "an", "with", "and", "my", "me", "taken", "around", "time")

        private val FULL = listOf(
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        )

        /** 0-based month for "jan", "january", "sept"…; -1 if [token] isn't a month. */
        private fun monthIndex(token: String): Int {
            if (token == "sept") return 8
            if (token.length < 3) return -1
            return FULL.indexOfFirst { it.startsWith(token) }
        }
    }
}
