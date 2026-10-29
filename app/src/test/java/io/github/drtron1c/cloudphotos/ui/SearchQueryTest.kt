package io.github.drtron1c.cloudphotos.ui

import io.github.drtron1c.cloudphotos.photos.IndexEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class SearchQueryTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 29)

    private fun entry(date: LocalDate, name: String = "IMG_0001.HEIC", video: Boolean = false) = IndexEntry(
        "id-$date-$name", date.atStartOfDay(zone).toInstant().toEpochMilli(), 0, name, video, false, false, "", 0, null, null,
    )

    @Test
    fun redHatLate2026() {
        val q = SearchQuery.parse("red hat late 2026", today)!!
        assertEquals("red hat", q.phrase)
        assertTrue(q.matchesFilters(entry(LocalDate.of(2026, 10, 3)), zone))
        assertTrue(q.matchesFilters(entry(LocalDate.of(2026, 12, 31)), zone))
        assertFalse(q.matchesFilters(entry(LocalDate.of(2026, 6, 1)), zone))
        assertFalse(q.matchesFilters(entry(LocalDate.of(2025, 11, 1)), zone))
    }

    @Test
    fun seasonsAndRelative() {
        val summer = SearchQuery.parse("beach summer 2025", today)!!
        assertEquals("beach", summer.phrase)
        assertTrue(summer.matchesFilters(entry(LocalDate.of(2025, 7, 14)), zone))
        assertFalse(summer.matchesFilters(entry(LocalDate.of(2025, 9, 14)), zone))

        val lastMonth = SearchQuery.parse("dog last month", today)!!
        assertEquals("dog", lastMonth.phrase)
        assertTrue(lastMonth.matchesFilters(entry(LocalDate.of(2026, 9, 30)), zone))
        assertFalse(lastMonth.matchesFilters(entry(LocalDate.of(2026, 10, 1)), zone))

        val early = SearchQuery.parse("snow early 2024", today)!!
        assertTrue(early.matchesFilters(entry(LocalDate.of(2024, 2, 1)), zone))
        assertFalse(early.matchesFilters(entry(LocalDate.of(2024, 5, 1)), zone))
    }

    @Test
    fun fileNamesStillWork() {
        val q = SearchQuery.parse("IMG_0001", today)!!
        assertTrue(q.matches(entry(LocalDate.of(2020, 1, 1)), zone))
        assertFalse(q.matches(entry(LocalDate.of(2020, 1, 1), name = "IMG_9999.JPG"), zone))
        val v = SearchQuery.parse("videos of the cat in march", today)!!
        assertEquals("cat", v.phrase)
        assertFalse(v.matchesFilters(entry(LocalDate.of(2026, 3, 2)), zone))
        assertTrue(v.matchesFilters(entry(LocalDate.of(2026, 3, 2), video = true), zone))
    }
}
