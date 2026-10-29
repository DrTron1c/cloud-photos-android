package io.github.drtron1c.cloudphotos.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A collection built from the library index never shows (or hands over) a neighbouring photo. */
class SubsetListTest {
    private fun asset(id: String) = Asset(
        id, "a-$id", "CPLAsset", "tag", 0, 0, false, false, "public.jpeg", "$id.jpg", 1, 1, false, null,
        null, null, null, null, null, null,
    )

    private class Base(val items: MutableList<Asset?>) : MediaList {
        val ensured = mutableListOf<Int>()
        override val key = "base"
        override val spec = AlbumSpec.LIBRARY
        override val count: Int get() = items.size
        override val refreshing = false
        override fun get(index: Int) = items.getOrNull(index)
        override fun ensure(index: Int) { ensured += index }
        override fun indexOf(assetId: String) = items.indexOfFirst { it?.id == assetId }
        override fun update(asset: Asset) {}
        override fun removeAll(ids: Set<String>) {}
        override fun refresh() {}
    }

    @Test
    fun aMovedPhotoIsNeverSwappedForItsNeighbour() {
        // The index put "b" at position 200, but photos were added and removed on another device:
        // position 200 now holds "x", and "b" (now at 201) isn't loaded yet.
        val items = MutableList<Asset?>(400) { null }
        items[200] = asset("x")
        val base = Base(items)
        var moved = 0
        val list = SubsetList("k", AlbumSpec.LIBRARY, base, positions = listOf(200), ids = listOf("b"), onMoved = { moved++ })
        assertNull("must not show the neighbour", list[0])
        assertEquals(1, moved)
        assertTrue("loads around it", base.ensured.any { it in 0 until 400 })
        // When its page arrives it's found where it really is.
        items[201] = asset("b")
        assertEquals("b", list[0]?.id)
    }

    @Test
    fun aPhotoInItsPlaceIsShown() {
        val items = MutableList<Asset?>(10) { asset("p$it") }
        val list = SubsetList("k", AlbumSpec.LIBRARY, Base(items), positions = listOf(3, 7), ids = listOf("p3", "p7"))
        assertEquals("p3", list[0]?.id)
        assertEquals("p7", list[1]?.id)
    }
}
