package io.github.drtron1c.cloudphotos.photos

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import java.io.File
import java.io.IOException

/**
 * Files for dragging into other apps, the clipboard, "Most Compatible" sharing and exports.
 * Everything stays in the app's private cache until you hand it to another app yourself.
 */
object LocalCopies {
    /** Dragging / copying from the viewer: what's already on screen, straight from the image cache (instant, no download). */
    fun cachedCopy(context: Context, imageLoader: ImageLoader, asset: Asset, model: (Version) -> Any): Pair<Uri, String>? {
        val dir = File(context.cacheDir, "drag").apply { mkdirs() }
        // Keep only the last few: these are tiny-lived hand-offs.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
        val candidates = listOfNotNull(asset.displayImage, asset.medium, asset.thumbnail).distinctBy { it.checksum }
        for (v in candidates) {
            val ext = Asset.extensionFor(v.fileType ?: if (v === asset.original) asset.itemType else null, "JPG")
            val name = asset.filename.substringBeforeLast('.').replace('/', '_') + "." + ext
            val target = File(dir, name)
            val local = model(v) as? File
            val ok = when {
                local != null && local.exists() -> { local.copyTo(target, overwrite = true); true }
                else -> imageLoader.diskCache?.openSnapshot(v.checksum)?.use { snap ->
                    File(snap.data.toString()).copyTo(target, overwrite = true); true
                } ?: false
            }
            if (ok) return FileProvider.getUriForFile(context, context.packageName + ".files", target) to Asset.mimeFor(name)
        }
        return null
    }

    fun isHeif(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("heic", "heif")

    /** HEIC → JPEG ("Most Compatible"), keeping the date, camera and location details. */
    fun toJpeg(src: File, dst: File, quality: Int = 93) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        var sample = 1
        if (bounds.outWidth > 0) while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 50_000_000L) sample *= 2
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(src)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (sample > 1) d.setTargetSampleSize(sample)
        }
        try {
            dst.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("Couldn't convert to JPEG") }
        } finally {
            bmp.recycle()
        }
        runCatching {
            val from = ExifInterface(src.path)
            val to = ExifInterface(dst.path)
            for (tag in EXIF_TAGS) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
            // The pixels are already upright.
            to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            to.saveAttributes()
        }
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_IMAGE_DESCRIPTION,
    )

    /** A video frame (or any bitmap) as a JPEG in Pictures › Cloud Photos. */
    fun saveBitmapToGallery(context: Context, bmp: Bitmap, name: String, dateTaken: Long): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cloud Photos")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (dateTaken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Couldn't create a file in your gallery")
        try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: throw IOException("Couldn't write to your gallery")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    /** A new file in a folder you picked (Export). The folder's app renames it if the name is taken. */
    fun createInTree(resolver: ContentResolver, tree: Uri, name: String, mime: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException("Couldn't create $name in that folder")
    }
}
