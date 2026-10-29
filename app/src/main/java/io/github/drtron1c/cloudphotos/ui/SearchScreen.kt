package io.github.drtron1c.cloudphotos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.github.drtron1c.cloudphotos.ai.ModelState
import io.github.drtron1c.cloudphotos.photos.AlbumSpec

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchTab(vm: MainViewModel) {
    val p = LocalPalette.current
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (LocalNoTabBar.current) 0.dp else TAB_BAR_HEIGHT.dp
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = vm.indexMatchesLibrary
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Ctrl+F or / from anywhere lands in the search field.
    androidx.compose.runtime.LaunchedEffect(vm.focusSearch) { if (vm.focusSearch > 0) runCatching { focus.requestFocus() } }

    fun run(q: String) {
        if (q.isBlank()) return
        keyboard?.hide()
        vm.runSearch(q)
    }
    val modelState by vm.smart.modelState.collectAsState()
    val analysed by vm.smart.indexed.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(top = top + 8.dp, bottom = bottom + 16.dp, start = 18.dp, end = 18.dp),
    ) {
        Text("Search", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = p.label)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(if (vm.settings.aiSearch) "Things, places, dates… e.g. red hat late 2026" else "Dates, months, years, file names, videos…") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run(query) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(16.dp))

        if (!ready && !vm.offlineMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Organising your library for search… ${(vm.index.progress * 100).toInt()}%",
                    color = p.secondaryLabel, fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (query.isNotBlank()) {
            val n = remember(query, vm.index.entries) { vm.searchCount(query) }
            val smartQuery = remember(query) { SearchQuery.parse(query)?.hasPhrase == true }
            if (n == 0 && smartQuery && (vm.smartReady || (vm.settings.aiText && vm.vision.textDone.value > 0) || vm.people.isNotEmpty())) {
                Button(onClick = { run(query) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Search the Pictures")
                }
            } else if (n >= 0) {
                Button(onClick = { run(query) }, enabled = n > 0, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(if (n == 0) "No results" else if (n == 1) "Show 1 result" else "Show %,d results".format(n))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (vm.settings.aiSearch) "Describe what's in the photo and when: “red hat late 2026”, “dog on a beach summer 2025”, “birthday cake”, “snow last year”. File names like “IMG_1234” work too."
                else "Try “March 2024”, “late 2025”, “videos 2023”, “today”, “favourites summer”, “screenshots”, “IMG_1234” or “edited”. Turn on Smart Search below to search what's in the pictures.",
                color = p.secondaryLabel, fontSize = 13.sp,
            )
            return@Column
        }

        SmartSearchCard(vm, modelState, analysed)

        Section("Media Types")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (spec in listOf(AlbumSpec.FAVORITES) + AlbumSpec.MEDIA_TYPES) {
                Chip(spec.title) { openAlbum(vm, activity, spec) }
            }
        }

        val years = remember(vm.index.entries) { vm.years() }
        if (years.isNotEmpty()) {
            Section("Years")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (y in years) Chip(y.toString()) { run(y.toString()) }
            }
        }

        Section("Collections")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Memories") { vm.open(Route.Memories) }
            Chip("People & Pets") { vm.open(Route.People) }
            Chip("Places") { vm.open(Route.Places) }
            Chip("Similar Photos") { vm.open(Route.Similar) }
            Chip("Recently Saved") { vm.openAlbum(AlbumSpec.RECENTLY_SAVED) }
            Chip("On This Day") { vm.onThisDayList()?.let { vm.openList(it) } ?: vm.toast("Still organising your library…") }
            Chip("Recently Edited") { vm.recentlyEditedList()?.let { vm.openList(it) } ?: vm.toast("Still organising your library…") }
            Chip("With Location") { run("location") }
            Chip("Duplicates") { vm.duplicatesList()?.let { vm.openList(it) } ?: vm.toast("Still organising your library…") }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LocalPalette.current.label)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        text,
        color = p.accent,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(p.placeholder)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** On-device AI search: off / downloading / analysing / ready. */
@Composable
private fun SmartSearchCard(vm: MainViewModel, modelState: ModelState, analysed: Int) {
    val p = LocalPalette.current
    Spacer(Modifier.height(4.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.placeholder).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = p.accent)
            Spacer(Modifier.width(8.dp))
            Text("Smart Search", color = p.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!vm.settings.aiSearch) {
            Text(
                "Search what's in your photos — “red hat late 2026”. Runs entirely on this device: your photos and searches never leave it. " +
                    "Needs a one-time ${megabytes(vm.smart.downloadSize)} download, then analyses your library in the background.",
                color = p.secondaryLabel, fontSize = 14.sp,
            )
            Button(onClick = { vm.setAiSearch(true) }, shape = RoundedCornerShape(12.dp)) { Text("Turn On Smart Search") }
        } else {
            val status = when (modelState) {
                is ModelState.Downloading -> "Downloading the AI model… ${megabytes(modelState.bytes)} of ${megabytes(modelState.total)}"
                is ModelState.Failed -> "Model download failed: ${modelState.message}. It will try again."
                else -> vm.aiStatus().ifBlank { if (analysed > 0) "%,d photos analysed".format(analysed) else "Starting…" }
            }
            Text(status, color = p.secondaryLabel, fontSize = 14.sp)
            if (modelState is ModelState.Downloading && modelState.total > 0) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (modelState.bytes.toFloat() / modelState.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
