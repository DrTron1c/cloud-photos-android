package io.github.drtron1c.cloudphotos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.github.drtron1c.cloudphotos.BuildConfig

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var confirmSignOut by remember { mutableStateOf(false) }

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
                Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = p.label)
            }

            Group("Account") {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(vm.fullName ?: "iCloud", color = p.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(vm.appleId, color = p.secondaryLabel, fontSize = 14.sp)
                    }
                }
            }

            if (vm.zones.size > 1) {
                Group("Library", footer = "Choose which iCloud library to browse.") {
                    vm.zones.forEachIndexed { i, z ->
                        if (i > 0) Divider()
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.switchZone(z) }.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = vm.zone == z, onClick = { vm.switchZone(z) })
                            Text(if (z == "PrimarySync") "Personal Library" else "Shared Library", color = p.label, fontSize = 17.sp)
                        }
                    }
                }
            }

            Group("Privacy", footer = "Uses your fingerprint, face unlock or screen-lock PIN.") {
                ToggleRow("Lock Cloud Photos", "Ask to unlock every time you open the app", vm.lockApp) { on ->
                    if (activity != null && on) {
                        activity.requireUnlock("Turn on app lock") { vm.setLockApp(true) }
                    } else vm.setLockApp(on)
                }
                Divider()
                ToggleRow("Lock Hidden & Recently Deleted", "Like Face ID for these albums on iPhone", vm.lockPrivateAlbums) { on ->
                    if (activity != null && !on) {
                        activity.requireUnlock("Turn off album lock") { vm.setLockPrivateAlbums(false) }
                    } else vm.setLockPrivateAlbums(on)
                }
            }

            Group("Sign-in") {
                if (vm.rememberPassword) {
                    ActionRow("Forget saved password", p.accent) { vm.forgetPassword(); vm.toast("Saved password removed") }
                } else {
                    Text(
                        "Your password isn't saved. When your iCloud session expires (roughly every couple of months) you'll be asked to sign in again.",
                        color = p.secondaryLabel, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                    )
                }
                Divider()
                ActionRow("Refresh library", p.accent) { vm.refreshAll(); vm.toast("Refreshing…") }
            }

            Group(null) {
                ActionRow("Sign Out", p.destructive) { confirmSignOut = true }
            }

            Text(
                "Cloud Photos ${BuildConfig.VERSION_NAME}\nUnofficial iCloud Photos client — not made by or affiliated with Apple. " +
                    "It talks only to Apple's own iCloud servers, exactly like icloud.com does.",
                color = p.secondaryLabel, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("This removes your iCloud session, saved password and trusted-device status from this app. Your photos stay safe in iCloud.") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign Out", color = p.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Group(title: String?, footer: String? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Spacer(Modifier.height(22.dp))
    if (title != null) {
        Text(
            title.uppercase(), color = p.secondaryLabel, fontSize = 13.sp,
            modifier = Modifier.padding(start = 32.dp, bottom = 6.dp),
        )
    }
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.card)
    ) { content() }
    if (footer != null) {
        Text(footer, color = p.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp))
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = LocalPalette.current.separator)
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.label, fontSize = 17.sp)
            Text(subtitle, color = p.secondaryLabel, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(title: String, color: Color, onClick: () -> Unit) {
    Text(
        title, color = color, fontSize = 17.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    )
}
