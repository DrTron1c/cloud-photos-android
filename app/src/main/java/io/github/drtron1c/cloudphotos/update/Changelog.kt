package io.github.drtron1c.cloudphotos.update

/** One feature (or fix) in a build: its name and what it is. */
data class ChangeItem(val title: String, val detail: String)

/** What one build changed. [build] is 0 when the heading doesn't say which build it is. */
data class ChangeEntry(val build: Int, val name: String, val items: List<ChangeItem>)

/**
 * The change log: `app/src/main/assets/changelog.md`, bundled in every build and also copied (newest section only) into the
 * notes of the GitHub release, so the "Update available" banner can say what is coming before it is installed.
 *
 * The file is plain Markdown in one fixed shape, which this reads without regular expressions:
 *
 *     ## Build 62 · optional name
 *     - **Feature name** — what it is, in a sentence or two.
 *       A line indented under it carries on the same item.
 *
 * Newest build first. Pure, so it is unit tested.
 */
object Changelog {
    const val ASSET = "changelog.md"

    /** The most builds' worth of notes the banner lists at once (someone who skipped many updates sees the newest). */
    const val MAX_SHOWN = 5

    fun parse(text: String): List<ChangeEntry> {
        val entries = ArrayList<ChangeEntry>()
        var build = 0
        var name = ""
        var open = false
        var items = ArrayList<ChangeItem>()
        var itemOpen = false
        var title = ""
        var detail = ""

        fun closeItem() {
            if (itemOpen) items.add(ChangeItem(title, detail.trim()))
            itemOpen = false
        }

        fun closeEntry() {
            closeItem()
            if (open && items.isNotEmpty()) entries.add(ChangeEntry(build, name, items))
            items = ArrayList()
            open = false
        }

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            val t = line.trim()
            when {
                t.startsWith("## ") -> {
                    closeEntry()
                    open = true
                    val rest = t.removePrefix("##").trim()
                    val afterWord = if (rest.startsWith("build", ignoreCase = true)) rest.substring(5).trimStart() else rest
                    val digits = afterWord.takeWhile { it.isDigit() }
                    build = digits.toIntOrNull() ?: 0
                    name = afterWord.substring(digits.length).trim().trimStart('—', '–', '-', ':', '·', ' ').trim()
                }
                open && (t.startsWith("- ") || t.startsWith("* ")) -> {
                    closeItem()
                    val body = t.substring(2).trim()
                    if (body.startsWith("**") && body.indexOf("**", 2) > 2) {
                        val end = body.indexOf("**", 2)
                        title = body.substring(2, end).trim()
                        detail = body.substring(end + 2).trimStart(' ', ':', '-', '—', '–').trim()
                    } else {
                        title = body
                        detail = ""
                    }
                    itemOpen = title.isNotEmpty()
                }
                open && itemOpen && t.isNotEmpty() && (line.startsWith(" ") || line.startsWith("\t")) ->
                    detail = if (detail.isEmpty()) t else "$detail $t"
                else -> Unit // a blank line, the file's title, a note: nothing to read
            }
        }
        closeEntry()
        return entries.sortedByDescending { it.build }
    }

    /**
     * What to announce on opening build [installedBuild]: the sections newer than the build whose notes were last dismissed
     * ([lastSeenBuild], 0 = never), up to [MAX_SHOWN], newest first. Someone who has never dismissed any (a first launch
     * after this feature arrived) gets only the newest section, not the whole history. Sections for builds that aren't
     * installed yet are never shown.
     */
    fun unseen(entries: List<ChangeEntry>, lastSeenBuild: Int, installedBuild: Int): List<ChangeEntry> {
        val upToNow = entries.filter { it.build in 1..installedBuild }.sortedByDescending { it.build }
        return if (lastSeenBuild <= 0) {
            upToNow.take(1)
        } else {
            upToNow.filter { it.build > lastSeenBuild }.take(MAX_SHOWN)
        }
    }

    /** "Build 62" or "Build 62 · Members and History". */
    fun heading(entry: ChangeEntry): String =
        if (entry.build > 0) "Build ${entry.build}" + (if (entry.name.isNotEmpty()) " · ${entry.name}" else "") else entry.name

    /** The items of the newest section in [text], for release notes (the section's own build number is not trusted there). */
    fun newestItems(text: String): List<ChangeItem> = parse(text).firstOrNull()?.items.orEmpty()
}
