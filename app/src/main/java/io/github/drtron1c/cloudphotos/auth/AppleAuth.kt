package io.github.drtron1c.cloudphotos.auth

import android.util.Base64
import io.github.drtron1c.cloudphotos.net.AuthState
import io.github.drtron1c.cloudphotos.net.HttpResult
import io.github.drtron1c.cloudphotos.net.ICLOUD_HOME
import io.github.drtron1c.cloudphotos.net.PersistentCookieJar
import io.github.drtron1c.cloudphotos.net.SessionExpiredException
import io.github.drtron1c.cloudphotos.net.USER_AGENT
import io.github.drtron1c.cloudphotos.net.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class TrustedPhone(val id: Int, val number: String, val pushMode: String?, val nonFteu: Boolean?)

sealed interface CodeDelivery {
    data object Devices : CodeDelivery
    data class Sms(val phone: TrustedPhone) : CodeDelivery
}

data class TwoFactorInfo(
    val phones: List<TrustedPhone>,
    val hasTrustedDevices: Boolean,
    val codeLength: Int,
    val delivery: CodeDelivery,
)

sealed interface AuthOutcome {
    data object SignedIn : AuthOutcome
    data class NeedsTwoFactor(val info: TwoFactorInfo) : AuthOutcome
}

class AuthException(message: String) : Exception(message)

/**
 * Apple Account sign-in for iCloud on the web: SRP password proof against idmsa.apple.com,
 * two-factor verification, session trust, then accountLogin on setup.icloud.com which
 * returns the per-account service URLs (CloudKit for Photos, the upload host, ...).
 */
class AppleAuth(
    private val http: OkHttpClient,
    private val state: AuthState,
    private val cookies: PersistentCookieJar,
) {
    companion object {
        const val IDMSA = "https://idmsa.apple.com"
        const val AUTH = "$IDMSA/appleauth/auth"
        const val SETUP = "https://setup.icloud.com/setup/ws/1"
        const val WIDGET_KEY = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"
        const val CLIENT_BUILD = "2534Project66"
        const val CLIENT_MASTERING = "2534B22"
        private const val FD_CLIENT_INFO =
            "{\"U\":\"$USER_AGENT\",\"L\":\"en-US\",\"Z\":\"GMT+00:00\",\"V\":\"1.1\",\"F\":\"\"}"
    }

    val isSignedIn get() = state.dsid != null && state.ckDatabaseUrl != null

    fun setupParams(): String {
        val sb = StringBuilder("clientBuildNumber=$CLIENT_BUILD&clientMasteringNumber=$CLIENT_MASTERING&clientId=${state.clientId}")
        state.dsid?.let { sb.append("&dsid=").append(it) }
        return sb.toString()
    }

    private fun authHeaders(accept: String = "application/json, text/javascript"): Map<String, String> {
        val h = linkedMapOf(
            "Accept" to accept,
            "X-Apple-OAuth-Client-Id" to WIDGET_KEY,
            "X-Apple-OAuth-Client-Type" to "firstPartyAuth",
            "X-Apple-OAuth-Redirect-URI" to ICLOUD_HOME,
            "X-Apple-OAuth-Require-Grant-Code" to "true",
            "X-Apple-OAuth-Response-Mode" to "web_message",
            "X-Apple-OAuth-Response-Type" to "code",
            "X-Apple-OAuth-State" to state.clientId,
            "X-Apple-Widget-Key" to WIDGET_KEY,
            "X-Apple-Frame-Id" to state.clientId,
            "X-Apple-FD-Client-Info" to FD_CLIENT_INFO,
            "Referer" to IDMSA,
        )
        state.scnt?.let { h["scnt"] = it }
        state.sessionId?.let { h["X-Apple-ID-Session-Id"] = it }
        state.authAttributes?.let { h["X-Apple-Auth-Attributes"] = it }
        return h
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.DEFAULT)

    /** Full password sign-in. */
    suspend fun signIn(appleIdInput: String, password: String): AuthOutcome {
        val appleId = appleIdInput.trim()
        if (!appleId.equals(state.appleId, ignoreCase = true)) {
            cookies.clear()
            state.clearSession(keepTrust = false)
        } else {
            state.clearSession(keepTrust = true)
        }
        state.appleId = appleId
        state.save()

        // 1. Load the sign-in frame like the website does (sets Apple's session cookies / scnt).
        val q = listOf(
            "frame_id" to state.clientId,
            "skVersion" to "7",
            "iframeid" to state.clientId,
            "client_id" to WIDGET_KEY,
            "response_type" to "code",
            "redirect_uri" to ICLOUD_HOME,
            "response_mode" to "web_message",
            "state" to state.clientId,
            "authVersion" to "latest",
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        runCatching { http.send("GET", "$AUTH/authorize/signin?$q", mapOf("Accept" to "text/html")) }

        // 2. SRP: send A, receive salt + B.
        val srp = AppleSrp(appleId)
        val initBody = JSONObject()
            .put("a", b64(srp.publicA()))
            .put("accountName", appleId)
            .put("protocols", JSONArray().put("s2k").put("s2k_fo"))
        val init = http.send("POST", "$AUTH/signin/init", authHeaders(), jsonBody = initBody)
        if (!init.ok) throw AuthException(init.appleError() ?: "Couldn't start sign-in (error ${init.code}). Check your Apple Account email.")
        val ij = init.json() ?: throw AuthException("Unexpected reply from Apple while signing in.")
        val salt = unb64(ij.getString("salt"))
        val serverB = unb64(ij.getString("b"))
        val c = ij.getString("c")
        val iterations = ij.getInt("iteration")
        val protocol = ij.optString("protocol", "s2k")

        // 3. Prove the password without sending it.
        val key = withContext(Dispatchers.Default) {
            val k = AppleSrp.derivePassword(password, protocol, salt, iterations)
            srp.processChallenge(salt, serverB, k)
            k
        }
        if (key.isEmpty()) throw AuthException("Sign-in failed.")
        val trust = JSONArray()
        state.trustToken?.let { trust.put(it) }
        val completeBody = JSONObject()
            .put("accountName", appleId)
            .put("c", c)
            .put("m1", b64(srp.m1()))
            .put("m2", b64(srp.m2()))
            .put("rememberMe", true)
            .put("trustTokens", trust)
        val res = http.send("POST", "$AUTH/signin/complete?isRememberMeEnabled=true", authHeaders(), jsonBody = completeBody)
        when (res.code) {
            in 200..299 -> Unit
            409 -> return AuthOutcome.NeedsTwoFactor(startTwoFactor())
            412 -> repairComplete()
            401, 403 -> throw AuthException(res.appleError() ?: "Your Apple Account email or password was incorrect.")
            else -> throw AuthException(res.appleError() ?: "Sign-in failed (error ${res.code}).")
        }

        val data = accountLogin()
        return if (isTrusted(data)) AuthOutcome.SignedIn else AuthOutcome.NeedsTwoFactor(startTwoFactor())
    }

    private suspend fun repairComplete() {
        runCatching { http.send("POST", "$AUTH/repair/complete", authHeaders(), jsonBody = JSONObject()) }
    }

    private fun parsePhones(o: JSONObject?): List<TrustedPhone> {
        val out = ArrayList<TrustedPhone>()
        fun addFrom(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                if (!p.has("id")) continue
                val id = p.optInt("id")
                if (out.any { it.id == id }) continue
                val number = p.optString("numberWithDialCode").ifEmpty { p.optString("obfuscatedNumber") }
                    .replace('•', '*')
                out += TrustedPhone(
                    id = id,
                    number = number,
                    pushMode = p.optString("pushMode").ifEmpty { null },
                    nonFteu = if (p.has("nonFTEU")) p.optBoolean("nonFTEU") else null,
                )
            }
        }
        if (o == null) return out
        o.optJSONObject("trustedPhoneNumber")?.let { addFrom(JSONArray().put(it)) }
        addFrom(o.optJSONArray("trustedPhoneNumbers"))
        o.optJSONObject("phoneNumberVerification")?.let {
            it.optJSONObject("trustedPhoneNumber")?.let { p -> addFrom(JSONArray().put(p)) }
            addFrom(it.optJSONArray("trustedPhoneNumbers"))
        }
        return out
    }

    /** Ask Apple which second factors exist and send the first code. */
    private suspend fun startTwoFactor(): TwoFactorInfo {
        val r = http.send("GET", AUTH, authHeaders(accept = "application/json"))
        val j = r.json()
        val phones = parsePhones(j)
        val deviceCount = j?.optInt("trustedDeviceCount", -1) ?: -1
        val noDevices = j?.optBoolean("noTrustedDevices", false) ?: false
        val hasDevices = if (deviceCount >= 0) deviceCount > 0 && !noDevices else !noDevices
        val length = j?.optJSONObject("securityCode")?.optInt("length", 6)?.takeIf { it in 4..8 } ?: 6

        val delivery: CodeDelivery = if (hasDevices || phones.isEmpty()) {
            pushToDevices()
            CodeDelivery.Devices
        } else {
            sendSms(phones.first())
            CodeDelivery.Sms(phones.first())
        }
        return TwoFactorInfo(phones, hasDevices, length, delivery)
    }

    /** Pops the "Allow sign in" prompt + code on the user's iPhone / iPad / Mac. */
    suspend fun pushToDevices() {
        runCatching {
            http.send("PUT", "$AUTH/verify/trusteddevice/securitycode", authHeaders(accept = "application/json"))
        }
    }

    private fun phonePayload(p: TrustedPhone): JSONObject {
        val o = JSONObject().put("id", p.id)
        p.nonFteu?.let { o.put("nonFTEU", it) }
        return o
    }

    suspend fun sendSms(phone: TrustedPhone) {
        val body = JSONObject().put("phoneNumber", phonePayload(phone)).put("mode", phone.pushMode ?: "sms")
        val r = http.send("PUT", "$AUTH/verify/phone", authHeaders(accept = "application/json"), jsonBody = body)
        if (!r.ok && r.code != 423) throw AuthException(r.appleError() ?: "Couldn't send a text message (error ${r.code}).")
    }

    private fun codeAccepted(r: HttpResult): Boolean {
        if (r.ok) return true
        if (r.code == 409 && r.json()?.optJSONObject("securityCode")?.optBoolean("valid") == true) return true
        if (r.code == 412 && !r.headers["X-Apple-Repair-Session-Token"].isNullOrEmpty()) return true
        return false
    }

    /** Check the 2FA code, trust this "browser", and finish the iCloud login. */
    suspend fun verifyCode(code: String, delivery: CodeDelivery) {
        val r = when (delivery) {
            is CodeDelivery.Devices -> http.send(
                "POST", "$AUTH/verify/trusteddevice/securitycode", authHeaders(accept = "application/json"),
                jsonBody = JSONObject().put("securityCode", JSONObject().put("code", code)),
            )
            is CodeDelivery.Sms -> http.send(
                "POST", "$AUTH/verify/phone/securitycode", authHeaders(accept = "application/json, text/plain"),
                jsonBody = JSONObject()
                    .put("phoneNumber", phonePayload(delivery.phone))
                    .put("securityCode", JSONObject().put("code", code))
                    .put("mode", delivery.phone.pushMode ?: "sms"),
            )
        }
        if (!codeAccepted(r)) {
            throw AuthException(
                if (r.code == 400 || r.code == 401 || r.code == 409) "Incorrect verification code."
                else r.appleError() ?: "Couldn't verify the code (error ${r.code})."
            )
        }
        if (r.code == 412) repairComplete()

        // Ask Apple to trust this session so the next sign-in skips 2FA.
        http.send("GET", "$AUTH/2sv/trust", authHeaders())
        val data = accountLogin()
        if (!isTrusted(data)) throw AuthException("Apple didn't confirm this sign-in. Please try again.")
    }

    private fun isTrusted(data: JSONObject): Boolean {
        if (data.optBoolean("hsaTrustedBrowser", false)) return true
        val hsa = data.optJSONObject("dsInfo")?.optInt("hsaVersion", 0) ?: 0
        return hsa < 2 && !data.optBoolean("hsaChallengeRequired", false)
    }

    /** Exchange the idmsa session token for an iCloud web session. */
    private suspend fun accountLogin(): JSONObject {
        val token = state.sessionToken ?: throw AuthException("Apple didn't return a session. Please sign in again.")
        val body = JSONObject()
            .put("accountCountryCode", state.accountCountry ?: JSONObject.NULL)
            .put("dsWebAuthToken", token)
            .put("extended_login", true)
            .put("trustToken", state.trustToken ?: "")
        val r = http.send("POST", "$SETUP/accountLogin", jsonBody = body)
        val j = r.json()
        if (!r.ok || j == null) throw AuthException(r.appleError() ?: "iCloud login failed (error ${r.code}).")
        if (j.has("domainToUse")) {
            throw AuthException("This Apple Account uses iCloud in China mainland (icloud.com.cn), which isn't supported.")
        }
        applyAccountData(j)
        if (j.optBoolean("termsUpdateNeeded", false)) {
            throw AuthException("Apple needs you to accept updated iCloud terms. Sign in once at icloud.com in a browser, accept them, then try again.")
        }
        return j
    }

    private fun applyAccountData(j: JSONObject) {
        val ds = j.optJSONObject("dsInfo")
        ds?.optString("dsid")?.takeIf { it.isNotEmpty() }?.let { state.dsid = it }
        ds?.optString("fullName")?.takeIf { it.isNotEmpty() }?.let { state.fullName = it }
        val ws = j.optJSONObject("webservices")
        ws?.optJSONObject("ckdatabasews")?.optString("url")?.takeIf { it.isNotEmpty() }?.let { state.ckDatabaseUrl = it }
        ws?.optJSONObject("photosupload")?.optString("url")?.takeIf { it.isNotEmpty() }?.let { state.photosUploadUrl = it }
        state.save()
    }

    /** Try to continue the saved session without asking for anything. */
    suspend fun resume(): Boolean {
        if (state.dsid != null) {
            val r = runCatching { http.send("POST", "$SETUP/validate?${setupParams()}", rawBody = "null") }.getOrNull()
            val j = r?.json()
            if (r != null && r.ok && j != null && j.has("webservices")) {
                applyAccountData(j)
                if (state.ckDatabaseUrl != null) return true
            }
        }
        if (state.sessionToken != null) {
            val data = runCatching { accountLogin() }.getOrNull()
            if (data != null && isTrusted(data) && state.ckDatabaseUrl != null) return true
        }
        return false
    }

    /** Re-sign in with a remembered password (usually no 2FA thanks to the trust token). */
    suspend fun silentSignIn(): AuthOutcome? {
        val pw = state.savedPassword ?: return null
        if (state.appleId.isEmpty()) return null
        return signIn(state.appleId, pw)
    }

    fun rememberPassword(password: String?) {
        state.savedPassword = password
        state.save()
    }

    /**
     * Accounts with Advanced Data Protection must approve web access on a trusted device
     * before Photos data can be read. No-op for everyone else.
     */
    suspend fun ensureWebAccess(onWaiting: (String) -> Unit) {
        val st = http.send("POST", "$SETUP/requestWebAccessState?${setupParams()}", rawBody = "").json() ?: return
        if (!st.optBoolean("isICDRSDisabled", false)) return

        if (!st.optBoolean("isDeviceConsentedForPCS", true)) {
            val consent = http.send("POST", "$SETUP/enableDeviceConsentForPCS?${setupParams()}", rawBody = "").json()
            if (consent?.optBoolean("isDeviceConsentNotificationSent", false) != true) {
                throw AuthException(
                    "Advanced Data Protection is on. On your iPhone open Settings › your name › iCloud and turn on " +
                        "\"Access iCloud Data on the Web\", then try again."
                )
            }
            onWaiting("Advanced Data Protection is on.\nApprove the request on your iPhone, iPad or Mac to let this app see your photos.")
            var consented = false
            for (i in 0 until 60) {
                delay(3000)
                val s = http.send("POST", "$SETUP/requestWebAccessState?${setupParams()}", rawBody = "").json()
                if (s?.optBoolean("isDeviceConsentedForPCS", false) == true) {
                    consented = true
                    break
                }
            }
            if (!consented) throw AuthException("No approval received from your devices. Please try again.")
        }

        onWaiting("Unlocking your photo library…")
        for (attempt in 0 until 20) {
            val r = http.send(
                "POST", "$SETUP/requestPCS?${setupParams()}",
                jsonBody = JSONObject().put("appName", "photos").put("derivedFromUserAction", attempt == 0),
            ).json() ?: throw AuthException("Couldn't unlock Photos.")
            if (r.optString("status") == "success") return
            val msg = r.optString("message")
            if (msg == "Requested the device to upload cookies." || msg == "Cookies not available yet on server.") {
                delay(3000)
            } else {
                throw AuthException("Couldn't unlock Photos: ${msg.ifEmpty { "unknown reason" }}")
            }
        }
        throw AuthException("Timed out waiting for your devices to unlock Photos.")
    }

    suspend fun signOut() {
        runCatching {
            http.send(
                "POST", "$SETUP/logout?${setupParams()}",
                jsonBody = JSONObject().put("trustBrowsers", false).put("allBrowsers", false),
            )
        }
        cookies.clear()
        state.clearSession(keepTrust = false)
    }

    fun requireSession() {
        if (!isSignedIn) throw SessionExpiredException()
    }
}
