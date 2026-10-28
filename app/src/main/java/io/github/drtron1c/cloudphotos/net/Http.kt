package io.github.drtron1c.cloudphotos.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
const val ICLOUD_HOME = "https://www.icloud.com"

/** A finished HTTP exchange with its body already read. */
class HttpResult(val code: Int, val headers: Headers, val body: String) {
    val ok get() = code in 200..299

    fun json(): JSONObject? = try {
        if (body.isBlank()) null else JSONObject(body)
    } catch (_: Exception) {
        null
    }

    fun jsonArray(): JSONArray? = try {
        if (body.isBlank()) null else JSONArray(body)
    } catch (_: Exception) {
        null
    }

    /** Best human-readable error Apple put in the body, if any. */
    fun appleError(): String? {
        val o = json() ?: return null
        o.optJSONArray("serviceErrors")?.optJSONObject(0)?.let { e ->
            val m = e.optString("message").ifEmpty { e.optString("title") }
            if (m.isNotEmpty()) return m
        }
        for (k in listOf("errorMessage", "reason", "errorReason", "message")) {
            val v = o.optString(k)
            if (v.isNotEmpty() && v != "null") return v
        }
        if (o.opt("error") is String) return o.optString("error")
        return null
    }
}

class HttpException(val code: Int, message: String) : IOException(message)

/** The iCloud session is no longer accepted; the caller should sign in again. */
class SessionExpiredException(message: String = "Your iCloud session has expired") : IOException(message)

fun buildHttpClient(cookieJar: PersistentCookieJar, authState: AuthState): OkHttpClient =
    OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val b = req.newBuilder()
            if (req.header("User-Agent") == null) b.header("User-Agent", USER_AGENT)
            val host = req.url.host
            if (host.endsWith("icloud.com") || host.endsWith("apple.com") || host.endsWith("icloud-content.com")) {
                if (req.header("Origin") == null) b.header("Origin", ICLOUD_HOME)
                if (req.header("Referer") == null) b.header("Referer", "$ICLOUD_HOME/")
            }
            val res = chain.proceed(b.build())
            if (host.endsWith("apple.com") || host.startsWith("setup.icloud")) authState.capture(res.headers)
            res
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

private val JSON_TYPE = "application/json".toMediaType()
private val TEXT_TYPE = "text/plain".toMediaType()

suspend fun OkHttpClient.send(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    jsonBody: Any? = null,
    rawBody: String? = null,
    textPlain: Boolean = false,
): HttpResult = withContext(Dispatchers.IO) {
    val type = if (textPlain) TEXT_TYPE else JSON_TYPE
    // Byte bodies keep the Content-Type exactly as given (String bodies get "; charset=utf-8" appended).
    val body = when {
        rawBody != null -> rawBody.toByteArray().toRequestBody(type)
        jsonBody != null -> jsonBody.toString().replace("\\/", "/").toByteArray().toRequestBody(type)
        method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(type)
        else -> null
    }
    val rb = Request.Builder().url(url.toHttpUrl()).method(method, body)
    headers.forEach { (k, v) -> rb.header(k, v) }
    newCall(rb.build()).execute().use { res ->
        HttpResult(res.code, res.headers, res.body?.string() ?: "")
    }
}
