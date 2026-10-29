package io.github.drtron1c.cloudphotos.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import io.github.drtron1c.cloudphotos.net.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.text.Normalizer
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A face found in a photo: box as fractions of the picture (x, y, w, h) and its fingerprint. */
class FaceRecord(val box: FloatArray, val score: Float, val emb: Packed)

/** Someone who keeps turning up in your photos. [protos] are fingerprints that stand for them. */
class Person(val id: String, var name: String, var hidden: Boolean, val protos: MutableList<FloatArray>) {
    val displayName get() = name.ifBlank { "Add Name" }
}

/** A person with the photos they're in (newest analysed first) and the best face to show. */
class PersonPhotos(val person: Person, val assetIds: List<String>, val coverAsset: String, val coverBox: FloatArray)

/**
 * The on-device "understanding" of your photos beyond Smart Search: faces (People & Pets) and the
 * text in them. Models are downloaded once; everything learned is stored encrypted in the app's
 * private storage and never leaves the device. Hidden and Recently Deleted items are never
 * analysed, and anything learned about a photo is dropped as soon as you hide or delete it.
 */
class Vision(context: Context) {
    private val dir = File(context.filesDir, "ai").apply { mkdirs() }
    private val facesFile = File(dir, "faces.bin")
    private val peopleFile = File(dir, "people.bin")
    private val textFile = File(dir, "text.bin")

    val facePack = ModelPack(
        dir,
        listOf(
            ModelFile(FacePins.DET_REPO, FacePins.DET_REV, FacePins.DET_FILE, FacePins.DET_SIZE, FacePins.DET_SHA),
            ModelFile(FacePins.REC_REPO, FacePins.REC_REV, FacePins.REC_FILE, FacePins.REC_SIZE, FacePins.REC_SHA),
        ),
    )
    val textPack = ModelPack(
        dir,
        listOf(
            ModelFile(TextPins.REPO, TextPins.REV, TextPins.DET_FILE, TextPins.DET_SIZE, TextPins.DET_SHA),
            ModelFile(TextPins.REPO, TextPins.REV, TextPins.REC_FILE, TextPins.REC_SIZE, TextPins.REC_SHA),
        ),
    )

    private val lock = Mutex()
    @Volatile private var faceModel: FaceModel? = null
    @Volatile private var textModel: TextModel? = null
    private var faces: HashMap<String, List<FaceRecord>>? = null
    private var texts: HashMap<String, String>? = null
    private var people: MutableList<Person>? = null

    /** Bumped whenever faces, people or text change (screens refresh on it). */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private val _facesDone = MutableStateFlow(0)
    val facesDone: StateFlow<Int> = _facesDone.asStateFlow()
    private val _textDone = MutableStateFlow(0)
    val textDone: StateFlow<Int> = _textDone.asStateFlow()

    // ---------- models ----------

    fun faceModel(): FaceModel = faceModel ?: synchronized(this) {
        faceModel ?: FaceModel(facePack.file(facePack.files[0]), facePack.file(facePack.files[1])).also { faceModel = it }
    }

    fun textModel(): TextModel = textModel ?: synchronized(this) {
        textModel ?: TextModel(textPack.file(textPack.files[0]), textPack.file(textPack.files[1])).also { textModel = it }
    }

    // ---------- loading / saving ----------

    suspend fun load() = lock.withLock {
        if (faces != null) return@withLock
        withContext(Dispatchers.IO) {
            faces = readFaces()
            texts = readTexts()
            people = readPeople()
        }
        _facesDone.value = faces!!.size
        _textDone.value = texts!!.size
        _version.value++
    }

    private fun readFaces(): HashMap<String, List<FaceRecord>> {
        val out = HashMap<String, List<FaceRecord>>()
        if (!facesFile.exists()) return out
        runCatching {
            val d = DataInputStream(Crypto.decrypt(facesFile.readBytes()).inputStream())
            check(d.readInt() == FACES_MAGIC)
            repeat(d.readInt()) {
                val id = d.readUTF()
                val n = d.readInt()
                out[id] = List(n) {
                    val box = FloatArray(4) { d.readFloat() }
                    val score = d.readFloat()
                    val scale = d.readFloat()
                    val q = ByteArray(d.readInt()).also { d.readFully(it) }
                    FaceRecord(box, score, Packed(q, scale))
                }
            }
        }
        return out
    }

    private fun readTexts(): HashMap<String, String> {
        val out = HashMap<String, String>()
        if (!textFile.exists()) return out
        runCatching {
            val d = DataInputStream(Crypto.decrypt(textFile.readBytes()).inputStream())
            check(d.readInt() == TEXT_MAGIC)
            repeat(d.readInt()) { out[d.readUTF()] = d.readUTF() }
        }
        return out
    }

    private fun readPeople(): MutableList<Person> {
        if (!peopleFile.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(String(Crypto.decrypt(peopleFile.readBytes())))
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val protos = o.getJSONArray("p")
                Person(
                    o.getString("id"), o.optString("n"), o.optBoolean("h"),
                    MutableList(protos.length()) { k ->
                        val b = java.util.Base64.getDecoder().decode(protos.getString(k))
                        val fb = ByteBuffer.wrap(b).asFloatBuffer()
                        FloatArray(fb.remaining()).also { fb.get(it) }
                    },
                )
            }
        }.getOrDefault(mutableListOf())
    }

    suspend fun save() = lock.withLock {
        val f = faces ?: return@withLock
        val t = texts ?: HashMap()
        val p = people ?: mutableListOf()
        withContext(Dispatchers.IO) {
            val fs = synchronized(f) { HashMap(f) }
            ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { d ->
                    d.writeInt(FACES_MAGIC)
                    d.writeInt(fs.size)
                    for ((id, list) in fs) {
                        d.writeUTF(id)
                        d.writeInt(list.size)
                        for (r in list) {
                            r.box.forEach { d.writeFloat(it) }
                            d.writeFloat(r.score)
                            d.writeFloat(r.emb.scale)
                            d.writeInt(r.emb.q.size)
                            d.write(r.emb.q)
                        }
                    }
                }
                write(facesFile, bytes.toByteArray())
            }
            val ts = synchronized(t) { HashMap(t) }
            ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { d ->
                    d.writeInt(TEXT_MAGIC)
                    d.writeInt(ts.size)
                    for ((id, s) in ts) { d.writeUTF(id); d.writeUTF(s.take(8000)) }
                }
                write(textFile, bytes.toByteArray())
            }
            val arr = JSONArray()
            synchronized(p) {
                for (person in p) {
                    val protos = JSONArray()
                    for (v in person.protos.takeLast(MAX_PROTOS)) {
                        val bb = ByteBuffer.allocate(v.size * 4)
                        bb.asFloatBuffer().put(v)
                        protos.put(java.util.Base64.getEncoder().encodeToString(bb.array()))
                    }
                    arr.put(JSONObject().put("id", person.id).put("n", person.name).put("h", person.hidden).put("p", protos))
                }
            }
            write(peopleFile, arr.toString().toByteArray())
        }
        _facesDone.value = f.size
        _textDone.value = t.size
        _version.value++
    }

    private fun write(target: File, plain: ByteArray) {
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(Crypto.encrypt(plain))
        tmp.renameTo(target)
    }

    // ---------- analysing ----------

    fun hasFaces(id: String) = faces?.containsKey(id) == true
    fun hasText(id: String) = texts?.containsKey(id) == true

    /**
     * Analyses one photo (its medium-size JPEG). Returns false if the picture couldn't be read.
     */
    fun analyse(id: String, jpeg: ByteArray, wantFaces: Boolean, wantText: Boolean): Boolean {
        val bmp = decode(jpeg, 1600) ?: return false
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        if (wantFaces) {
            val m = faceModel()
            val found = m.detect(px, w, h).filter { it.score >= 0.85f && min(it.w, it.h) >= max(36f, 0.03f * max(w, h)) }.take(12)
            val records = found.map { f ->
                FaceRecord(floatArrayOf(f.x / w, f.y / h, f.w / w, f.h / h), f.score, Packed.of(m.embed(px, w, h, f)))
            }
            faces?.let { synchronized(it) { it[id] = records } }
        }
        if (wantText) {
            val lines = textModel().read(px, w, h)
            texts?.let { synchronized(it) { it[id] = lines.joinToString("\n") { l -> l.text } } }
        }
        return true
    }

    /** Text of one photo read right now (the viewer's "Text in Photo"), and remembered. */
    fun readNow(id: String, jpeg: ByteArray): List<String>? {
        val bmp = decode(jpeg, 2400) ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val lines = textModel().read(px, w, h, maxSide = 1280).map { it.text }
        texts?.let { synchronized(it) { it[id] = lines.joinToString("\n") } }
        return lines
    }

    fun textOf(id: String): List<String>? = texts?.get(id)?.let { s -> if (s.isEmpty()) emptyList() else s.split('\n') }

    /** Photos whose text has every word of [query] (ignoring case and accents). */
    fun searchText(query: String, among: Collection<String>): List<String> {
        val words = fold(query).split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        val t = texts ?: return emptyList()
        val snapshot = synchronized(t) { HashMap(t) }
        return among.filter { id -> snapshot[id]?.let { text -> val f = fold(text); words.all { f.contains(it) } } == true }
    }

    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    // ---------- people ----------

    /**
     * Groups faces into people: each face joins the person it looks most like; faces that match
     * nobody but each other (3+ photos) become someone new. Names you gave are kept.
     */
    suspend fun people(visible: Set<String>): List<PersonPhotos> = withContext(Dispatchers.Default) {
        load()
        val f = faces ?: return@withContext emptyList()
        val ppl = people ?: return@withContext emptyList()
        val all = synchronized(f) { f.entries.filter { it.key in visible }.flatMap { (id, list) -> list.map { id to it } } }
        val unit = all.map { (_, r) -> FloatArray(r.emb.q.size) { r.emb.q[it] * r.emb.scale } }
        fun best(v: FloatArray): Pair<Person?, Float> {
            var bp: Person? = null
            var bs = -1f
            for (p in ppl) for (proto in p.protos) {
                val s = dot(v, proto)
                if (s > bs) { bs = s; bp = p }
            }
            return bp to bs
        }
        val assigned = HashMap<Person, MutableList<Int>>()
        val loose = ArrayList<Int>()
        for (i in all.indices) {
            val (p, s) = best(unit[i])
            if (p != null && s >= JOIN) assigned.getOrPut(p) { ArrayList() } += i else loose += i
        }
        // Leader clustering of the faces that matched nobody.
        val groups = ArrayList<Pair<FloatArray, MutableList<Int>>>()
        for (i in loose) {
            var bi = -1
            var bs = -1f
            groups.forEachIndexed { gi, g -> val s = dot(unit[i], g.first); if (s > bs) { bs = s; bi = gi } }
            if (bi >= 0 && bs >= NEW_PERSON) {
                val g = groups[bi]
                g.second += i
                val n = g.second.size
                val c = FloatArray(g.first.size) { k -> (g.first[k] * (n - 1) + unit[i][k]) / n }
                groups[bi] = normalize(c) to g.second
            } else groups += unit[i].copyOf() to mutableListOf(i)
        }
        var changed = false
        for ((centroid, members) in groups) {
            val photos = members.map { all[it].first }.toSet()
            if (photos.size < MIN_PHOTOS) continue
            val p = Person(UUID.randomUUID().toString(), "", false, mutableListOf(centroid))
            synchronized(ppl) { ppl += p }
            assigned[p] = members
            changed = true
        }
        // Each person's own fingerprint follows their faces (a few prototypes per person).
        for ((p, members) in assigned) {
            if (members.size < 2) continue
            val mean = FloatArray(unit[members[0]].size)
            for (i in members) for (k in mean.indices) mean[k] += unit[i][k]
            val c = normalize(mean)
            if (p.protos.none { dot(it, c) > 0.97f }) {
                if (p.protos.size >= MAX_PROTOS) p.protos[0] = c else p.protos += c
                changed = true
            }
        }
        if (changed) save()
        assigned.entries
            .filter { !it.key.hidden }
            .map { (p, members) ->
                val ids = members.map { all[it].first }.distinct()
                val cover = members.maxBy { all[it].second.score * all[it].second.box[2] }
                PersonPhotos(p, ids, all[cover].first, all[cover].second.box)
            }
            .filter { it.person.name.isNotBlank() || it.assetIds.size >= MIN_PHOTOS }
            .sortedWith(compareBy({ it.person.name.isBlank() }, { -it.assetIds.size }))
    }

    /** Names of the people in one photo (for Info and search). */
    fun namesIn(id: String): List<String> {
        val list = faces?.get(id) ?: return emptyList()
        val ppl = people ?: return emptyList()
        return list.mapNotNull { r ->
            val v = FloatArray(r.emb.q.size) { r.emb.q[it] * r.emb.scale }
            var bp: Person? = null
            var bs = -1f
            for (p in ppl) for (proto in p.protos) { val s = dot(v, proto); if (s > bs) { bs = s; bp = p } }
            bp?.takeIf { bs >= JOIN && it.name.isNotBlank() && !it.hidden }?.name
        }.distinct()
    }

    fun person(id: String): Person? = people?.firstOrNull { it.id == id }
    fun namedPeople(): List<Person> = people?.filter { it.name.isNotBlank() && !it.hidden }.orEmpty()

    suspend fun rename(id: String, name: String) { person(id)?.name = name.trim(); save() }
    suspend fun hide(id: String) { person(id)?.hidden = true; save() }
    suspend fun unhideAll() { people?.forEach { it.hidden = false }; save() }

    /** "Same person": [other] is folded into [into]. */
    suspend fun merge(into: String, other: String) {
        val a = person(into) ?: return
        val b = person(other) ?: return
        if (a === b) return
        a.protos += b.protos
        if (a.name.isBlank()) a.name = b.name
        people?.let { synchronized(it) { it.remove(b) } }
        save()
    }

    // ---------- forgetting ----------

    /** Hidden / deleted photos: forget their faces and text straight away. */
    suspend fun forget(ids: Set<String>) {
        if (ids.isEmpty()) return
        load()
        var changed = false
        faces?.let { synchronized(it) { if (it.keys.removeAll(ids)) changed = true } }
        texts?.let { synchronized(it) { if (it.keys.removeAll(ids)) changed = true } }
        if (changed) save()
    }

    /** After a full pass over the library: drop photos that are gone (or now hidden / deleted). */
    suspend fun retainOnly(ids: Set<String>) {
        load()
        faces?.let { synchronized(it) { it.keys.retainAll(ids) } }
        texts?.let { synchronized(it) { it.keys.retainAll(ids) } }
        save()
    }

    fun deleteFaces() {
        faceModel?.close(); faceModel = null
        faces?.let { synchronized(it) { it.clear() } }
        people?.let { synchronized(it) { it.clear() } }
        facesFile.delete(); peopleFile.delete()
        facePack.delete()
        _facesDone.value = 0
        _version.value++
    }

    fun deleteText() {
        textModel?.close(); textModel = null
        texts?.let { synchronized(it) { it.clear() } }
        textFile.delete()
        textPack.delete()
        _textDone.value = 0
        _version.value++
    }

    companion object {
        private const val FACES_MAGIC = 0x43504631 // "CPF1"
        private const val TEXT_MAGIC = 0x43505431  // "CPT1"
        /** Same person (SFace: same face ≈ 0.9, different people mostly < 0.3). */
        const val JOIN = 0.45f
        const val NEW_PERSON = 0.5f
        const val MIN_PHOTOS = 3
        const val MAX_PROTOS = 8

        /** Upright picture (EXIF rotation applied), longest side at most [maxSide]. */
        fun decode(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = info.size
                val scale = min(1f, maxSide.toFloat() / max(s.width, s.height))
                if (scale < 1f) d.setTargetSize(max(1, (s.width * scale).roundToInt()), max(1, (s.height * scale).roundToInt()))
            }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        }.getOrNull()
    }
}
