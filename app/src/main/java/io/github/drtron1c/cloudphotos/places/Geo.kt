package io.github.drtron1c.cloudphotos.places

import android.content.Context
import java.io.InputStream
import kotlin.math.cos
import kotlin.math.floor

/** A town or city from the bundled list. */
data class City(val name: String, val country: String, val lat: Double, val lon: Double)

/**
 * Place names without asking any server: the nearest of ~34,000 towns and cities (GeoNames,
 * CC BY 4.0, population 15,000+) bundled with the app. Your photo locations never leave the device.
 */
class Geo(citiesTsv: () -> InputStream, countriesTsv: () -> InputStream) {
    private val names: Array<String>
    private val countryOf: Array<String>
    private val lats: DoubleArray
    private val lons: DoubleArray
    private val pops: IntArray
    /** 1° grid cells → city indexes. */
    private val grid = HashMap<Int, IntArray>()

    init {
        val countries = countriesTsv().bufferedReader().useLines { lines ->
            lines.mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }.toMap()
        }
        val rows = citiesTsv().bufferedReader().useLines { lines -> lines.mapNotNull { l -> l.split('\t').takeIf { it.size >= 4 } }.toList() }
        names = Array(rows.size) { rows[it][0] }
        countryOf = Array(rows.size) { countries[rows[it][1]] ?: rows[it][1] }
        lats = DoubleArray(rows.size) { rows[it][2].toDouble() }
        lons = DoubleArray(rows.size) { rows[it][3].toDouble() }
        pops = IntArray(rows.size) { rows[it].getOrNull(4)?.toIntOrNull() ?: 0 }
        val cells = HashMap<Int, MutableList<Int>>()
        for (i in rows.indices) cells.getOrPut(cell(lats[i], lons[i])) { ArrayList() } += i
        for ((k, v) in cells) grid[k] = v.toIntArray()
    }

    private fun cell(lat: Double, lon: Double) = (floor(lat).toInt() + 90) * 400 + (floor(lon).toInt() + 180)

    /**
     * The place a photo was taken: a big city if it's within 25 km (the suburbs of Athens are
     * "Athens"), otherwise the nearest town within [maxKm].
     */
    fun nearest(lat: Double, lon: Double, maxKm: Double = 40.0): City? {
        var best = -1
        var bestD = Double.MAX_VALUE
        var big = -1
        val cosLat = cos(Math.toRadians(lat))
        for (dy in -1..1) for (dx in -1..1) {
            val idx = grid[cell(lat + dy, lon + dx)] ?: continue
            for (i in idx) {
                val ky = (lats[i] - lat) * 111.0
                val kx = (lons[i] - lon) * 111.0 * cosLat
                val d = ky * ky + kx * kx
                if (d < bestD - 1e-9) { bestD = d; best = i }
                if (d <= 25.0 * 25.0 && pops[i] >= 500_000 && (big < 0 || pops[i] > pops[big])) big = i
            }
        }
        if (big >= 0 && maxKm >= 25.0) best = big
        else if (best < 0 || bestD > maxKm * maxKm) return null
        return City(names[best], countryOf[best], lats[best], lons[best])
    }

    /** "Athens, Greece"-style label for a spot, or just the country, or null far from anywhere. */
    fun label(lat: Double, lon: Double): String? {
        nearest(lat, lon)?.let { return "${it.name}, ${it.country}" }
        return nearest(lat, lon, 250.0)?.country
    }

    companion object {
        @Volatile private var instance: Geo? = null

        fun get(context: Context): Geo = instance ?: synchronized(this) {
            instance ?: Geo({ context.assets.open("geo/cities.tsv") }, { context.assets.open("geo/countries.tsv") }).also { instance = it }
        }
    }
}
