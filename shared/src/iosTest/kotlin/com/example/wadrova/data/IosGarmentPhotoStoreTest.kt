@file:OptIn(ExperimentalForeignApi::class)

package com.example.wadrova.data

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.test.runTest
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImageOrientation
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIRectFill

class IosGarmentPhotoStoreTest {
    private val folder = "garment_photos_test"
    private val store = IosGarmentPhotoStore(folder)
    private val files = NSFileManager.defaultManager
    private val root: String =
        files.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)!!.path + "/" + folder
    private var counter = 0

    @BeforeTest
    fun setUp() {
        files.removeItemAtPath(root, null)
    }

    @AfterTest
    fun tearDown() {
        files.removeItemAtPath(root, null)
    }

    private fun image(width: Int, height: Int, opaque: Boolean = true): UIImage {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(width.toDouble(), height.toDouble()), opaque, 1.0)
        if (opaque) {
            UIColor.redColor.setFill()
            UIRectFill(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
        }
        val result = UIGraphicsGetImageFromCurrentImageContext()!!
        UIGraphicsEndImageContext()
        return result
    }

    private fun path(extension: String) = NSTemporaryDirectory() + "photo-test-${counter++}.$extension"

    private fun jpeg(image: UIImage) = PickedPhoto(path("jpg").also { UIImageJPEGRepresentation(image, 0.9)!!.writeToFile(it, true) })

    private suspend fun dimensions(id: String, size: PhotoSize): Pair<Int, Int> {
        val decoded = UIImage(data = assertNotNull(store.read(id, size)).toNSData())
        return decoded.size.useContents { width.toInt() to height.toInt() }
    }

    @OptIn(BetaInteropApi::class)
    private fun ByteArray.toNSData(): NSData = usePinned {
        NSData.create(bytes = it.addressOf(0), length = size.toULong())
    }

    @Test
    fun portraitAndLandscapeAreScaledToBothRenditions() = runTest {
        val portrait = store.import(jpeg(image(2000, 4000)))
        assertEquals(800 to 1600, dimensions(portrait, PhotoSize.DISPLAY))
        assertEquals(320 to 640, dimensions(portrait, PhotoSize.THUMBNAIL))
        val landscape = store.import(jpeg(image(4000, 2000)))
        assertEquals(1600 to 800, dimensions(landscape, PhotoSize.DISPLAY))
    }

    @Test
    fun largeImageImports() = runTest {
        val id = store.import(jpeg(image(8000, 6000)))
        assertEquals(1600 to 1200, dimensions(id, PhotoSize.DISPLAY))
    }

    @Test
    fun exifOrientationIsAppliedToThePixels() = runTest {
        val rotated = UIImage.imageWithCGImage(image(2000, 1000).CGImage, 1.0, UIImageOrientation.UIImageOrientationRight)
        val id = store.import(jpeg(rotated))
        assertEquals(800 to 1600, dimensions(id, PhotoSize.DISPLAY))
        val decoded = UIImage(data = assertNotNull(store.read(id, PhotoSize.DISPLAY)).toNSData())
        assertEquals(UIImageOrientation.UIImageOrientationUp, decoded.imageOrientation)
    }

    @Test
    fun transparentPngImports() = runTest {
        val file = path("png").also { UIImagePNGRepresentation(image(300, 200, opaque = false))!!.writeToFile(it, true) }
        val id = store.import(PickedPhoto(file))
        assertEquals(300 to 200, dimensions(id, PhotoSize.DISPLAY))
    }

    @Test
    fun unreadableFilesFailCleanly() = runTest {
        val corrupt = path("jpg").also { ByteArray(16) { 1 }.toNSData().writeToFile(it, true) }
        assertFailsWith<PhotoImportException> { store.import(PickedPhoto(corrupt)) }
        assertFailsWith<PhotoImportException> { store.import(PickedPhoto(path("jpg"))) }
        assertEquals(emptySet(), store.storedIds())
    }

    @Test
    fun photoOutlivesItsSourceAndDeleteRemovesIt() = runTest {
        val photo = jpeg(image(500, 400))
        val id = store.import(photo)
        assertTrue(!files.fileExistsAtPath(photo.location))
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

    @OptIn(BetaInteropApi::class)
    @Test
    fun photoFolderIsExcludedFromBackup() = runTest {
        store.import(jpeg(image(100, 100)))
        memScoped {
            val value = alloc<ObjCObjectVar<Any?>>()
            val read = NSURL.fileURLWithPath(root).getResourceValue(value.ptr, NSURLIsExcludedFromBackupKey, null)
            assertTrue(read)
            assertEquals(true, (value.value as NSNumber).boolValue)
        }
    }
}
