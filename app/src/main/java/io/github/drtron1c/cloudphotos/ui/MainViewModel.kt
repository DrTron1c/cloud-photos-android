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
}

enum class Tab { Library, Albums, Search }

/** One block in the Days view: a day header or a photo position. */
sealed interface GridEntry {
    data class Header(val title: String, val subtitle: String?, val key: String) : GridEntry
    data class Item(val index: Int) : GridEntry
}

/** A month or year card in the Months / Years views. */
data class Period(val key: String, val title: String, val subtitle: String, val first: Int, val last: Int, val cover: Int)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as CloudPhotosApp).graph
    val settings = graph.settings
    val imageLoader get() = graph.imageLoader
    val http get() = graph.http
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
        if (settings.aiSearch) {
            viewModelScope.launch { smart.index() }
            SmartIndexWorker.start(getApplication(), settings)
        }
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
        val m = SearchQuery.parse(query) ?: return
        if (!indexMatchesLibrary) {
            toast("Still organising your library… ${(index.progress * 100).toInt()}%")
            return
        }
        val byName = index.entries.count { m.matches(it, zoneId) }
        if (!m.hasPhrase || byName > 0 || !smartReady) {
            search(query)?.let { openList(it) }
            if (byName == 0 && m.hasPhrase && settings.aiSearch && !smartReady) {
                toast("On-device AI search is still getting ready — see Settings › Smart Search")
            }
            return
        }
        viewModelScope.launch {
            busy = "Searching…"
            try {
                val candidates = index.entries.filter { m.matchesFilters(it, zoneId) }.map { it.id }
                val (ranked, confident) = smart.rank(m.phrase, candidates)
                busy = null
                if (ranked.isEmpty()) {
                    toast("Nothing found for “${query.trim()}”")
                    return@launch
                }
                val score = ranked.withIndex().associate { it.value.first to it.index }
                val list = virtualList(
                    "smart:" + query.trim().lowercase(), query.trim(), "search",
                    order = { matches -> matches.sortedBy { score[it.value.id] ?: Int.MAX_VALUE } },
                ) { it.id in score }
                if (list != null) {
                    if (!confident) toast("No clear matches — showing the closest photos")
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
        stack.add(route)
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
