package io.github.drtron1c.cloudphotos.ai

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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One pinned model file on Hugging Face. */
class ModelFile(val repo: String, val revision: String, val path: String, val size: Long, val sha256: String) {
    val local get() = repo.substringAfter('/') + "_" + path.substringAfterLast('/')
}

/**
 * A set of model files downloaded once (from Hugging Face, checked against pinned checksums) and
 * then used offline. Downloading is the only time any of the on-device AI goes online.
 */
class ModelPack(val dir: File, val files: List<ModelFile>) {
    private val lock = Mutex()
    private val _state = MutableStateFlow<ModelState>(if (present()) ModelState.Ready else ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun file(m: ModelFile) = File(dir, m.local)
    fun present() = files.all { file(it).length() == it.size }
    val downloadSize: Long get() = files.sumOf { it.size }

    suspend fun download(http: OkHttpClient): Boolean = lock.withLock {
        if (present()) { _state.value = ModelState.Ready; return@withLock true }
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val total = downloadSize
            var done = files.filter { file(it).length() == it.size }.sumOf { it.size }
            try {
                val client = http.newBuilder().cache(null).readTimeout(60, TimeUnit.SECONDS).build()
                for (m in files) {
                    val target = file(m)
                    if (target.length() == m.size) continue
                    val part = File(dir, m.local + ".part")
                    val request = Request.Builder().url("https://huggingface.co/${m.repo}/resolve/${m.revision}/${m.path}")
                        .header("User-Agent", "CloudPhotos").build()
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
                                    if (done - last > 256 * 1024) {
                                        last = done
                                        _state.value = ModelState.Downloading(done, total)
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
                _state.value = ModelState.Ready
                true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = ModelState.Failed(e.message ?: "Download failed")
                false
            }
        }
    }

    fun delete() {
        files.forEach { file(it).delete(); File(dir, it.local + ".part").delete() }
        _state.value = ModelState.Missing
    }
}
