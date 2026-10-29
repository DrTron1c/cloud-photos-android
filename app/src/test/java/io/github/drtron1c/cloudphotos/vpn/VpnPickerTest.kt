package io.github.drtron1c.cloudphotos.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpnPickerTest {
    private val geo = Geo(File("src/main/assets/vpn/zone1970.tab").readText(), File("src/main/assets/vpn/iso3166.tab").readText())
    private val servers = ServerList.parseBundled(File("src/main/assets/vpn/proton-servers.json").readText()).second

    @Test
    fun bundledListHasFreeServers() {
        assertTrue(servers.size > 500)
        val free = ServerPicker.countries(servers, freeOnly = true).map { it.first }
        println("FREE COUNTRIES $free")
        assertTrue(free.size >= 5)
        assertTrue(free.all { geo.countryCode(it) != null })
    }

    @Test
    fun closestFreeCountryFromAthensIsRomania() {
        val athens = geo.forTimeZone("Europe/Athens")
        assertNotNull(athens)
        assertEquals("Romania", ServerPicker.closestCountry(servers, geo, athens, freeOnly = true))
        // From New York it's the US; from Tokyo, Japan.
        assertEquals("United States", ServerPicker.closestCountry(servers, geo, geo.forTimeZone("America/New_York"), true))
        assertEquals("Japan", ServerPicker.closestCountry(servers, geo, geo.forTimeZone("Asia/Tokyo"), true))
    }

    @Test
    fun readsWireGuardConfig() {
        val conf = """
            [Interface]
            # Key for test
            PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
            Address = 10.2.0.2/32
            DNS = 10.2.0.1

            [Peer]
            # RO-FREE#1
            PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
            AllowedIPs = 0.0.0.0/0
            Endpoint = 1.2.3.4:51820
        """.trimIndent()
        val acc = ProtonAccount.parse(conf).getOrThrow()
        assertEquals("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", acc.privateKey)
        assertEquals("10.2.0.2/32", acc.address)
        assertTrue(ProtonAccount.parse("hello").isFailure)
        assertTrue(ProtonAccount.parse("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=").isSuccess)
    }

    @Test
    fun flags() {
        assertEquals("🇷🇴", Geo.flag("RO"))
    }
}
