package io.github.drtron1c.cloudphotos.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cuts a video down to a part of it, on the device (Media3 Transformer). */
@OptIn(UnstableApi::class)
object VideoTrimmer {
    suspend fun trim(context: Context, input: File, output: File, startMs: Long, endMs: Long, onProgress: (Float) -> Unit) {
        try {
            run(context, input, output, startMs, endMs, Composition.HDR_MODE_KEEP_HDR, onProgress)
        } catch (e: ExportException) {
            // Some phones can't write HDR (iPhone Dolby Vision) video: fall back to a standard one.
            output.delete()
            run(context, input, output, startMs, endMs, Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL, onProgress)
        }
    }

    private suspend fun run(
        context: Context, input: File, output: File, startMs: Long, endMs: Long, hdrMode: Int, onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(input))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
            )
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(EditedMediaItem.Builder(item).build()))
            .setHdrMode(hdrMode)
            .build()
        val holder = ProgressHolder()
        var transformer: Transformer? = null
        val poll = launch {
            while (true) {
                val t = transformer
                if (t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                delay(300)
            }
        }
        try {
            suspendCancellableCoroutine { cont ->
                val t = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer = t
                cont.invokeOnCancellation { t.cancel() }
                t.start(composition, output.path)
            }
        } finally {
            poll.cancel()
        }
    }

    /** No iCloud upload available: the clip goes to Movies › Cloud Photos. */
    fun saveToGallery(context: Context, file: File, dateTaken: Long): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Cloud Photos")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (dateTaken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Couldn't create a file in your gallery")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("Couldn't write the video")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}
