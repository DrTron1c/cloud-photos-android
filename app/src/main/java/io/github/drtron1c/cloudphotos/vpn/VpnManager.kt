package io.github.drtron1c.cloudphotos.vpn

import android.app.Application
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import io.github.drtron1c.cloudphotos.net.SecureFile
import io.github.drtron1c.cloudphotos.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.TimeZone

sealed interface VpnState {
    /** Not connected (or no Proton account added). */
    data object Off : VpnState
    data class Connecting(val country: String, val attempt: Int) : VpnState
    data class Connected(val server: VpnServer, val since: Long) : VpnState
    data class Failed(val message: String) : VpnState
    /** Android must ask you once whether Cloud Photos may run a VPN. */
    data object NeedsPermission : VpnState
}

/**
 * Proton VPN inside Cloud Photos, over WireGuard (the official WireGuard library). By default only
 * Cloud Photos' own traffic goes through it, so your Wi-Fi provider can't even see that you're
 * talking to iCloud; optionally the whole device.
 */
class VpnManager(
    private val app: Application,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    /** Called after the route changes, so open connections are re-made through (or outside) the tunnel. */
    private val onRouteChanged: () -> Unit,
) {
    private val _state = MutableStateFlow<VpnState>(VpnState.Off)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val accountFile = SecureFile(File(app.filesDir, "vpn/account.bin"))
    private val listFile = File(app.filesDir, "vpn/servers.json")
    private val _account = MutableStateFlow(accountFile.read()?.let { ProtonAccount.fromJson(it) })
    val account: StateFlow<ProtonAccount?> = _account.asStateFlow()

    val geo: Geo by lazy {
        Geo(
            app.assets.open("vpn/zone1970.tab").bufferedReader().use { it.readText() },
            app.assets.open("vpn/iso3166.tab").bufferedReader().use { it.readText() },
        )
    }

    @Volatile private var loaded: Pair<Long, List<VpnServer>>? = null
    val servers: List<VpnServer> get() = list().second
    val listTimestamp: Long get() = list().first

    private val backend by lazy { GoBackend(app) }
    private val tunnel = object : Tunnel {
        override fun getName() = "cloudphotos"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && _state.value is VpnState.Connected) {
                _state.value = VpnState.Off
                onRouteChanged()
            }
        }
    }
    private val mutex = Mutex()
    private var disconnectJob: Job? = null
    private var permissionAnswer: CompletableDeferred<Boolean>? = null

    // ---------- servers ----------

    @Synchronized
    private fun list(): Pair<Long, List<VpnServer>> {
        loaded?.let { return it }
        val fresh = runCatching { if (listFile.exists()) ServerList.parseBundled(listFile.readText()) else null }.getOrNull()
        val bundled = app.assets.open("vpn/proton-servers.json").bufferedReader().use { ServerList.parseBundled(it.readText()) }
        val pick = if (fresh != null && fresh.first >= bundled.first && fresh.second.size > 100) fresh else bundled
        loaded = pick
        return pick
    }

    /** Fetch a fresher server list (weekly, or on request). */
    suspend fun refreshServers(http: OkHttpClient, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val ageDays = (System.currentTimeMillis() / 1000 - listTimestamp) / 86400
        if (!force && ageDays < 7) return@withContext false
        runCatching {
            http.newCall(Request.Builder().url(ServerList.REMOTE_URL).header("User-Agent", "CloudPhotos").build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching false
                val (ts, list) = ServerList.parseGluetun(r.body?.string().orEmpty())
                if (list.size < 100) return@runCatching false
                listFile.parentFile?.mkdirs()
                listFile.writeText(ServerList.toBundled(ts, list))
                synchronized(this@VpnManager) { loaded = ts to list }
                true
            }
        }.getOrDefault(false)
    }

    /** Your rough location from the phone's time zone (no location permission, no network). */
    fun myLocation(): LatLon? {
        val id = TimeZone.getDefault().id
        geo.forTimeZone(id)?.let { return it }
        val region = runCatching { android.icu.util.TimeZone.getRegion(id) }.getOrNull()
        return region?.let { geo.forCountryCode(it) }
    }

    fun closestFreeCountry(): String? = ServerPicker.closestCountry(servers, geo, myLocation(), freeOnly = true)

    fun closestCountry(): String? = ServerPicker.closestCountry(servers, geo, myLocation(), freeOnly = settings.vpnFreeOnly)

    /** The country to use: your choice, or the closest free one. */
    fun targetCountry(): String? = settings.vpnCountry.ifEmpty { null } ?: closestFreeCountry()

    // ---------- account ----------

    fun importAccount(text: String): Result<ProtonAccount> {
        val parsed = ProtonAccount.parse(text, servers)
        parsed.onSuccess {
            accountFile.write(it.toJson())
            _account.value = it
        }
        return parsed
    }

    fun removeAccount() {
        scope.launch {
            disconnect()
            accountFile.delete()
            _account.value = null
        }
    }

    // ---------- connecting ----------

    fun permissionIntent(): Intent? = VpnService.prepare(app)

    /** The UI's answer from Android's "Connection request" dialog. */
    fun onPermissionResult(granted: Boolean) {
        permissionAnswer?.complete(granted)
    }

    fun connectAsync() {
        scope.launch { connect() }
    }

    /** Connects (trying a few servers in the country). True once traffic flows through Proton. */
    suspend fun connect(): Boolean = mutex.withLock {
        disconnectJob?.cancel()
        val acc = _account.value ?: run { _state.value = VpnState.Off; return false }
        val country = targetCountry() ?: run { _state.value = VpnState.Failed("No Proton servers known."); return false }
        (_state.value as? VpnState.Connected)?.let { if (it.server.country == country) return true }

        if (permissionIntent() != null) {
            val answer = CompletableDeferred<Boolean>()
            permissionAnswer = answer
            _state.value = VpnState.NeedsPermission
            val granted = withTimeoutOrNull(120_000) { answer.await() } ?: false
            permissionAnswer = null
            if (!granted || permissionIntent() != null) {
                _state.value = VpnState.Failed("Android's VPN permission wasn't given.")
                return false
            }
        }

        val countryHasFree = servers.any { it.country == country && it.free }
        val candidates = ServerPicker.candidates(servers, country, freeOnly = settings.vpnFreeOnly && countryHasFree).take(4)
        if (candidates.isEmpty()) {
            _state.value = VpnState.Failed("No ${if (settings.vpnFreeOnly) "free " else ""}Proton servers in $country.")
            return false
        }
        var lastError: String? = null
        candidates.forEachIndexed { i, server ->
            _state.value = VpnState.Connecting(country, i + 1)
            val ok = try {
                withContext(Dispatchers.IO) {
                    backend.setState(tunnel, Tunnel.State.UP, config(acc, server))
                    waitForHandshake(server)
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                false
            }
            if (ok) {
                _state.value = VpnState.Connected(server, System.currentTimeMillis())
                onRouteChanged()
                return true
            }
        }
        runCatching { withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) } }
        onRouteChanged()
        _state.value = VpnState.Failed(
            "Couldn't connect to Proton VPN in $country" + (lastError?.let { " ($it)" } ?: "") +
                ". Check the key is from your Proton account and that your plan includes this country."
        )
        return false
    }

    suspend fun disconnect() {
        mutex.withLock {
            disconnectJob?.cancel()
            runCatching { withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) } }
            if (_state.value !is VpnState.Off) {
                _state.value = VpnState.Off
                onRouteChanged()
            }
        }
    }

    fun disconnectAsync() {
        scope.launch { disconnect() }
    }

    private fun config(acc: ProtonAccount, server: VpnServer): Config {
        val iface = Interface.Builder()
            .parsePrivateKey(acc.privateKey)
            .parseAddresses(acc.address)
            .parseDnsServers(acc.dns)
            .apply { if (!settings.vpnWholeDevice) includeApplication(app.packageName) }
            .build()
        val peer = Peer.Builder()
            .parsePublicKey(server.publicKey)
            .parseEndpoint("${server.ip}:51820")
            // IPv6 goes into the tunnel too (and stops there), so nothing leaks around it.
            .parseAllowedIPs("0.0.0.0/0, ::/0")
            .setPersistentKeepalive(25)
            .build()
        return Config.Builder().setInterface(iface).addPeer(peer).build()
    }

    /** WireGuard is silent until the server answers: wait for the first handshake. */
    private suspend fun waitForHandshake(server: VpnServer): Boolean {
        val key = com.wireguard.crypto.Key.fromBase64(server.publicKey)
        repeat(40) {
            val stats = runCatching { backend.getStatistics(tunnel) }.getOrNull()
            val at = stats?.peer(key)?.latestHandshakeEpochMillis() ?: 0L
            if (at > 0) return true
            delay(250)
        }
        return false
    }

    // ---------- app open / close ----------

    fun shouldAutoConnect() = _account.value != null && settings.vpnAutoConnect

    fun onAppOpened() {
        disconnectJob?.cancel()
        if (shouldAutoConnect() && _state.value !is VpnState.Connected && _state.value !is VpnState.Connecting &&
            _state.value !is VpnState.NeedsPermission
        ) connectAsync()
    }

    fun onAppLeft() {
        if (!settings.vpnAutoDisconnect) return
        val s = _state.value
        if (s is VpnState.Connected || s is VpnState.Connecting) {
            disconnectJob?.cancel()
            disconnectJob = scope.launch {
                delay(4_000)
                disconnect()
            }
        }
    }

    /** With "Only through the VPN" on, network requests wait here until the tunnel is up. */
    fun gateBlocking(timeoutMs: Long = 25_000): Boolean {
        if (!settings.vpnRequire || _account.value == null) return true
        if (_state.value is VpnState.Connected) return true
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (_state.value is VpnState.Connected) return true
            if (_state.value is VpnState.Failed || _state.value is VpnState.Off) return false
            Thread.sleep(100)
        }
        return false
    }
}
