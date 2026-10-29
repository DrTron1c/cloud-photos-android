package io.github.drtron1c.cloudphotos.update

import org.json.JSONObject

/** What the app needs to know about a published build. */
data class ReleaseInfo(
    /** The build's Android versionCode (the project's CI run number). */
    val versionCode: Int,
    val title: String,
    /** One line about what changed, or blank. */
    val changes: String,
    /** Download address of the APK. */
    val apkUrl: String,
    val sizeBytes: Long,
    /** What the build adds, read from the change-log section in the release notes. */
    val notes: List<ChangeItem> = emptyList(),
)

/** Reads the JSON GitHub returns for `GET /repos/{owner}/{repo}/releases/tags/{tag}`. Pure, so it is unit tested. */
object ReleaseParser {
    const val APK_NAME = "CloudPhotos.apk"
    private val CODE_LINE = Regex("(?im)^\\s*versionCode\\s*[:=]\\s*(\\d+)\\s*$")
    private val BUILD_IN_TITLE = Regex("(?i)\\bbuild\\s*#?\\s*(\\d+)")

    /** Null when the text isn't a release, has no APK, or doesn't say which build it is. */
    fun parse(text: String): ReleaseInfo? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val name = root.optString("name")
        val body = root.optString("body")
        val code = CODE_LINE.find(body)?.groupValues?.get(1)?.toIntOrNull()
            ?: BUILD_IN_TITLE.find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val assetsJson = root.optJSONArray("assets") ?: return null
        val assets = (0 until assetsJson.length()).mapNotNull { assetsJson.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            .filter { it.optString("state", "uploaded") == "uploaded" }
        val apk = assets.firstOrNull { it.optString("name") == APK_NAME } ?: assets.firstOrNull() ?: return null
        val url = apk.optString("browser_download_url").ifEmpty { apk.optString("url") }.ifEmpty { return null }
        return ReleaseInfo(
            versionCode = code,
            title = name.ifBlank { "Cloud Photos build $code" },
            changes = changesLine(body),
            apkUrl = url,
            sizeBytes = apk.optLong("size", 0L),
            notes = Changelog.newestItems(body),
        )
    }

    fun isNewer(remote: Int, installed: Int): Boolean = remote > installed

    /** The first human sentence of the release notes (not the machine line or the change log). */
    internal fun changesLine(body: String): String =
        body.lineSequence()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() && !CODE_LINE.matches(it) && !it.startsWith("Unofficial", ignoreCase = true) &&
                    !it.startsWith("#") && !it.startsWith("- ") && !it.startsWith("* ")
            }
            .firstOrNull()
            ?.take(200)
            .orEmpty()
}
