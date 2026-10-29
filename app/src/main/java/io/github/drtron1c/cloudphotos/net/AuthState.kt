package io.github.drtron1c.cloudphotos.net

import org.json.JSONObject
import java.util.UUID

/**
 * Everything Apple hands back during sign-in that later requests must echo:
 * session headers (scnt, session id), the long-lived session + trust tokens,
 * and the account's service URLs. Persisted encrypted.
 */
class AuthState(private val store: SecureFile) {
    @Volatile var clientId: String = UUID.randomUUID().toString().lowercase()
    @Volatile var appleId: String = ""
    @Volatile var accountCountry: String? = null
    @Volatile var sessionId: String? = null
    @Volatile var scnt: String? = null
    @Volatile var authAttributes: String? = null
    @Volatile var sessionToken: String? = null
    @Volatile var trustToken: String? = null
    @Volatile var dsid: String? = null
    @Volatile var fullName: String? = null
    @Volatile var ckDatabaseUrl: String? = null
    @Volatile var photosUploadUrl: String? = null
    @Volatile var sharedStreamsUrl: String? = null
    @Volatile var savedPassword: String? = null

    init {
        load()
    }

    @Synchronized
    private fun load() {
        val text = store.read() ?: return
        try {
            val o = JSONObject(text)
            clientId = o.optString("clientId").ifEmpty { clientId }
            appleId = o.optString("appleId")
            accountCountry = o.optNullable("accountCountry")
            sessionId = o.optNullable("sessionId")
            scnt = o.optNullable("scnt")
            authAttributes = o.optNullable("authAttributes")
            sessionToken = o.optNullable("sessionToken")
            trustToken = o.optNullable("trustToken")
            dsid = o.optNullable("dsid")
            fullName = o.optNullable("fullName")
            ckDatabaseUrl = o.optNullable("ckDatabaseUrl")
            photosUploadUrl = o.optNullable("photosUploadUrl")
            sharedStreamsUrl = o.optNullable("sharedStreamsUrl")
            savedPassword = o.optNullable("savedPassword")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun save() {
        val o = JSONObject()
        o.put("clientId", clientId)
        o.put("appleId", appleId)
        o.putOpt("accountCountry", accountCountry)
        o.putOpt("sessionId", sessionId)
        o.putOpt("scnt", scnt)
        o.putOpt("authAttributes", authAttributes)
        o.putOpt("sessionToken", sessionToken)
        o.putOpt("trustToken", trustToken)
        o.putOpt("dsid", dsid)
        o.putOpt("fullName", fullName)
        o.putOpt("ckDatabaseUrl", ckDatabaseUrl)
        o.putOpt("photosUploadUrl", photosUploadUrl)
        o.putOpt("sharedStreamsUrl", sharedStreamsUrl)
        o.putOpt("savedPassword", savedPassword)
        store.write(o.toString())
    }

    /** Forget the signed-in session but keep the Apple ID (and trust token if [keepTrust]) for next time. */
    @Synchronized
    fun clearSession(keepTrust: Boolean) {
        sessionId = null
        scnt = null
        authAttributes = null
        sessionToken = null
        dsid = null
        ckDatabaseUrl = null
        photosUploadUrl = null
        sharedStreamsUrl = null
        if (!keepTrust) {
            trustToken = null
            savedPassword = null
            accountCountry = null
            fullName = null
        }
        save()
    }

    /** Copy Apple's session headers off any response. */
    fun capture(headers: okhttp3.Headers) {
        var changed = false
        fun take(name: String, set: (String) -> Unit) {
            val v = headers[name]
            if (!v.isNullOrEmpty()) {
                set(v); changed = true
            }
        }
        take("X-Apple-ID-Account-Country") { accountCountry = it }
        take("X-Apple-ID-Session-Id") { sessionId = it }
        take("X-Apple-Auth-Attributes") { authAttributes = it }
        take("X-Apple-Session-Token") { sessionToken = it }
        take("X-Apple-TwoSV-Trust-Token") { trustToken = it }
        take("scnt") { scnt = it }
        if (changed) save()
    }

    private fun JSONObject.optNullable(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null
}
