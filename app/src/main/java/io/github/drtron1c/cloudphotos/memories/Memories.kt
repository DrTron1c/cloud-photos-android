package io.github.drtron1c.cloudphotos.memories

import io.github.drtron1c.cloudphotos.photos.IndexEntry
import io.github.drtron1c.cloudphotos.places.Geo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A memory: a few dozen photos around a day, a trip, a season, a year or a person. */
data class Memory(
    val id: String,
    val title: String,
    val subtitle: String,
    /** "onthisday", "recent", "trip", "day", "season", "year", "person", "pet". */
    val kind: String,
    /** Photos to play, in date order. */
    val ids: List<String>,
    val cover: String,
    /** When it happened (for sorting). */
    val date: Long,
)

/**
 * Builds memories from the on-device library index (dates, places, favourites) — all on the
 * device, nothing sent anywhere. Hidden and Recently Deleted photos aren't in the index, so they
 * never appear in memories either.
 */
object MemoryMaker {
    private const val MAX_PHOTOS = 45

    fun make(
        entries: List<IndexEntry>,
        today: LocalDate,
        zone: ZoneId,
        geo: Geo?,
        people: List<Pair<String, List<String>>> = emptyList(),
        pets: Map<String, List<String>> = emptyMap(),
    ): List<Memory> {
        val photos = entries.filter { !it.video && it.date > 0 }.sortedBy { it.date }
        if (photos.isEmpty()) return emptyList()
        fun day(e: IndexEntry): LocalDate = Instant.ofEpochMilli(e.date).atZone(zone).toLocalDate()
        val byDay = photos.groupBy { day(it) }
        val out = ArrayList<Memory>()
        val dayFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())
        val weekdayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())
        val byId = photos.associateBy { it.id }

        fun memory(id: String, title: String, subtitle: String, kind: String, group: List<IndexEntry>): Memory? {
            val picked = pick(group, MAX_PHOTOS)
            if (picked.isEmpty()) return null
            // The cover is always one of the memory's own photos.
            val cover = picked.filter { it.favourite }.maxByOrNull { it.date } ?: picked[picked.size / 3]
            return Memory(id, title, subtitle, kind, picked.map { it.id }, cover.id, group.first().date)
        }

        // On This Day: the same date in earlier years.
        for ((d, group) in byDay) {
            if (d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year && group.size >= 3) {
                val years = today.year - d.year
                memory("otd:$d", "On This Day", (if (years == 1) "1 year ago" else "$years years ago") + " · " + d.format(dayFmt), "onthisday", group)?.let { out += it }
            }
        }

        // Recent months.
        for (back in 0L..1L) {
            val m = today.minusMonths(back)
            val group = photos.filter { val d = day(it); d.year == m.year && d.monthValue == m.monthValue }
            if (group.size >= 30) {
                val name = m.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                memory("month:${m.year}-${m.monthValue}", if (back == 0L) "This Month" else "Last Month", "$name ${m.year}", "recent", group)?.let { out += it }
            }
        }

        // Trips: stretches of photos taken far from home.
        val located = photos.filter { it.lat != null && it.lon != null }
        val tripDays = HashSet<LocalDate>()
        if (located.size >= 20) {
            val home = located.groupBy { (Math.floor(it.lat!! * 2) to Math.floor(it.lon!! * 2)) }.maxBy { it.value.size }.value
            val hLat = home.map { it.lat!! }.average()
            val hLon = home.map { it.lon!! }.average()
            var run = ArrayList<IndexEntry>()
            fun flush() {
                if (run.size < 2) { run = ArrayList(); return }
                val start = run.first().date
                val end = run.last().date
                val inSpan = photos.filter { it.date in start..end }
                val days = ChronoUnit.DAYS.between(day(run.first()), day(run.last())) + 1
                if ((inSpan.size >= 15 && days >= 2) || inSpan.size >= 30) {
                    val placeName = geo?.let { g ->
                        val names = run.mapNotNull { g.nearest(it.lat!!, it.lon!!) }
                        val cities = names.groupingBy { it.name }.eachCount().maxByOrNull { it.value }
                        val countries = names.groupingBy { it.country }.eachCount().maxByOrNull { it.value }
                        when {
                            cities != null && cities.value >= names.size * 0.6 -> cities.key
                            countries != null -> countries.key
                            else -> null
                        }
                    }
                    val first = day(run.first())
                    val last = day(run.last())
                    val range = when {
                        first == last -> first.format(dayFmt)
                        first.year == last.year && first.month == last.month -> "${first.dayOfMonth}–${last.format(dayFmt)}"
                        first.year == last.year -> first.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) + " – " + last.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
                        else -> first.format(dayFmt) + " – " + last.format(dayFmt)
                    }
                    memory("trip:$first", if (placeName != null) "Trip to $placeName" else "Trip", range, "trip", inSpan)?.let { out += it }
                    var d = first
                    while (!d.isAfter(last)) { tripDays += d; d = d.plusDays(1) }
                }
                run = ArrayList()
            }
            for (e in located) {
                val away = distanceKm(hLat, hLon, e.lat!!, e.lon!!) > 80
                if (away) {
                    if (run.isNotEmpty() && e.date - run.last().date > 3 * 86_400_000L) flush()
                    run += e
                } else flush()
            }
            flush()
        }

        // Big days: far more photos than usual (birthdays, weddings, days out).
        val counts = byDay.values.map { it.size }.sorted()
        val median = counts[counts.size / 2]
        for ((d, group) in byDay) {
            if (d in tripDays || group.size < max(25, median * 4)) continue
            val place = geo?.let { g -> group.firstOrNull { it.lat != null }?.let { g.nearest(it.lat!!, it.lon!!)?.name } }
            memory("day:$d", place?.let { "A Day in $it" } ?: "A Day to Remember", d.format(weekdayFmt), "day", group)?.let { out += it }
        }

        // Seasons and years.
        val years = photos.groupBy { day(it).year }
        for ((y, group) in years) {
            if (y < today.year && group.size >= 100) memory("year:$y", "$y in Photos", "${"%,d".format(group.size)} photos", "year", group)?.let { out += it }
            for ((season, months) in listOf("Summer" to setOf(6, 7, 8), "Winter" to setOf(12, 1, 2), "Spring" to setOf(3, 4, 5), "Autumn" to setOf(9, 10, 11))) {
                val s = group.filter { day(it).monthValue in months }
                val finished = LocalDate.of(y, months.max(), 1).plusMonths(1).isBefore(today) || y < today.year
                if (s.size >= 60 && finished) memory("season:$season-$y", "$season $y", "${"%,d".format(s.size)} photos", "season", s)?.let { out += it }
            }
        }

        // People and pets (when those are switched on).
        for ((name, ids) in people) {
            val group = ids.mapNotNull { byId[it] }.sortedBy { it.date }
            if (group.size >= 12) memory("person:$name", name, "Over the years", "person", group)?.let { out += it }
        }
        for ((kind, ids) in pets) {
            val group = ids.mapNotNull { byId[it] }.sortedBy { it.date }
            if (group.size >= 12) memory("pet:$kind", if (kind == "dogs") "Good Dogs" else "Cat Life", "${group.size} photos", "pet", group)?.let { out += it }
        }

        val order = listOf("onthisday", "recent", "trip", "day", "person", "pet", "season", "year")
        return out.sortedWith(compareBy({ order.indexOf(it.kind) }, { -it.date }))
    }

    /** Up to [max] photos spread over the whole event, favourites first, skipping near-identical shots. */
    fun pick(group: List<IndexEntry>, max: Int): List<IndexEntry> {
        val sorted = group.filter { !it.video }.sortedBy { it.date }
        val spaced = ArrayList<IndexEntry>()
        for (e in sorted) {
            val last = spaced.lastOrNull()
            if (last != null && e.date - last.date < 20_000L && !e.favourite) continue
            spaced += e
        }
        if (spaced.size <= max) return spaced
        val favs = spaced.filter { it.favourite }.take(max / 2)
        val rest = spaced.filter { !it.favourite }
        val need = max - favs.size
        val step = rest.size.toDouble() / need
        val sampled = (0 until need).map { rest[min(rest.lastIndex, (it * step).toInt())] }
        return (favs + sampled).distinctBy { it.id }.sortedBy { it.date }
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(min(1.0, abs(a))))
    }
}
