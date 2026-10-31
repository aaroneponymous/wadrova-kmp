package com.example.wadrova.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stores photos under noBackupFilesDir, which Android excludes from Auto Backup. */
class AndroidGarmentPhotoStore(context: Context) : GarmentPhotoStore {
    private val resolver = context.applicationContext.contentResolver
    private val root = File(context.applicationContext.noBackupFilesDir, "garment_photos")

    override suspend fun import(photo: PickedPhoto): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val staging = File(root, "$id.tmp")
        try {
            val display = decodeOriented(Uri.parse(photo.location))
            try {
                staging.mkdirs()
                write(display, File(staging, PhotoSize.DISPLAY.fileName))
                val thumbnail = display.scaledToFit(PhotoSize.THUMBNAIL.maxSide)
                write(thumbnail, File(staging, PhotoSize.THUMBNAIL.fileName))
                if (thumbnail !== display) thumbnail.recycle()
            } finally {
                display.recycle()
            }
            if (!staging.renameTo(File(root, id))) throw PhotoImportException("Couldn't store the photo.")
            id
        } catch (e: PhotoImportException) {
            staging.deleteRecursively()
            throw e
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw PhotoImportException("Couldn't read the photo.", e)
        } catch (e: OutOfMemoryError) {
            staging.deleteRecursively()
            throw PhotoImportException("The photo is too large.", e)
        }
    }

    override suspend fun read(id: String, size: PhotoSize): ByteArray? = withContext(Dispatchers.IO) {
        if (!isValidPhotoId(id)) return@withContext null
        File(File(root, id), size.fileName).takeIf { it.isFile }?.readBytes()
    }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            if (isValidPhotoId(id)) File(root, id).deleteRecursively()
        }
    }

    /** Also clears staging folders left by an interrupted import. */
    override suspend fun storedIds(): Set<String> = withContext(Dispatchers.IO) {
        val folders = root.listFiles().orEmpty().filter { it.isDirectory }
        folders.filter { it.name.endsWith(".tmp") }.forEach { it.deleteRecursively() }
        folders.map { it.name }.filterNot { it.endsWith(".tmp") }.toSet()
    }

    /** Decodes with subsampling first, so a very large photo never loads at full resolution. */
    private fun decodeOriented(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PhotoImportException("This image format isn't supported.")

        val options = BitmapFactory.Options().apply {
            inSampleSize = photoSampleSize(bounds.outWidth, bounds.outHeight, PhotoSize.DISPLAY.maxSide)
        }
        val decoded = open(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw PhotoImportException("This image format isn't supported.")
        val oriented = decoded.oriented(readOrientation(uri))
        val scaled = oriented.scaledToFit(PhotoSize.DISPLAY.maxSide)
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    private fun open(uri: Uri) = resolver.openInputStream(uri) ?: throw PhotoImportException("Couldn't open the photo.")

    private fun readOrientation(uri: Uri): Int = try {
        open(uri).use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun Bitmap.oriented(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return this
        }
        val result = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (result !== this) recycle()
        return result
    }

    private fun Bitmap.scaledToFit(maxSide: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return this
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** JPEG has no transparency, so transparent areas are flattened onto white. */
    private fun write(bitmap: Bitmap, file: File) {
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
            }
        } else {
            bitmap
        }
        val written = file.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (opaque !== bitmap) opaque.recycle()
        if (!written) throw PhotoImportException("Couldn't store the photo.")
    }
}
