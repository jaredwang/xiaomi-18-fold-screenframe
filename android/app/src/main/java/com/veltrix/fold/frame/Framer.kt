package com.veltrix.fold.frame

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.veltrix.fold.frame.core.DeviceProfile
import com.veltrix.fold.frame.core.Profiles
import com.veltrix.fold.frame.core.SAMPLE_WIDTH
import com.veltrix.fold.frame.core.cameraAtBottom
import com.veltrix.fold.frame.core.detectProfile
import com.veltrix.fold.frame.core.detectRotation
import com.veltrix.fold.frame.core.statusBand
import com.veltrix.fold.frame.core.statusBarMargins
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Decode, detect, frame, save. Everything here blocks: call off the main thread. */
object Framer {
    /** Gallery folder for saved images; localized, e.g. Pictures/带壳截图. */
    fun albumPath(context: Context) = "Pictures/" + context.getString(R.string.album_dir)
    private const val SCREENSHOTS = "DCIM/Screenshots/"

    fun decode(context: Context, uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
            // BitmapShader on a software canvas needs a software bitmap.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /**
     * Render [src] in the frame. [src] may be a downscaled copy for previews:
     * the frame scales with the screenshot, so the result looks identical.
     */
    fun render(src: Bitmap, profile: DeviceProfile, flipped: Boolean, options: FrameOptions): Bitmap =
        FrameRenderer.render(src, profile, detectRotation(src.width, src.height, flipped), options)

    /** Default screen + pose for a full-size screenshot; profile null = unrecognised size. */
    fun detect(src: Bitmap): Pair<DeviceProfile?, Boolean> {
        val profile = detectProfile(src.width, src.height)
        return profile to (profile === Profiles.INNER && guessFlipped(src))
    }

    /** Whether the inner screen was held camera-down, judged from the status bar. */
    fun guessFlipped(src: Bitmap): Boolean {
        val w = SAMPLE_WIDTH
        val h = src.height * w / src.width
        val band = statusBand(w, h)
        val rows = band.last - band.first + 1
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * rows)
        small.getPixels(px, 0, w, 0, band.first, w, rows)
        if (small !== src) small.recycle()
        val gray = IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3 }
        return cameraAtBottom(statusBarMargins(w, rows, gray), src.width > src.height) ?: false
    }

    /** Copy of [src] whose long side is at most [longSide]. */
    fun downscale(src: Bitmap, longSide: Int): Bitmap {
        val k = longSide.toFloat() / max(src.width, src.height)
        if (k >= 1) return src
        return Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)
    }

    fun saveToGallery(context: Context, bitmap: Bitmap, baseName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, albumPath(context))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error(context.getString(R.string.gallery_write_failed))
        try {
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Write to the share cache (not the gallery) and return a grantable URI. */
    fun saveForShare(context: Context, bitmap: Bitmap, baseName: String): Uri {
        val file = File(ShareProvider.dir(context), "$baseName.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ShareProvider.uriFor(file)
    }

    fun clearShareCache(context: Context) {
        ShareProvider.dir(context).listFiles()?.forEach { it.delete() }
    }

    /** "Screenshot_…" without extension, for naming outputs. */
    fun baseName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return (name?.substringBeforeLast('.') ?: "screenshot_${System.currentTimeMillis()}") + "_framed"
    }

    /**
     * The gallery (MediaStore) entry behind [source], or null if there is none.
     * Media URIs are used as-is; photo-picker and other apps' share URIs are
     * matched by file name + size (needs READ_MEDIA_IMAGES).
     */
    fun galleryUriFor(context: Context, source: Uri): Uri? {
        if (source.authority == MediaStore.AUTHORITY && source.pathSegments.firstOrNull() != "picker") {
            return ContentUris.parseId(source).takeIf { it > 0 }?.let { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
        }
        val (name, size) = context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0) && !it.isNull(1)) it.getString(0) to it.getLong(1) else null }
            ?: return null
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.SIZE} = ?",
            arrayOf(name, size.toString()),
            null,
        )?.use { if (it.count == 1 && it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null }
    }

    /** Screenshots newer than [afterId], newest first (needs READ_MEDIA_IMAGES). */
    fun screenshotsAfter(context: Context, afterId: Long, limit: Int): List<Pair<Long, Uri>> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media._ID} > ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(SCREENSHOTS, afterId.toString()))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media._ID} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), args, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    add(id to ContentUris.withAppendedId(collection, id))
                }
            }
        } ?: emptyList()
    }

    fun latestScreenshot(context: Context): Pair<Long, Uri>? = screenshotsAfter(context, 0, 1).firstOrNull()
}
