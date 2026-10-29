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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.github.drtron1c.cloudphotos.photos.AlbumSpec

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchTab(vm: MainViewModel) {
    val p = LocalPalette.current
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TAB_BAR_HEIGHT.dp
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = vm.indexMatchesLibrary

    fun run(q: String) {
        if (q.isBlank()) return
        keyboard?.hide()
        val list = vm.search(q)
        if (list == null) vm.toast("Still organising your library… ${(vm.index.progress * 100).toInt()}%")
        else vm.openList(list)
    }

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
            placeholder = { Text("Dates, months, years, file names, videos…") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run(query) }),
            modifier = Modifier.fillMaxWidth(),
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
            if (n >= 0) {
                Button(onClick = { run(query) }, enabled = n > 0, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(if (n == 0) "No results" else if (n == 1) "Show 1 result" else "Show %,d results".format(n))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Try “March 2024”, “videos 2023”, “today”, “favourites summer”, “screenshots”, “IMG_1234” or “edited”.",
                color = p.secondaryLabel, fontSize = 13.sp,
            )
            return@Column
        }

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
