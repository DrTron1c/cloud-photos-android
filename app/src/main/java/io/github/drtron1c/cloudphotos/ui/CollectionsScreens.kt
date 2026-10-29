package io.github.drtron1c.cloudphotos.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import io.github.drtron1c.cloudphotos.ai.ModelState
import io.github.drtron1c.cloudphotos.memories.Memory
import io.github.drtron1c.cloudphotos.places.OsmMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------- shared bits

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(
        Modifier.fillMaxWidth().padding(top = top + 8.dp, start = 14.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(icon = Icons.AutoMirrored.Filled.ArrowBackIos, onDark = false, onClick = onBack)
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = p.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** A photo by id (from the library), as a cropped image. */
@Composable
fun PhotoById(vm: MainViewModel, id: String?, modifier: Modifier, medium: Boolean = false, crop: FloatArray? = null) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val asset = id?.let { vm.assetFor(it) }
    Box(modifier.background(p.placeholder)) {
        val v = asset?.let { if (medium || crop != null) it.medium ?: it.thumbnail else it.thumbnail }
        if (v != null) {
            AsyncImage(
                model = remember(v.checksum, crop?.contentToString()) {
                    ImageRequest.Builder(context).data(vm.model(v))
                        .memoryCacheKey(v.checksum + (crop?.let { "#face" + it.contentToString() } ?: ""))
                        .diskCacheKey(v.checksum)
                        .apply { if (crop != null) transformations(FaceCrop(crop)) }
                        .build()
                },
                contentDescription = null,
                imageLoader = vm.imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Crops a face (box as fractions of the picture) out of a photo, with some room around it. */
class FaceCrop(private val box: FloatArray) : Transformation() {
    override val cacheKey: String = "face:" + box.joinToString(",") { "%.3f".format(it) }

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val cx = (box[0] + box[2] / 2) * input.width
        val cy = (box[1] + box[3] / 2) * input.height
        val side = max(box[2] * input.width, box[3] * input.height) * 1.8f
        val half = (min(side, min(input.width, input.height).toFloat()) / 2)
        val x0 = (cx - half).coerceIn(0f, input.width - 2 * half).roundToInt()
        val y0 = (cy - half).coerceIn(0f, input.height - 2 * half).roundToInt()
        val s = (2 * half).roundToInt().coerceIn(1, min(input.width - x0, input.height - y0))
        return Bitmap.createBitmap(input, x0, y0, s, s)
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, text: String, button: String? = null, onClick: () -> Unit = {}) {
    val p = LocalPalette.current
    Column(
        Modifier.padding(horizontal = 18.dp, vertical = 8.dp).widthIn(max = 640.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(p.placeholder).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = p.accent)
            Spacer(Modifier.width(8.dp))
            Text(title, color = p.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(text, color = p.secondaryLabel, fontSize = 14.sp)
        if (button != null) Button(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text(button) }
    }
}

// ---------------------------------------------------------------- Memories

@Composable
fun MemoriesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ready = vm.indexMatchesLibrary
    LaunchedEffect(ready, vm.index.entries, vm.people) { vm.refreshMemories() }
    Column(Modifier.fillMaxSize().background(p.background)) {
        ScreenHeader("Memories", onBack)
        when {
            !ready -> IndexProgress(vm, null)
            vm.memoriesLoading && vm.memories.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
            vm.memories.isEmpty() -> InfoCard(
                Icons.Outlined.AutoAwesome, "No memories yet",
                "Memories are made on this device from your trips, big days, seasons and years. Keep taking photos — they'll show up here.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(330.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!vm.settings.memoriesNotify) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Tip: turn on the daily On This Day reminder in Settings › Memories.",
                            color = p.secondaryLabel, fontSize = 13.sp,
                        )
                    }
                }
                items(vm.memories, key = { it.id }) { m -> MemoryCard(vm, m, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemoryCard(vm: MainViewModel, m: Memory, modifier: Modifier, height: Dp? = null) {
    Box(
        modifier
            .then(if (height != null) Modifier.height(height) else Modifier.aspectRatio(1.5f))
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = { vm.playMemory(m) },
                onLongClick = { vm.memoryList(m)?.let { vm.openList(it) } },
            ),
    ) {
        PhotoById(vm, m.cover, Modifier.fillMaxSize(), medium = true)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(m.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(m.subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            Icons.Filled.PlayArrow, "Play", tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(30.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(4.dp),
        )
    }
}

/** A memory plays as a slideshow with a title and music. */
@Composable
fun MemoryPlayerScreen(vm: MainViewModel, memory: Memory, level: Int, onClose: () -> Unit) {
    val list = remember(memory.id) { vm.memoryList(memory) }
    if (list == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    SlideshowScreen(vm, list, 0, level = level, memory = memory, onClose = onClose)
}

// ---------------------------------------------------------------- People & Pets

@Composable
fun PeopleScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val s = vm.settings
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val version by vm.vision.version.collectAsState()
    val faceModel by vm.vision.facePack.state.collectAsState()
    val done by vm.vision.facesDone.collectAsState()
    LaunchedEffect(version, vm.indexMatchesLibrary, s.aiPeople) { vm.refreshPeople() }
    var merging by remember { mutableStateOf(false) }
    val picked = remember { mutableStateMapOf<String, Boolean>() }

    Column(Modifier.fillMaxSize().background(p.background)) {
        ScreenHeader("People & Pets", onBack) {
            if (s.aiPeople && vm.people.size > 1) {
                Pill(text = if (merging) "Cancel" else "Select", onDark = false, onClick = { merging = !merging; picked.clear() })
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom + 80.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (!s.aiPeople) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    InfoCard(
                        Icons.Outlined.Face, "Find the people in your photos",
                        "Faces are found and grouped on this device — nothing about them is sent anywhere. One-time ${megabytes(vm.vision.facePack.downloadSize)} download " +
                            "(OpenCV's YuNet and SFace models), then your library is analysed in the background. Hidden and Recently Deleted photos are never looked at.",
                        "Turn On People",
                    ) { vm.setAiPeople(true) }
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val status = when (val m = faceModel) {
                        is ModelState.Downloading -> "Downloading… ${megabytes(m.bytes)} of ${megabytes(m.total)}"
                        is ModelState.Failed -> "Download failed: ${m.message}"
                        else -> vm.aiStatus().ifBlank { "%,d photos looked at".format(done) }
                    }
                    Text(status + if (vm.peopleLoading) " · grouping…" else "", color = p.secondaryLabel, fontSize = 13.sp)
                }
                if (vm.people.isEmpty() && !vm.peopleLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "People appear once someone turns up in at least three photos. This takes a while for a big library.",
                            color = p.secondaryLabel, fontSize = 14.sp,
                        )
                    }
                }
                items(vm.people, key = { it.person.id }) { pp ->
                    val on = picked[pp.person.id] == true
                    Column(
                        Modifier.clickable {
                            if (merging) picked[pp.person.id] = !on else vm.open(Route.Person(pp.person.id))
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            PhotoById(vm, pp.coverAsset, Modifier.size(104.dp).clip(CircleShape), crop = pp.coverBox)
                            if (merging) {
                                Icon(
                                    Icons.Filled.CheckCircle, null, tint = if (on) p.accent else Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.align(Alignment.BottomEnd).size(26.dp).background(Color.White, CircleShape),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            pp.person.displayName, color = if (pp.person.name.isBlank()) p.accent else p.label, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                        Text("%,d".format(pp.assetIds.size), color = p.secondaryLabel, fontSize = 13.sp)
                    }
                }
            }
            // Pets come from Smart Search (it knows dogs and cats, though not which one).
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Text("Pets", color = p.label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (!s.aiSearch) Text("Turn on Smart Search to find the dogs and cats in your photos.", color = p.secondaryLabel, fontSize = 14.sp)
                    else if (vm.pets.isEmpty()) Text(if (vm.smartReady) "No pets found yet." else "Smart Search is still analysing your photos.", color = p.secondaryLabel, fontSize = 14.sp)
                }
            }
            for ((kind, ids) in vm.pets) {
                item(key = "pet-$kind") {
                    Column(Modifier.clickable { vm.petList(kind)?.let { vm.openList(it) } }, horizontalAlignment = Alignment.CenterHorizontally) {
                        PhotoById(vm, ids.lastOrNull(), Modifier.size(104.dp).clip(CircleShape))
                        Spacer(Modifier.height(6.dp))
                        Text(if (kind == "dogs") "Dogs" else "Cats", color = p.label, fontSize = 15.sp)
                        Text("%,d".format(ids.size), color = p.secondaryLabel, fontSize = 13.sp)
                    }
                }
            }
        }
        if (merging) {
            val chosen = picked.filterValues { it }.keys.toList()
            Row(Modifier.fillMaxWidth().background(p.bar).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottom + 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (chosen.isEmpty()) "Pick the same person twice or more" else "${chosen.size} selected", color = p.label, modifier = Modifier.weight(1f))
                TextButton(enabled = chosen.size >= 2, onClick = {
                    val into = chosen.firstOrNull { id -> vm.people.firstOrNull { it.person.id == id }?.person?.name?.isNotBlank() == true } ?: chosen.first()
                    chosen.filter { it != into }.forEach { vm.mergePeople(into, it) }
                    merging = false; picked.clear()
                }) { Text("Same Person") }
            }
        }
    }
}

@Composable
fun PersonScreen(vm: MainViewModel, id: String, level: Int, covered: Boolean, onBack: () -> Unit) {
    val list = remember(id, vm.people) { vm.personList(id) }
    val person = vm.people.firstOrNull { it.person.id == id }
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var mergeWith by remember { mutableStateOf(false) }
    if (list == null || person == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    PhotoGrid(
        vm = vm,
        list = list,
        isRoot = false,
        onBack = onBack,
        title = person.person.name.ifBlank { "Unnamed Person" },
        keyLevel = level,
        keysEnabled = !covered,
        headerActions = {
            Box {
                Pill(icon = Icons.Filled.MoreHoriz, onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (person.person.name.isBlank()) "Add Name…" else "Rename…") }, onClick = { menu = false; naming = true })
                    DropdownMenuItem(text = { Text("Play Memory") }, onClick = { menu = false; vm.playAsMemory(person.person.name.ifBlank { "Someone Special" }, "Over the years", person.assetIds) })
                    DropdownMenuItem(text = { Text("Same Person As…") }, onClick = { menu = false; mergeWith = true })
                    DropdownMenuItem(text = { Text("Hide from People") }, onClick = { menu = false; vm.hidePerson(id); onBack() })
                }
            }
            Spacer(Modifier.width(8.dp))
        },
    )
    if (naming) {
        var name by remember { mutableStateOf(person.person.name) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { naming = false; vm.renamePerson(id, name) }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
    if (mergeWith) {
        val others = vm.people.filter { it.person.id != id }
        AlertDialog(
            onDismissRequest = { mergeWith = false },
            title = { Text("Same person as…") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(others, key = { it.person.id }) { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { mergeWith = false; vm.mergePeople(id, o.person.id) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PhotoById(vm, o.coverAsset, Modifier.size(44.dp).clip(CircleShape), crop = o.coverBox)
                            Spacer(Modifier.width(12.dp))
                            Text(o.person.displayName + " · " + o.assetIds.size)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { mergeWith = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Places

@Composable
fun PlacesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val s = vm.settings
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ready = vm.indexMatchesLibrary
    LaunchedEffect(ready, vm.index.entries) { vm.refreshPlaces() }
    var showMap by remember { mutableStateOf(s.placesMap) }
    Column(Modifier.fillMaxSize().background(p.background)) {
        ScreenHeader("Places", onBack) {
            if (s.placesMap) Pill(text = if (showMap) "List" else "Map", onDark = false, onClick = { showMap = !showMap })
        }
        if (!ready) { IndexProgress(vm, null); return@Column }
        if (showMap && s.placesMap) {
            val points = remember(vm.index.entries) { vm.mapPoints() }
            OsmMap(
                points = points,
                geo = vm.geoOrNull(),
                imageLoader = vm.imageLoader,
                thumbnail = { id ->
                    val a = vm.assetFor(id)
                    a?.let { thumbRequest(vm, it) }
                },
                onOpen = { ids ->
                    val first = points.firstOrNull { it.id == ids.first() }
                    val label = first?.let { vm.placeLabel(it.lat, it.lon) } ?: "Places"
                    vm.idsList("place:${ids.first()}:${ids.size}", label, ids, "place", keepOrder = false)?.let { vm.openList(it) }
                },
                modifier = Modifier.fillMaxSize().padding(bottom = bottom),
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 24.dp)) {
            if (!s.placesMap) {
                item {
                    InfoCard(
                        Icons.Outlined.Map, "Show a map?",
                        "Places below are named on this device (no server needed). A map needs map pictures (OpenStreetMap data, served by CARTO): " +
                            "their servers see which map areas you look at — never your photos. Place names on the map are in English.",
                        "Show Map",
                    ) { s.placesMap = true; showMap = true }
                }
            }
            if (vm.places.isEmpty()) {
                item { Text("No photos with a location yet.", color = p.secondaryLabel, modifier = Modifier.padding(18.dp)) }
            }
            items(vm.places, key = { it.label }) { g ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { vm.idsList("place:" + g.label, g.label, g.ids, "place", keepOrder = false)?.let { vm.openList(it) } }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhotoById(vm, g.ids.firstOrNull(), Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.label, color = p.label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("%,d".format(g.ids.size) + if (g.ids.size == 1) " photo" else " photos", color = p.secondaryLabel, fontSize = 13.sp)
                    }
                    Icon(Icons.Outlined.Place, null, tint = p.secondaryLabel)
                }
            }
            item {
                Text(
                    "Place names: GeoNames (CC BY 4.0). Map: © OpenStreetMap contributors © CARTO.",
                    color = p.secondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(18.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Similar photos

@Composable
fun SimilarScreen(vm: MainViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val analysed by vm.smart.indexed.collectAsState()
    LaunchedEffect(vm.indexMatchesLibrary, analysed / 200) { vm.refreshSimilar() }
    var confirmAll by remember { mutableStateOf(false) }
    // Per group: ids to keep (default: the best one).
    val keep = remember { mutableStateMapOf<Int, Set<String>>() }
    val groups = vm.similar

    Column(Modifier.fillMaxSize().background(p.background)) {
        ScreenHeader("Similar Photos", onBack) {
            if (!groups.isNullOrEmpty()) Pill(text = "Clean Up All", onDark = false, onClick = { confirmAll = true })
        }
        when {
            !vm.settings.aiSearch -> InfoCard(
                Icons.Outlined.PhotoFilter, "Find near-identical photos",
                "Bursts and retakes are found on this device by Smart Search, then you choose what to keep. Exact copies are in Albums › Duplicates.",
                "Turn On Smart Search",
            ) { vm.setAiSearch(true) }
            !vm.smartReady || groups == null -> Column(Modifier.padding(18.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
                Text("Smart Search is analysing your photos (${"%,d".format(analysed)} so far)…", color = p.secondaryLabel)
            }
            groups.isEmpty() -> Text("No similar photos found.", color = p.secondaryLabel, modifier = Modifier.padding(18.dp))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = bottom + 24.dp)) {
                item {
                    Text(
                        "${groups.size} groups. The photo marked Keep stays; tap others to keep them too. Deleted photos go to Recently Deleted for 30 days.",
                        color = p.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                }
                items(groups.size, key = { i -> groups[i].joinToString(",").hashCode() }) { gi ->
                    val g = groups[gi]
                    val kept = keep[gi] ?: setOf(vm.bestOf(g))
                    val toDelete = g.filter { it !in kept }
                    Column(Modifier.padding(vertical = 8.dp)) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(g, key = { it }) { id ->
                                val k = id in kept
                                Box(
                                    Modifier.size(104.dp).clip(RoundedCornerShape(10.dp))
                                        .border(if (k) 3.dp else 0.dp, if (k) p.accent else Color.Transparent, RoundedCornerShape(10.dp))
                                        .clickable { keep[gi] = if (k) kept - id else kept + id },
                                ) {
                                    PhotoById(vm, id, Modifier.fillMaxSize())
                                    Text(
                                        if (k) "Keep" else "Delete",
                                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                                            .background(if (k) p.accent else Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.idsList("similar:$gi", "Similar", g)?.let { vm.openList(it) } }) { Text("View") }
                            Spacer(Modifier.weight(1f))
                            TextButton(enabled = toDelete.isNotEmpty() && kept.isNotEmpty(), onClick = { vm.deleteIds(toDelete) { keep.remove(gi) } }) {
                                Text(if (toDelete.size == 1) "Delete 1" else "Delete ${toDelete.size}", color = p.destructive)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmAll && groups != null) {
        val all = groups.flatMapIndexed { gi, g -> val kept = keep[gi] ?: setOf(vm.bestOf(g)); g.filter { it !in kept } }
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Delete ${all.size} similar photos?") },
            text = { Text("The best photo of each group is kept. The others go to Recently Deleted on all your devices for 30 days.") },
            confirmButton = { TextButton(onClick = { confirmAll = false; vm.deleteIds(all) { keep.clear() } }) { Text("Delete", color = p.destructive) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

/** For the Albums tab and sidebar icons. */
val PetsIcon: ImageVector get() = Icons.Outlined.Pets
