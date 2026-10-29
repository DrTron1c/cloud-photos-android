package io.github.drtron1c.cloudphotos.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Offline place names from the bundled town list. */
class GeoTest {
    private val assets = listOf("src/main/assets/geo", "app/src/main/assets/geo").map(::File).first { it.exists() }
    private val geo = Geo({ File(assets, "cities.tsv").inputStream() }, { File(assets, "countries.tsv").inputStream() })

    @Test
    fun namesBigCitiesAndTheirSuburbs() {
        assertEquals("Athens", geo.nearest(37.9755, 23.7348)?.name)            // Syntagma
        assertEquals("Athens", geo.nearest(37.9420, 23.6470)?.name)            // Piraeus side, still "Athens"
        assertEquals("Greece", geo.nearest(37.9755, 23.7348)?.country)
        assertEquals("London", geo.nearest(51.5007, -0.1246)?.name)
        assertEquals("Paris", geo.nearest(48.8584, 2.2945)?.name)              // Eiffel Tower
        assertEquals("Thessaloniki", geo.nearest(40.6264, 22.9484)?.name)
    }

    @Test
    fun farFromAnyTownIsUnnamed() {
        assertNull(geo.nearest(0.0, -30.0))                                    // mid-Atlantic
        assertEquals("Athens, Greece", geo.label(37.9755, 23.7348))
    }
}
