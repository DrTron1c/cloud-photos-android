package io.github.drtron1c.cloudphotos.ui

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drtron1c.cloudphotos.update.ChangeEntry
import io.github.drtron1c.cloudphotos.update.ChangeItem
import io.github.drtron1c.cloudphotos.update.Changelog
import io.github.drtron1c.cloudphotos.update.ReleaseInfo
import io.github.drtron1c.cloudphotos.update.UpdateManager
import io.github.drtron1c.cloudphotos.update.UpdateState
import java.time.Duration

internal fun megabytes(bytes: Long): String = "${(bytes + 500_000L) / 1_000_000L} MB"

/** The bundled change log (`assets/changelog.md`), newest build first. */
fun loadChangelog(context: Context): List<ChangeEntry> =
    runCatching { Changelog.parse(context.assets.open(Changelog.ASSET).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())

/** Floating Liquid Glass card used for the banners at the top of the app. */
@Composable
fun BannerCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    GlassSurface(
        modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        cornerRadius = 24.dp,
        elevation = 14.dp,
        flat = p.card,
        blur = 22.dp,
    ) { content() }
}

@Composable
private fun BannerIcon(icon: ImageVector) {
    val p = LocalPalette.current
    Box(
        Modifier.size(40.dp).background(Brush.linearGradient(listOf(p.accent.copy(alpha = 0.75f), p.accent)), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White) }
}

/** "Update available" at the top of the app, with download progress and install. */
@Composable
fun UpdateBanner(updates: UpdateManager, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val state by updates.state.collectAsState()
    val dismissed by updates.bannerDismissed.collectAsState()
    var notesOpen by rememberSaveable { mutableStateOf(false) }
    val info: ReleaseInfo? = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    }
    val inProgress = state is UpdateState.Downloading || state is UpdateState.Installing
    if (info == null || (dismissed && !inProgress)) return

    BannerCard(modifier) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BannerIcon(Icons.Filled.SystemUpdate)
                Column(Modifier.weight(1f)) {
                    Text("Update available", color = p.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        info.title + if (info.sizeBytes > 0) " · ${megabytes(info.sizeBytes)}" else "",
                        color = p.secondaryLabel, fontSize = 13.sp,
                    )
                }
                if (!inProgress) {
                    IconButton(onClick = updates::dismissBanner) { Icon(Icons.Filled.Close, "Later", tint = p.secondaryLabel) }
                }
            }
            if (info.changes.isNotBlank() && state is UpdateState.Available) {
                Text(info.changes, color = p.label, fontSize = 14.sp, maxLines = 3)
            }
            if (info.notes.isNotEmpty() && state is UpdateState.Available) {
                Row(
                    Modifier.clickable { notesOpen = !notesOpen }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("What's new in build ${info.versionCode}", color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Icon(if (notesOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = p.accent)
                }
                if (notesOpen) {
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        ChangeList(listOf(ChangeEntry(info.versionCode, "", info.notes)))
                    }
                }
            }
            when (val s = state) {
                is UpdateState.Available -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = updates::downloadAndInstall) { Text("Download & Install") }
                    TextButton(onClick = updates::dismissBanner) { Text("Later") }
                }
                is UpdateState.Downloading -> {
                    val fraction = if (info.sizeBytes > 0) (s.bytes.toFloat() / info.sizeBytes).coerceIn(0f, 1f) else null
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Downloading… " + megabytes(s.bytes) + if (info.sizeBytes > 0) " of ${megabytes(info.sizeBytes)}" else "",
                            color = p.secondaryLabel, fontSize = 13.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = updates::cancelDownload) { Text("Cancel") }
                    }
                }
                is UpdateState.Installing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Downloaded. Confirm the install on Android's screen — Cloud Photos restarts when it finishes.", color = p.secondaryLabel, fontSize = 13.sp)
                }
                is UpdateState.NeedsPermission -> {
                    Text(
                        "Android needs your OK first: on the screen that opened, switch on “Allow from this source” for Cloud Photos, then come back — the download starts by itself.",
                        color = p.label, fontSize = 14.sp,
                    )
                    Button(onClick = updates::downloadAndInstall) { Text("Open the Setting Again") }
                }
                is UpdateState.Failed -> {
                    Text(s.message, color = p.destructive, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = updates::downloadAndInstall) { Text("Try Again") }
                        TextButton(onClick = updates::dismissBanner) { Text("Later") }
                    }
                }
                else -> Unit
            }
        }
    }
}

/** "What's new" after an update: tap to read, "Got it" hides it for good. */
@Composable
fun WhatsNewBanner(entries: List<ChangeEntry>, installedBuild: Int, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    if (entries.isEmpty()) return
    val count = entries.sumOf { it.items.size }
    BannerCard(modifier) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BannerIcon(Icons.Filled.AutoAwesome)
                Column(Modifier.weight(1f)) {
                    Text("What's new", color = p.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Build $installedBuild · " + (if (count == 1) "1 change" else "$count changes") + if (open) "" else " · tap to read",
                        color = p.secondaryLabel, fontSize = 13.sp,
                    )
                }
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = p.secondaryLabel)
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss what's new", tint = p.secondaryLabel) }
            }
            if (open) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    ChangeList(entries)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Got It") }
                }
            }
        }
    }
}

/** Bullet points: each item's name in bold and what it is underneath. */
@Composable
fun ChangeList(entries: List<ChangeEntry>, modifier: Modifier = Modifier, showHeadings: Boolean = entries.size > 1) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.forEach { entry ->
            if (showHeadings) Text(Changelog.heading(entry), color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            entry.items.forEach { ChangeRow(it) }
        }
    }
}

@Composable
private fun ChangeRow(item: ChangeItem) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).background(p.accent, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, color = p.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (item.detail.isNotBlank()) Text(item.detail, color = p.secondaryLabel, fontSize = 14.sp)
        }
    }
}

/** One line for Settings: what the last check found. */
fun updateStatusLine(state: UpdateState, nowMs: Long): String = when (state) {
    UpdateState.Idle -> "Not checked yet."
    UpdateState.Checking -> "Checking GitHub…"
    is UpdateState.UpToDate -> "You have the latest build (checked ${ago(state.checkedAtMs, nowMs)})."
    is UpdateState.Available -> "${state.info.title} is available."
    is UpdateState.Downloading -> "Downloading ${state.info.title}…"
    is UpdateState.Installing -> "Waiting for Android's installer…"
    is UpdateState.NeedsPermission -> "Allow Cloud Photos to install updates, then come back."
    is UpdateState.Failed -> state.message
}

private fun ago(then: Long, now: Long): String {
    val d = Duration.ofMillis((now - then).coerceAtLeast(0))
    return when {
        d.toMinutes() < 1 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        else -> "${d.toDays()} days ago"
    }
}

/** Proton VPN status at the top while it connects, or when it couldn't. */
@Composable
fun VpnBanner(vm: MainViewModel, state: io.github.drtron1c.cloudphotos.vpn.VpnState, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var dismissedFailure by rememberSaveable { mutableStateOf<String?>(null) }
    val text: String
    val failed: Boolean
    when (state) {
        is io.github.drtron1c.cloudphotos.vpn.VpnState.Connecting -> {
            text = "Connecting to Proton VPN · ${state.country}…"; failed = false
        }
        io.github.drtron1c.cloudphotos.vpn.VpnState.NeedsPermission -> {
            text = "Allow Cloud Photos to set up the VPN on Android's screen"; failed = false
        }
        is io.github.drtron1c.cloudphotos.vpn.VpnState.Failed -> {
            if (dismissedFailure == state.message) return
            text = state.message; failed = true
        }
        else -> return
    }
    Box(modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        GlassSurface(cornerRadius = 20.dp, elevation = 10.dp, flat = p.card) {
            Row(
                Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (failed) Icon(Icons.Filled.Close, null, tint = p.destructive, modifier = Modifier.size(18.dp))
                else androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(text, color = p.label, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false), maxLines = 3)
                if (failed) {
                    TextButton(onClick = { vm.vpn.connectAsync() }) { Text("Retry") }
                    if (vm.phase == Phase.Ready) TextButton(onClick = { vm.open(Route.Vpn) }) { Text("Settings") }
                    IconButton(onClick = { dismissedFailure = state.let { (it as io.github.drtron1c.cloudphotos.vpn.VpnState.Failed).message } }) {
                        Icon(Icons.Filled.Close, "Dismiss", tint = p.secondaryLabel)
                    }
                }
            }
        }
    }
}
