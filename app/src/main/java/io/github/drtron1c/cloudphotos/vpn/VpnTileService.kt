package io.github.drtron1c.cloudphotos.vpn

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.drtron1c.cloudphotos.CloudPhotosApp
import io.github.drtron1c.cloudphotos.R
import io.github.drtron1c.cloudphotos.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: Proton VPN for Cloud Photos on / off from the notification shade. */
class VpnTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var watch: Job? = null

    private val graph get() = (application as CloudPhotosApp).graph

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        watch = s.launch { graph.vpn.state.collect { show(it) } }
    }

    override fun onStopListening() {
        watch?.cancel()
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private fun show(state: VpnState) {
        val tile = qsTile ?: return
        val hasAccount = graph.vpn.account.value != null
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_vpn)
        tile.label = "Proton VPN"
        when {
            !hasAccount -> { tile.state = Tile.STATE_INACTIVE; sub(tile, "Set up in Cloud Photos") }
            state is VpnState.Connected -> { tile.state = Tile.STATE_ACTIVE; sub(tile, state.server.country) }
            state is VpnState.Connecting -> { tile.state = Tile.STATE_ACTIVE; sub(tile, "Connecting…") }
            state is VpnState.Failed -> { tile.state = Tile.STATE_INACTIVE; sub(tile, "Couldn't connect") }
            else -> { tile.state = Tile.STATE_INACTIVE; sub(tile, "Off") }
        }
        tile.updateTile()
    }

    private fun sub(tile: Tile, text: String) {
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = text
    }

    override fun onClick() {
        super.onClick()
        val vpn = graph.vpn
        if (vpn.account.value == null || vpn.permissionIntent() != null) {
            openApp()
            return
        }
        val s = vpn.state.value
        if (s is VpnState.Connected || s is VpnState.Connecting) vpn.disconnectAsync() else vpn.connectAsync()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, "vpn")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
