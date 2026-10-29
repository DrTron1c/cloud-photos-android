package io.github.drtron1c.cloudphotos.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drtron1c.cloudphotos.vpn.Geo
import io.github.drtron1c.cloudphotos.vpn.ServerPicker
import io.github.drtron1c.cloudphotos.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Settings › Proton VPN. */
@Composable
fun VpnScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val s = vm.settings
    val vpn = vm.vpn
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vpn.state.collectAsState()
    val account by vpn.account.collectAsState()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var dialog by remember { mutableStateOf<String?>(null) }
    var listVersion by remember { mutableStateOf(0) }

    val closest = remember(listVersion) { vpn.closestFreeCountry() }
    val me = remember { vpn.myLocation() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            if (text == null) vm.toast("Couldn't read that file")
            else vpn.importAccount(text).onSuccess { vm.toast("Proton VPN account added") }.onFailure { vm.toast(it.message ?: "Not a WireGuard file") }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.groupedBackground)
            .verticalScroll(rememberScrollState())
            .padding(top = top + 8.dp, bottom = bottom + 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(icon = Icons.AutoMirrored.Filled.ArrowBackIos, onDark = false, onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text("Proton VPN", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = p.label)
            }

            if (account == null) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Connect your Proton VPN account", color = p.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Proton lets other apps use your account through a WireGuard key. It takes a minute, once (free accounts work):",
                            color = p.secondaryLabel, fontSize = 14.sp,
                        )
                        Step(1, "Open Proton's website and sign in.")
                        Step(2, "Go to Downloads › WireGuard configuration. Pick any platform and any server (free plan: a free server), then Create and Download.")
                        Step(3, "Come back and import the .conf file here.")
                        OutlinedButton(onClick = {
                            vm.expectingExternal = true
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://account.protonvpn.com/downloads#wireguard-configuration"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open account.protonvpn.com") }
                        Button(onClick = {
                            vm.expectingExternal = true
                            filePicker.launch(arrayOf("*/*"))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Import Configuration File") }
                        TextButton(onClick = { dialog = "paste" }) { Text("Paste the configuration or key instead") }
                        Text(
                            "Only the private key is kept, encrypted on this device. Cloud Photos never sees your Proton password.",
                            color = p.secondaryLabel, fontSize = 12.sp,
                        )
                    }
                }
            } else {
                // ---------- status ----------
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val connected = state as? VpnState.Connected
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape)
                                    .background(if (connected != null) Color(0xFF34C759) else p.placeholder),
                                contentAlignment = Alignment.Center,
                            ) {
                                when (state) {
                                    is VpnState.Connecting, VpnState.NeedsPermission ->
                                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                    else -> Icon(
                                        if (connected != null) Icons.Filled.Lock else Icons.Filled.LockOpen, null,
                                        tint = if (connected != null) Color.White else p.secondaryLabel,
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when (val st = state) {
                                        is VpnState.Connected -> "Connected"
                                        is VpnState.Connecting -> "Connecting to ${st.country}…" + if (st.attempt > 1) " (server ${st.attempt})" else ""
                                        VpnState.NeedsPermission -> "Waiting for Android's permission…"
                                        is VpnState.Failed -> "Not connected"
                                        VpnState.Off -> "Not connected"
                                    },
                                    color = p.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                )
                                val sub = when (val st = state) {
                                    is VpnState.Connected -> {
                                        val code = vpn.geo.countryCode(st.server.country)
                                        "${Geo.flag(code)} ${st.server.country} · ${st.server.city} · ${st.server.name}"
                                    }
                                    is VpnState.Failed -> st.message
                                    else -> null
                                }
                                if (sub != null) Text(sub, color = if (state is VpnState.Failed) p.destructive else p.secondaryLabel, fontSize = 13.sp)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state is VpnState.Connected || state is VpnState.Connecting) {
                                OutlinedButton(onClick = { vpn.disconnectAsync() }) { Text("Disconnect") }
                            } else {
                                Button(onClick = { vpn.connectAsync() }) { Text("Connect") }
                            }
                        }
                    }
                }

                // ---------- country ----------
                Title("Country")
                Card {
                    val chosen = s.vpnCountry
                    Row(
                        Modifier.fillMaxWidth().clickable { dialog = "country" }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (chosen.isEmpty()) "Closest free country" else "${Geo.flag(vpn.geo.countryCode(chosen))} $chosen",
                                color = p.label, fontSize = 17.sp,
                            )
                            if (chosen.isEmpty() && closest != null) {
                                Text("Now: ${Geo.flag(vpn.geo.countryCode(closest))} $closest", color = p.secondaryLabel, fontSize = 13.sp)
                            }
                        }
                        Text("Change ›", color = p.accent, fontSize = 15.sp)
                    }
                    Divider()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            s.vpnCountry = ""
                            if (state is VpnState.Connected) vpn.connectAsync()
                        }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.NearMe, null, tint = p.accent)
                        Spacer(Modifier.width(10.dp))
                        Text("Quick select: closest free country" + (closest?.let { " (${Geo.flag(vpn.geo.countryCode(it))} $it)" } ?: ""), color = p.accent, fontSize = 16.sp)
                    }
                    Divider()
                    ToggleRowV("Free plan", "Only show and use Proton's free servers", s.vpnFreeOnly) { s.vpnFreeOnly = it }
                }

                // ---------- when ----------
                Title("When to connect")
                Card {
                    ToggleRowV("Connect when Cloud Photos opens", "Before any photos load", s.vpnAutoConnect) { s.vpnAutoConnect = it }
                    Divider()
                    ToggleRowV("Disconnect when you leave the app", "A few seconds after you switch away or close it", s.vpnAutoDisconnect) { s.vpnAutoDisconnect = it }
                    Divider()
                    ToggleRowV(
                        "Only use the internet through the VPN",
                        "If the VPN can't connect, Cloud Photos waits instead of going online without it",
                        s.vpnRequire,
                    ) { s.vpnRequire = it }
                }

                Title("What goes through the VPN")
                Card {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Segment("Only Cloud Photos", !s.vpnWholeDevice, Modifier.weight(1f)) { s.vpnWholeDevice = false; if (state is VpnState.Connected) vpn.connectAsync() }
                        Segment("Whole device", s.vpnWholeDevice, Modifier.weight(1f)) { s.vpnWholeDevice = true; if (state is VpnState.Connected) vpn.connectAsync() }
                    }
                    Text(
                        if (s.vpnWholeDevice) "Every app's traffic uses Proton while it's connected."
                        else "Only Cloud Photos uses Proton — your Wi-Fi provider can't see that you're using iCloud at all. Other apps are unaffected.",
                        color = p.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    )
                }

                Title("Account")
                Card {
                    Text(
                        "WireGuard key added " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(account!!.importedAt)) +
                            (account!!.madeFor.takeIf { it.isNotBlank() }?.let { " · made for $it" } ?: ""),
                        color = p.secondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                    )
                    Divider()
                    Text(
                        "Server list from " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(vpn.listTimestamp * 1000)) +
                            " · ${vpn.servers.size} servers. Tap to refresh.",
                        color = p.accent, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                val ok = vpn.refreshServers(vm.http, force = true)
                                listVersion++
                                vm.toast(if (ok) "Server list updated" else "Couldn't update the server list right now")
                            }
                        }.padding(16.dp),
                    )
                    Divider()
                    Text(
                        "Replace Key…", color = p.accent, fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth().clickable { vm.expectingExternal = true; filePicker.launch(arrayOf("*/*")) }.padding(16.dp),
                    )
                    Divider()
                    Text(
                        "Remove Proton VPN", color = p.destructive, fontSize = 17.sp,
                        modifier = Modifier.fillMaxWidth().clickable { dialog = "remove" }.padding(16.dp),
                    )
                }
                Text(
                    "Uses the official WireGuard library. The server list comes from the open-source gluetun project, which tracks Proton's servers. " +
                        "Your rough location for “closest” comes from your phone's time zone — no location permission is used.",
                    color = p.secondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
                )
            }
        }
    }

    when (dialog) {
        "paste" -> {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Paste WireGuard configuration") },
                text = {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("[Interface]\nPrivateKey = …") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vpn.importAccount(text).onSuccess { dialog = null; vm.toast("Proton VPN account added") }
                            .onFailure { vm.toast(it.message ?: "Not a WireGuard configuration") }
                    }, enabled = text.isNotBlank()) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "remove" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Remove Proton VPN?") },
            text = { Text("Disconnects and deletes the key from this device. Your Proton account isn't affected.") },
            confirmButton = { TextButton(onClick = { dialog = null; vpn.removeAccount() }) { Text("Remove", color = p.destructive) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        "country" -> {
            val countries = remember(listVersion, s.vpnFreeOnly) { ServerPicker.countries(vpn.servers, s.vpnFreeOnly) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Country") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        item {
                            CountryRow("📍", "Closest free country" + (closest?.let { " — $it" } ?: ""), s.vpnCountry.isEmpty(), null) {
                                s.vpnCountry = ""; dialog = null
                                if (state is VpnState.Connected) vpn.connectAsync()
                            }
                        }
                        items(countries, key = { it.first }) { (name, hasFree) ->
                            val code = vpn.geo.countryCode(name)
                            val km = me?.let { m -> vpn.geo.forCountry(name)?.distanceKm(m)?.roundToInt() }
                            CountryRow(
                                Geo.flag(code), name, s.vpnCountry == name,
                                listOfNotNull(if (hasFree) "Free" else null, km?.let { "%,d km".format(it) }).joinToString(" · ").ifEmpty { null },
                            ) {
                                s.vpnCountry = name; dialog = null
                                if (state is VpnState.Connected) vpn.connectAsync()
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            )
        }
    }
}

@Composable
private fun CountryRow(flag: String, name: String, selected: Boolean, detail: String?, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .background(if (selected) p.accent.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Text(name, color = p.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, color = p.secondaryLabel, fontSize = 12.sp)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val p = LocalPalette.current
    Row {
        Box(Modifier.size(22.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
            Text("$n", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = p.label, fontSize = 14.sp)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Spacer(Modifier.height(16.dp))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card)) { content() }
}

@Composable
private fun Title(text: String) {
    Text(
        text.uppercase(), color = LocalPalette.current.secondaryLabel, fontSize = 13.sp,
        modifier = Modifier.padding(start = 32.dp, top = 18.dp),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = LocalPalette.current.separator)
}

@Composable
private fun Segment(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier.clip(RoundedCornerShape(9.dp)).background(if (on) p.accent else p.placeholder).clickable(onClick = onClick).padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Color.White else p.label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun ToggleRowV(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.label, fontSize = 17.sp)
            Text(subtitle, color = p.secondaryLabel, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
