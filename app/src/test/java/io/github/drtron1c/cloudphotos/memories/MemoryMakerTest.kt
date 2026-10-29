package io.github.drtron1c.cloudphotos.memories

import io.github.drtron1c.cloudphotos.photos.IndexEntry
import io.github.drtron1c.cloudphotos.places.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MemoryMakerTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val today = LocalDate.of(2026, 10, 29)
    private val assets = listOf("src/main/assets/geo", "app/src/main/assets/geo").map(::File).first { it.exists() }
    private val geo = Geo({ File(assets, "cities.tsv").inputStream() }, { File(assets, "countries.tsv").inputStream() })
    private var n = 0

    private fun photo(t: LocalDateTime, lat: Double? = null, lon: Double? = null, fav: Boolean = false, video: Boolean = false) =
        IndexEntry("id${n++}", t.atZone(zone).toInstant().toEpochMilli(), 0, "IMG_$n.HEIC", video, fav, false, "c$n", 1000, lat, lon)

    private fun library(): List<IndexEntry> {
        val out = ArrayList<IndexEntry>()
        // Everyday life at home in Athens: a few photos most days for two years.
        var d = LocalDate.of(2024, 9, 1)
        while (d.isBefore(today)) {
            val away = d.year == 2025 && d.monthValue == 6 && d.dayOfMonth in 10..14
            if (d.dayOfMonth % 3 == 0 && !away) for (h in 0 until 2) out += photo(d.atTime(10 + h, 0), 37.98, 23.73)
            d = d.plusDays(1)
        }
        // A trip to Paris, 5 days.
        for (day in 0 until 5) for (k in 0 until 8) out += photo(LocalDate.of(2025, 6, 10 + day).atTime(9 + k, 0), 48.86, 2.35, fav = k == 0)
        // A big day at home (birthday): 60 photos.
        for (k in 0 until 60) out += photo(LocalDate.of(2025, 3, 15).atTime(14, 0).plusMinutes(k.toLong() * 3), 37.98, 23.73)
        // On this day, last year and two years ago.
        for (k in 0 until 6) out += photo(LocalDate.of(2025, 10, 29).atTime(12, k * 5))
        for (k in 0 until 4) out += photo(LocalDate.of(2024, 10, 29).atTime(18, k * 5))
        // Videos never make it into memories.
        out += photo(LocalDate.of(2025, 10, 29).atTime(13, 0), video = true)
        return out
    }

    @Test
    fun makesOnThisDayTripsAndBigDays() {
        val lib = library()
        val ms = MemoryMaker.make(lib, today, zone, geo)
        val byKind = ms.groupBy { it.kind }
        println(ms.joinToString("\n") { "${it.kind}: ${it.title} — ${it.subtitle} (${it.ids.size})" })
        assertEquals(2, byKind["onthisday"]?.size)
        assertTrue(byKind["onthisday"]!!.first().subtitle.startsWith("1 year ago"))
        val trip = byKind["trip"]?.single()
        assertEquals("Trip to Paris", trip?.title)
        assertTrue(trip!!.ids.size <= 45)
        assertTrue(byKind["day"]?.any { it.subtitle.contains("15 March 2025") } == true)
        // On This Day comes first.
        assertEquals("onthisday", ms.first().kind)
        val videos = lib.filter { it.video }.map { it.id }.toSet()
        assertTrue(ms.none { m -> m.ids.any { it in videos } })
    }

    @Test
    fun everyCoverIsOneOfItsOwnPhotos() {
        // Lots of favourites, so not every one can make it into a memory.
        val lib = library() + (0 until 300).map { k -> photo(LocalDate.of(2025, 8, 2).atTime(8, 0).plusMinutes(k.toLong()), 37.98, 23.73, fav = true) }
        val ms = MemoryMaker.make(lib, today, zone, geo)
        assertTrue(ms.isNotEmpty())
        for (m in ms) assertTrue("${m.title}: cover ${m.cover} isn't one of its photos", m.cover in m.ids)
    }

    @Test
    fun pickSpreadsOutAndKeepsFavourites() {
        val group = (0 until 300).map { photo(LocalDateTime.of(2025, 1, 1, 0, 0).plusMinutes(it * 10L), fav = it % 50 == 0) }
        val picked = MemoryMaker.pick(group, 45)
        assertEquals(45, picked.size)
        assertTrue(group.filter { it.favourite }.all { f -> picked.any { it.id == f.id } })
        assertTrue(picked.zipWithNext().all { (a, b) -> a.date <= b.date })
        assertTrue(picked.first().date - group.first().date < 24 * 3_600_000L)
    }
}
