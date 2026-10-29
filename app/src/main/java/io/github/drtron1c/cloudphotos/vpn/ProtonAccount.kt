package io.github.drtron1c.cloudphotos.vpn

import org.json.JSONObject
import java.util.Base64

/**
 * Your Proton VPN account, as Proton lets other apps use it: the WireGuard key from a
 * configuration file made at account.protonvpn.com › Downloads › WireGuard. The same key works on
 * every server your plan includes, so the app can pick countries itself.
 */
data class ProtonAccount(
    val privateKey: String,
    val address: String = "10.2.0.2/32",
    val dns: String = "10.2.0.1",
    /** The server the file was made for (shown in Settings). */
    val madeFor: String = "",
    val importedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = JSONObject().put("k", privateKey).put("a", address).put("d", dns).put("m", madeFor).put("t", importedAt).toString()

    companion object {
        fun fromJson(text: String): ProtonAccount? = runCatching {
            val o = JSONObject(text)
            ProtonAccount(o.getString("k"), o.optString("a", "10.2.0.2/32"), o.optString("d", "10.2.0.1"), o.optString("m"), o.optLong("t"))
        }.getOrNull()

        /**
         * Reads a WireGuard configuration (the .conf file, or its text pasted in, or just the
         * private key). Returns null with [error] set to what's wrong.
         */
        fun parse(text: String, servers: List<VpnServer> = emptyList()): Result<ProtonAccount> {
            val trimmed = text.trim()
            if (isKey(trimmed)) return Result.success(ProtonAccount(trimmed))
            var key: String? = null
            var address: String? = null
            var dns: String? = null
            var endpoint: String? = null
            var section = ""
            for (raw in trimmed.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.startsWith("[")) {
                    section = line.lowercase()
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val name = line.substring(0, eq).trim().lowercase()
                val value = line.substring(eq + 1).trim()
                when {
                    section == "[interface]" && name == "privatekey" -> key = value
                    section == "[interface]" && name == "address" ->
                        address = value.split(',').map { it.trim() }.firstOrNull { it.contains('.') } ?: value
                    section == "[interface]" && name == "dns" ->
                        dns = value.split(',').map { it.trim() }.firstOrNull { it.contains('.') } ?: value
                    section == "[peer]" && name == "endpoint" -> endpoint = value.substringBeforeLast(':')
                }
            }
            if (key == null) return Result.failure(IllegalArgumentException("That doesn't look like a WireGuard configuration — no PrivateKey line found."))
            if (!isKey(key)) return Result.failure(IllegalArgumentException("The PrivateKey in that file isn't a valid WireGuard key."))
            val server = servers.firstOrNull { it.ip == endpoint }
            return Result.success(
                ProtonAccount(
                    privateKey = key,
                    address = address ?: "10.2.0.2/32",
                    dns = dns ?: "10.2.0.1",
                    madeFor = server?.let { "${it.name} (${it.country})" } ?: endpoint.orEmpty(),
                )
            )
        }

        fun isKey(s: String): Boolean = runCatching { Base64.getDecoder().decode(s).size == 32 }.getOrDefault(false) && s.length == 44
    }
}
