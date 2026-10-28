package io.github.drtron1c.cloudphotos.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cookie jar that keeps every cookie (session cookies included, like a browser that is
 * never closed) in an encrypted file, so the iCloud web session survives app restarts.
 */
class PersistentCookieJar(private val store: SecureFile) : CookieJar {
    private val cookies = ArrayList<Cookie>()

    init {
        load()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > now) this.cookies.add(c)
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val expired = cookies.removeAll { it.expiresAt <= now }
        if (expired) persist()
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun has(name: String): Boolean = cookies.any { it.name == name && it.expiresAt > System.currentTimeMillis() }

    @Synchronized
    fun clear() {
        cookies.clear()
        store.delete()
    }

    private fun persist() {
        val arr = JSONArray()
        for (c in cookies) {
            arr.put(
                JSONObject()
                    .put("n", c.name)
                    .put("v", c.value)
                    .put("e", c.expiresAt)
                    .put("d", c.domain)
                    .put("p", c.path)
                    .put("s", c.secure)
                    .put("h", c.httpOnly)
                    .put("o", c.hostOnly)
            )
        }
        store.write(arr.toString())
    }

    private fun load() {
        val text = store.read() ?: return
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = Cookie.Builder()
                    .name(o.getString("n"))
                    .value(o.getString("v"))
                    .expiresAt(o.getLong("e"))
                    .path(o.getString("p"))
                if (o.optBoolean("o")) b.hostOnlyDomain(o.getString("d")) else b.domain(o.getString("d"))
                if (o.optBoolean("s")) b.secure()
                if (o.optBoolean("h")) b.httpOnly()
                cookies.add(b.build())
            }
        } catch (_: Exception) {
            cookies.clear()
        }
    }
}
