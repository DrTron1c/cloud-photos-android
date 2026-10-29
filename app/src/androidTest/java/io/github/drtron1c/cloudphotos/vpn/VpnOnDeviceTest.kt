package io.github.drtron1c.cloudphotos.vpn

import android.net.VpnService
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import io.github.drtron1c.cloudphotos.CloudPhotosApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Brings a WireGuard tunnel up and down on the device exactly as the app configures it (only
 * Cloud Photos routed), against a real Proton free server. The key here is random, so Proton
 * won't answer — this checks the VPN plumbing, not a Proton account.
 */
@RunWith(AndroidJUnit4::class)
class VpnOnDeviceTest {
    @Test
    fun tunnelComesUpAndDown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // The CI script grants the VPN permission (appops ACTIVATE_VPN), as tapping "OK" would.
        assertNull(VpnService.prepare(ctx))
        val vpn = (ctx.applicationContext as CloudPhotosApp).graph.vpn
        val server = vpn.servers.first { it.country == "Romania" && it.free }
        val backend = GoBackend(ctx)
        val tunnel = object : Tunnel {
            override fun getName() = "cptest"
            override fun onStateChange(newState: Tunnel.State) {}
        }
        val iface = Interface.Builder()
            .parsePrivateKey(KeyPair().privateKey.toBase64())
            .parseAddresses("10.2.0.2/32")
            .parseDnsServers("10.2.0.1")
            .includeApplication(ctx.packageName)
            .build()
        val peer = Peer.Builder().parsePublicKey(server.publicKey).parseEndpoint("${server.ip}:51820")
            .parseAllowedIPs("0.0.0.0/0, ::/0").setPersistentKeepalive(25).build()
        val config = Config.Builder().setInterface(iface).addPeer(peer).build()
        assertEquals(Tunnel.State.UP, backend.setState(tunnel, Tunnel.State.UP, config))
        Log.i("CompanionTest", "VPN_UP server=${server.name}")
        Thread.sleep(3000)
        val stats = backend.getStatistics(tunnel)
        Log.i("CompanionTest", "VPN_STATS tx=${stats.totalTx()} rx=${stats.totalRx()}")
        assertTrue(stats.totalTx() > 0)
        assertEquals(Tunnel.State.DOWN, backend.setState(tunnel, Tunnel.State.DOWN, null))
        Log.i("CompanionTest", "VPN_DOWN ok")
        // Closest free country from the emulator's time zone (UTC in CI).
        Log.i("CompanionTest", "VPN_CLOSEST_FREE=${vpn.closestFreeCountry()}")
    }
}
