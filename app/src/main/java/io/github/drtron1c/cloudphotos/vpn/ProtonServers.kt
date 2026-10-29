package io.github.drtron1c.cloudphotos.vpn

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One Proton VPN WireGuard server. */
data class VpnServer(
    val country: String,
    val city: String,
    val name: String,
    val host: String,
    val ip: String,
    val publicKey: String,
    val free: Boolean,
)

/**
 * Proton VPN's WireGuard servers. A copy ships with the app and a fresher one is fetched from
 * the open-source gluetun-servers project (MIT), which tracks Proton's list. No Proton login is
 * needed for the list; your own WireGuard key works on every server your plan allows.
 */
object ServerList {
    const val REMOTE_URL = "https://raw.githubusercontent.com/qdm12/gluetun-servers/main/pkg/servers/protonvpn.json"

    /** The compact bundled form: {"timestamp":…, "servers":[[country, city, name, host, ip, key, free], …]}. */
    fun parseBundled(text: String): Pair<Long, List<VpnServer>> {
        val root = JSONObject(text)
        val arr = root.getJSONArray("servers")
        val list = (0 until arr.length()).map { i ->
            val a = arr.getJSONArray(i)
            VpnServer(a.getString(0), a.optString(1), a.optString(2), a.optString(3), a.getString(4), a.getString(5), a.optInt(6) == 1)
        }
        return root.optLong("timestamp") to list
    }

    /** gluetun-servers' protonvpn.json. */
    fun parseGluetun(text: String): Pair<Long, List<VpnServer>> {
        val root = JSONObject(text)
        val arr = root.getJSONArray("servers")
        val list = ArrayList<VpnServer>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("vpn") != "wireguard") continue
            val key = o.optString("wgpubkey")
            val ip = o.optJSONArray("ips")?.optString(0).orEmpty()
            if (key.isEmpty() || ip.isEmpty()) continue
            list += VpnServer(
                o.optString("country"), o.optString("city"), o.optString("server_name"), o.optString("hostname"),
                ip, key, o.optBoolean("free"),
            )
        }
        return root.optLong("timestamp") to list
    }

    fun toBundled(timestamp: Long, servers: List<VpnServer>): String {
        val arr = JSONArray()
        for (s in servers) arr.put(JSONArray().put(s.country).put(s.city).put(s.name).put(s.host).put(s.ip).put(s.publicKey).put(if (s.free) 1 else 0))
        return JSONObject().put("timestamp", timestamp).put("servers", arr).toString()
    }
}

data class LatLon(val lat: Double, val lon: Double) {
    fun distanceKm(o: LatLon): Double {
        val r = 6371.0
        val dLat = Math.toRadians(o.lat - lat)
        val dLon = Math.toRadians(o.lon - lon)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat)) * cos(Math.toRadians(o.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}

/**
 * Where things are, from the time-zone database (zone1970.tab + iso3166.tab, bundled): your
 * phone's time zone gives your rough location without any location permission or network call.
 */
class Geo(zoneTab: String, isoTab: String) {
    private class Zone(val codes: List<String>, val at: LatLon, val id: String)

    private val zones: List<Zone> = zoneTab.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) null else parseCoord(parts[1])?.let { Zone(parts[0].split(','), it, parts[2]) }
        }.toList()

    private val codeByName: Map<String, String>
    val nameByCode: Map<String, String>

    init {
        val byName = HashMap<String, String>()
        val byCode = HashMap<String, String>()
        isoTab.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val parts = line.split('\t')
            if (parts.size >= 2) {
                byName[norm(parts[1])] = parts[0]
                byCode[parts[0]] = parts[1]
            }
        }
        // Names used by Proton / gluetun that differ from the ISO list.
        for ((alias, code) in ALIASES) byName[norm(alias)] = code
        codeByName = byName
        nameByCode = byCode
    }

    fun countryCode(country: String): String? = codeByName[norm(country)]

    fun forTimeZone(id: String): LatLon? = zones.firstOrNull { it.id == id }?.at

    fun forCountryCode(code: String): LatLon? = zones.firstOrNull { code in it.codes }?.at

    fun forCountry(country: String): LatLon? = countryCode(country)?.let { forCountryCode(it) }

    /** A server's city if the time-zone list knows it (e.g. Toronto, Los_Angeles), else its country. */
    fun forCity(city: String, country: String): LatLon? {
        val c = norm(city)
        if (c.isNotEmpty()) {
            zones.firstOrNull { norm(it.id.substringAfterLast('/').replace('_', ' ')) == c }?.let { return it.at }
        }
        return forCountry(country)
    }

    companion object {
        private val ALIASES = mapOf(
            "United States" to "US", "USA" to "US", "United Kingdom" to "GB", "UK" to "GB", "South Korea" to "KR",
            "Korea" to "KR", "Russia" to "RU", "Czech Republic" to "CZ", "Czechia" to "CZ", "Vietnam" to "VN",
            "Taiwan" to "TW", "Hong Kong" to "HK", "Moldova" to "MD", "Macedonia" to "MK", "North Macedonia" to "MK",
            "Bolivia" to "BO", "Venezuela" to "VE", "Iran" to "IR", "Syria" to "SY", "Tanzania" to "TZ",
            "Laos" to "LA", "Brunei" to "BN", "Palestine" to "PS", "Ivory Coast" to "CI", "Turkey" to "TR",
            "Türkiye" to "TR", "Bosnia and Herzegovina" to "BA", "UAE" to "AE", "United Arab Emirates" to "AE",
        )

        private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z]"), "")

        /** "+3758+02343" or "+404251-0740023" → degrees. */
        internal fun parseCoord(s: String): LatLon? {
            val m = Regex("([+-])(\\d+)([+-])(\\d+)").matchEntire(s) ?: return null
            fun deg(sign: String, digits: String, degDigits: Int): Double {
                val d = digits.substring(0, degDigits).toDouble()
                val min = digits.substring(degDigits, degDigits + 2).toDouble()
                val sec = if (digits.length > degDigits + 2) digits.substring(degDigits + 2).toDouble() else 0.0
                val v = d + min / 60 + sec / 3600
                return if (sign == "-") -v else v
            }
            val (s1, latD, s2, lonD) = m.destructured
            return LatLon(deg(s1, latD, 2), deg(s2, lonD, 3))
        }

        /** 🇷🇴 for "RO". */
        fun flag(code: String?): String {
            if (code == null || code.length != 2) return "🌐"
            val a = Character.toChars(0x1F1E6 + (code[0].uppercaseChar() - 'A'))
            val b = Character.toChars(0x1F1E6 + (code[1].uppercaseChar() - 'A'))
            return String(a) + String(b)
        }
    }
}

/** Picks servers: the closest free country to you, or a random server in a chosen country. */
object ServerPicker {
    /** Countries with at least one server, alphabetically, with whether any of their servers are free. */
    fun countries(servers: List<VpnServer>, freeOnly: Boolean): List<Pair<String, Boolean>> =
        servers.groupBy { it.country }
            .map { (c, list) -> c to list.any { it.free } }
            .filter { !freeOnly || it.second }
            .sortedBy { it.first }

    /** Nearest country (by its nearest server city) among free servers — or all servers when [freeOnly] is false. */
    fun closestCountry(servers: List<VpnServer>, geo: Geo, me: LatLon?, freeOnly: Boolean = true): String? {
        val pool = servers.filter { !freeOnly || it.free }
        if (pool.isEmpty()) return null
        if (me == null) return pool.first().country
        return pool.minByOrNull { s -> geo.forCity(s.city, s.country)?.distanceKm(me) ?: Double.MAX_VALUE }?.country
    }

    /** Servers to try for [country], in order (random, so load spreads out). */
    fun candidates(servers: List<VpnServer>, country: String, freeOnly: Boolean, random: kotlin.random.Random = kotlin.random.Random): List<VpnServer> =
        servers.filter { it.country == country && (!freeOnly || it.free) }.shuffled(random)
}
