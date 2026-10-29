package io.github.drtron1c.cloudphotos.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.drtron1c.cloudphotos.CloudPhotosApp
import io.github.drtron1c.cloudphotos.net.Crypto
import io.github.drtron1c.cloudphotos.photos.AlbumSpec
import io.github.drtron1c.cloudphotos.settings.AppSettings
import io.github.drtron1c.cloudphotos.settings.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.max

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val bytes: Long, val total: Long) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * On-device AI search ("red hat", "dog on a beach"): a MobileCLIP model looks at each photo's
 * thumbnail on this device and remembers a 512-number "meaning" for it; a search phrase gets the
 * same treatment and the closest photos win. Photos and searches never leave the device — the
 * only download is the model itself, once, from Hugging Face.
 */
class SmartSearch(private val context: Context, private val settings: AppSettings) {
    private val dir = File(context.filesDir, "ai").apply { mkdirs() }
    private val indexFile = File(dir, "index.bin")

    private val _model = MutableStateFlow<ModelState>(if (filesPresent()) ModelState.Ready else ModelState.Missing)
    val modelState: StateFlow<ModelState> = _model.asStateFlow()
    private val _indexed = MutableStateFlow(0)
    /** How many photos have been analysed. */
    val indexed: StateFlow<Int> = _indexed.asStateFlow()

    private val lock = Mutex()
    @Volatile private var model: ClipModel? = null
    @Volatile private var index: HashMap<String, Packed>? = null

    // ---------- model files ----------

    private class ModelFile(val path: String, val size: Long, val sha256: String) {
        val local get() = path.substringAfterLast('/')
    }

    private fun file(m: ModelFile) = File(dir, m.local)

    fun filesPresent() = FILES.all { file(it).length() == it.size }

    val downloadSize: Long get() = FILES.sumOf { it.size }

    /** Downloads (and checks) the model, ~90 MB. */
    suspend fun downloadModel(http: OkHttpClient): Boolean = withContext(Dispatchers.IO) {
        val total = downloadSize
        var done = FILES.filter { file(it).length() == it.size }.sumOf { it.size }
        try {
            for (m in FILES) {
                val target = file(m)
                if (target.length() == m.size) continue
                val part = File(dir, m.local + ".part")
                val request = Request.Builder().url("https://huggingface.co/$REPO/resolve/$REVISION/${m.path}")
                    .header("User-Agent", "CloudPhotos").build()
                val client = http.newBuilder().cache(null).readTimeout(60, TimeUnit.SECONDS).build()
                client.newCall(request).execute().use { r ->
                    check(r.isSuccessful) { "Download failed (HTTP ${r.code})" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    r.body!!.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(128 * 1024)
                            var last = 0L
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                if (done - last > 512 * 1024) {
                                    last = done
                                    _model.value = ModelState.Downloading(done, total)
                                }
                            }
                        }
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (sha != m.sha256) {
                        part.delete()
                        error("The downloaded model didn't match (checksum).")
                    }
                }
                part.renameTo(target)
            }
            _model.value = ModelState.Ready
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _model.value = ModelState.Failed(e.message ?: "Download failed")
            false
        }
    }

    /** Loads the model (once). */
    fun model(): ClipModel {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            val tok = ClipTokenizer.fromTokenizerJson(file(FILES[0]).readText())
            val m = ClipModel(file(FILES[1]), file(FILES[2]), tok, threads = max(2, Runtime.getRuntime().availableProcessors() - 2))
            model = m
            return m
        }
    }

    // ---------- index ----------

    /** Stored encrypted: id, scale and 512 signed bytes per photo. */
    suspend fun index(): HashMap<String, Packed> = lock.withLock {
        index ?: withContext(Dispatchers.IO) { readIndex() }.also { index = it; _indexed.value = it.size }
    }

    private fun readIndex(): HashMap<String, Packed> {
        val out = HashMap<String, Packed>()
        if (!indexFile.exists()) return out
        runCatching {
            val data = DataInputStream(Crypto.decrypt(indexFile.readBytes()).inputStream())
            check(data.readInt() == MAGIC)
            val n = data.readInt()
            val dim = data.readInt()
            repeat(n) {
                val id = data.readUTF()
                val scale = data.readFloat()
                val q = ByteArray(dim)
                data.readFully(q)
                out[id] = Packed(q, scale)
            }
        }
        return out
    }

    suspend fun saveIndex() = lock.withLock {
        val idx = index ?: return@withLock
        withContext(Dispatchers.IO) {
            val snapshot = synchronized(idx) { HashMap(idx) }
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { d ->
                d.writeInt(MAGIC)
                d.writeInt(snapshot.size)
                d.writeInt(snapshot.values.firstOrNull()?.q?.size ?: 512)
                for ((id, p) in snapshot) {
                    d.writeUTF(id)
                    d.writeFloat(p.scale)
                    d.write(p.q)
                }
            }
            val tmp = File(dir, "index.tmp")
            tmp.writeBytes(Crypto.encrypt(bytes.toByteArray()))
            tmp.renameTo(indexFile)
            _indexed.value = snapshot.size
        }
    }

    /** Analyse one picture (thumbnail bytes) for the index. */
    fun embedPicture(bytes: ByteArray): FloatArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ImagePrep.SIZE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val chw = ImagePrep.toChw(px, bmp.width, bmp.height)
        bmp.recycle()
        return model().embedImage(chw)
    }

    /**
     * Scores [ids] for [phrase]; returns the good matches best first (or the closest few when
     * nothing is a clear match, flagged by the second value).
     */
    suspend fun rank(phrase: String, ids: Collection<String>): Pair<List<Pair<String, Float>>, Boolean> = withContext(Dispatchers.Default) {
        val idx = index()
        val q = model().embedText("a photo of $phrase")
        val scored = ids.mapNotNull { id -> idx[id]?.let { id to it.similarity(q) } }.sortedByDescending { it.second }
        if (scored.isEmpty()) return@withContext emptyList<Pair<String, Float>>() to false
        val top = scored.first().second
        val cutoff = max(MIN_SCORE, top - SPREAD)
        val good = scored.takeWhile { it.second >= cutoff }.take(MAX_RESULTS)
        if (good.isNotEmpty()) good to true else scored.take(FALLBACK_RESULTS) to false
    }

    /** Hidden or deleted photos: drop what was learned about them straight away. */
    suspend fun forget(ids: Set<String>) {
        if (ids.isEmpty()) return
        val idx = index()
        val removed = synchronized(idx) { idx.keys.removeAll(ids) }
        if (removed) saveIndex()
    }

    fun deleteAll() {
        model?.close()
        model = null
        index = null
        _indexed.value = 0
        dir.listFiles()?.forEach { it.delete() }
        _model.value = ModelState.Missing
    }

    companion object {
        private const val MAGIC = 0x43504131 // "CPA1"
        const val REPO = "Xenova/mobileclip_s0"
        /** Pinned: the files below were checked on CI against this exact revision. */
        const val REVISION = "757d59c9c6870a76a4b0306f05f5061bca15c39f"
        private val FILES = listOf(
            ModelFile("tokenizer.json", 2_224_081, "72ed5c96db5729294468543e4bc75fce14ca63f58e37300290189ba1c1e52b85"),
            ModelFile("onnx/vision_model.onnx", 45_543_630, "17d3c037b1d488c10c50e09f6009ea5a198caef4e0e8f4ea5617b7cb2d067ac0"),
            ModelFile("onnx/text_model_quantized.onnx", 42_799_238, "b8557b10e5c23a0126c6d2e6eba48d240484979007917d128953b31618a04211"),
        )
        /** Measured on CI: matching picture ≈ 0.25, unrelated ≈ 0.05–0.14. */
        private const val MIN_SCORE = 0.17f
        private const val SPREAD = 0.075f
        private const val MAX_RESULTS = 1500
        private const val FALLBACK_RESULTS = 24
    }
}

/** Analyses library photos in the background (newest first), a few minutes at a time. */
class SmartIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as CloudPhotosApp).graph
        val settings = graph.settings
        val smart = graph.smart
        fun status(s: String) = settings.raw().edit().putString(Keys.AI_STATUS, s).apply()
        if (!settings.aiSearch) return Result.success()
        if (!smart.filesPresent()) {
            status("Downloading the AI model…")
            if (!smart.downloadModel(graph.http)) return Result.retry()
        }
        val signedIn = runCatching { graph.auth.isSignedIn && graph.auth.resume() }.getOrDefault(false)
        if (!signedIn) {
            status("Waiting for you to sign in")
            return Result.success()
        }
        val started = System.currentTimeMillis()
        val idx = smart.index()
        val total = runCatching { graph.photos.count(AlbumSpec.LIBRARY) }.getOrElse { return Result.retry() }
        var start = 0
        var added = 0
        val seen = HashSet<String>()
        while (start < total) {
            val page = runCatching { graph.photos.page(AlbumSpec.LIBRARY, start, 100) }.getOrElse { smart.saveIndex(); return Result.retry() }
            if (page.isEmpty()) break
            start += page.size
            for (asset in page) {
                seen += asset.id
                if (idx.containsKey(asset.id)) continue
                val thumb = asset.thumbnail ?: continue
                val bytes = runCatching {
                    graph.http.newCall(Request.Builder().url(thumb.url).build()).execute().use { r ->
                        if (r.isSuccessful) r.body?.bytes() else null
                    }
                }.getOrNull() ?: continue
                val e = runCatching { smart.embedPicture(bytes) }.getOrNull() ?: continue
                synchronized(idx) { idx[asset.id] = Packed.of(e) }
                added++
                if (added % 50 == 0) {
                    smart.saveIndex()
                    status("Analysing photos on this device… %,d of %,d".format(idx.size, total))
                }
                if (System.currentTimeMillis() - started > 8 * 60_000L) {
                    smart.saveIndex()
                    status("Analysing photos on this device… %,d of %,d".format(idx.size, total))
                    return Result.retry()
                }
            }
        }
        // A full pass: forget photos that were deleted.
        if (start >= total) synchronized(idx) { idx.keys.retainAll(seen) }
        smart.saveIndex()
        status("Ready · %,d photos analysed".format(idx.size))
        return Result.success()
    }

    companion object {
        private const val NAME = "smart-index"

        fun start(context: Context, settings: AppSettings) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(settings.aiChargingOnly)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SmartIndexWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
