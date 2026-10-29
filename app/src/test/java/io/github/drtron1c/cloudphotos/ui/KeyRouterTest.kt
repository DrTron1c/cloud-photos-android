package io.github.drtron1c.cloudphotos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The top-most screen gets keys first; a screen that's gone gets none. */
class KeyRouterTest {
    @Test
    fun deepestScreenWins() {
        val r = KeyRouter()
        val calls = ArrayList<String>()
        r.add(0) { calls += "grid"; true }
        val viewer = r.add(2) { calls += "viewer"; false }
        r.add(1) { calls += "album"; true }
        // KeyEvent isn't needed for the routing itself.
        assert(r.dispatchForTest())
        assertEquals(listOf("viewer", "album"), calls)
        calls.clear()
        r.remove(viewer)
        r.dispatchForTest()
        assertEquals(listOf("album"), calls)
    }
}
