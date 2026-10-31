package com.example.wadrova.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AndroidGarmentPhotoStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AndroidGarmentPhotoStore(context)
    private val root = File(context.noBackupFilesDir, "garment_photos")
    private val sources = File(context.cacheDir, "photo_sources")

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        sources.deleteRecursively()
        sources.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
        sources.deleteRecursively()
    }

    private fun source(
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        name: String = "source.jpg",
        color: Int = Color.RED,
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val file = File(sources, name)
        file.outputStream().use { bitmap.compress(format, 90, it) }
        bitmap.recycle()
        return file
    }

    private fun picked(file: File) = PickedPhoto(Uri.fromFile(file).toString())

    private suspend fun dimensions(id: String, size: PhotoSize): Pair<Int, Int> {
        val bytes = assertNotNull(store.read(id, size))
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun portraitAndLandscapeAreScaledToBothRenditions() = runTest {
        val portrait = store.import(picked(source(2000, 4000)))
        assertEquals(800 to 1600, dimensions(portrait, PhotoSize.DISPLAY))
        assertEquals(320 to 640, dimensions(portrait, PhotoSize.THUMBNAIL))
        val landscape = store.import(picked(source(4000, 2000)))
        assertEquals(1600 to 800, dimensions(landscape, PhotoSize.DISPLAY))
    }

    @Test
    fun smallPhotosAreNotEnlarged() = runTest {
        val id = store.import(picked(source(300, 200)))
        assertEquals(300 to 200, dimensions(id, PhotoSize.DISPLAY))
        assertEquals(300 to 200, dimensions(id, PhotoSize.THUMBNAIL))
    }

    @Test
    fun largeImageImports() = runTest {
        val id = store.import(picked(source(8000, 6000)))
        assertEquals(1600 to 1200, dimensions(id, PhotoSize.DISPLAY))
    }

    @Test
    fun exifRotationIsAppliedAndMetadataIsNotKept() = runTest {
        val file = source(2000, 1000)
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,0/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            saveAttributes()
        }
        val id = store.import(picked(file))
        assertEquals(800 to 1600, dimensions(id, PhotoSize.DISPLAY))
        val stored = File(File(root, id), PhotoSize.DISPLAY.fileName)
        val exif = ExifInterface(stored.path)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals(
            ExifInterface.ORIENTATION_UNDEFINED,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
        )
    }

    @Test
    fun transparentPngIsFlattenedOntoWhite() = runTest {
        val file = source(100, 100, Bitmap.CompressFormat.PNG, "source.png", Color.TRANSPARENT)
        val id = store.import(picked(file))
        val bytes = assertNotNull(store.read(id, PhotoSize.DISPLAY))
        val pixel = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).getPixel(50, 50)
        assertTrue(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    @Test
    fun webpImports() = runTest {
        @Suppress("DEPRECATION")
        val id = store.import(picked(source(500, 400, Bitmap.CompressFormat.WEBP, "source.webp")))
        assertEquals(500 to 400, dimensions(id, PhotoSize.DISPLAY))
    }

    @Test
    fun unreadableFilesFailCleanly() = runTest {
        val corrupt = File(sources, "corrupt.jpg").apply { writeText("not an image") }
        assertFailsWith<PhotoImportException> { store.import(picked(corrupt)) }
        assertFailsWith<PhotoImportException> { store.import(picked(File(sources, "missing.jpg"))) }
        assertEquals(emptySet(), store.storedIds())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun photoOutlivesItsSourceAndDeleteRemovesIt() = runTest {
        val file = source(500, 400)
        val id = store.import(picked(file))
        file.delete()
        assertNotNull(store.read(id, PhotoSize.DISPLAY))
        assertEquals(setOf(id), store.storedIds())
        store.delete(id)
        assertNull(store.read(id, PhotoSize.DISPLAY))
        assertEquals(emptySet(), store.storedIds())
    }

    @Test
    fun missingFilesAndBadIdsReadAsNull() = runTest {
        assertNull(store.read("does-not-exist", PhotoSize.DISPLAY))
        assertNull(store.read("../../databases", PhotoSize.DISPLAY))
    }

    @Test
    fun interruptedImportStagingIsCleared() = runTest {
        File(root, "abc.tmp").mkdirs()
        assertEquals(emptySet(), store.storedIds())
        assertFalse(File(root, "abc.tmp").exists())
    }

    @Test
    fun photosLiveInTheNoBackupDirectory() = runTest {
        val id = store.import(picked(source(100, 100)))
        assertTrue(File(root, id).canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
    }
}
