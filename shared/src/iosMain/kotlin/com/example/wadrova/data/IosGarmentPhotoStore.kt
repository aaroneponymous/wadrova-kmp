@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.example.wadrova.data

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLRef
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.numberWithBool
import platform.Foundation.numberWithInt
import platform.Foundation.writeToFile
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetCount
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill
import platform.posix.memcpy

/** Stores photos in a folder excluded from iCloud and device backups. */
class IosGarmentPhotoStore(folderName: String = "garment_photos") : GarmentPhotoStore {
    private val files = NSFileManager.defaultManager
    private val root: String = run {
        val support = files.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
            ?: error("Application Support is unavailable.")
        support.path + "/" + folderName
    }

    /** Applied every time, in case a restore or an older build left the folder without the flag. */
    private fun prepareRoot() {
        files.createDirectoryAtPath(root, true, null, null)
        val excluded = NSURL.fileURLWithPath(root).setResourceValue(NSNumber.numberWithBool(true), NSURLIsExcludedFromBackupKey, null)
        if (!excluded) throw PhotoImportException("Couldn't protect the photo folder.")
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun import(photo: PickedPhoto): String = withContext(Dispatchers.IO) {
        val id = Uuid.random().toString()
        val staging = "$root/$id.tmp"
        try {
            prepareRoot()
            files.createDirectoryAtPath(staging, true, null, null)
            PhotoSize.entries.forEach { size ->
                val data = autoreleasepool { encodeRendition(photo.location, size.maxSide) }
                if (!data.writeToFile("$staging/${size.fileName}", true)) {
                    throw PhotoImportException("Couldn't store the photo.")
                }
            }
            if (!files.moveItemAtPath(staging, "$root/$id", null)) throw PhotoImportException("Couldn't store the photo.")
            id
        } catch (e: PhotoImportException) {
            files.removeItemAtPath(staging, null)
            throw e
        } finally {
            // The picker's copy of the photo is temporary either way.
            files.removeItemAtPath(photo.location, null)
        }
    }

    /** Writes a temporary file and imports it the same way as a picked photo, which deletes the file afterwards. */
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun importBytes(bytes: ByteArray): String {
        val path = NSTemporaryDirectory() + "import-${Uuid.random()}"
        val written = withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) false else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }.writeToFile(path, true)
        }
        if (!written) throw PhotoImportException("Couldn't read the photo.")
        return import(PickedPhoto(path))
    }

    override suspend fun read(id: String, size: PhotoSize): ByteArray? = withContext(Dispatchers.IO) {
        if (!isValidPhotoId(id)) return@withContext null
        NSData.dataWithContentsOfFile("$root/$id/${size.fileName}")?.toByteArray()
    }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            if (isValidPhotoId(id)) files.removeItemAtPath("$root/$id", null)
        }
    }

    /** Also clears staging folders left by an interrupted import. */
    override suspend fun storedIds(): Set<String> = withContext(Dispatchers.IO) {
        val names = files.contentsOfDirectoryAtPath(root, null).orEmpty().filterIsInstance<String>()
        names.filter { it.endsWith(".tmp") }.forEach { files.removeItemAtPath("$root/$it", null) }
        names.filterNot { it.endsWith(".tmp") }.toSet()
    }

    /** ImageIO decodes directly at the target size and applies EXIF orientation, avoiding a full-size decode. */
    private fun encodeRendition(path: String, maxSide: Int): NSData {
        val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef
        val source = CGImageSourceCreateWithURL(url, null)
        CFRelease(url)
        if (source == null || CGImageSourceGetCount(source) == 0uL) {
            source?.let { CFRelease(it) }
            throw PhotoImportException("This image format isn't supported.")
        }
        val options = mapOf(
            bridged(kCGImageSourceCreateThumbnailFromImageAlways) to NSNumber.numberWithBool(true),
            bridged(kCGImageSourceCreateThumbnailWithTransform) to NSNumber.numberWithBool(true),
            bridged(kCGImageSourceThumbnailMaxPixelSize) to NSNumber.numberWithInt(maxSide),
        )
        val cfOptions = CFBridgingRetain(options) as CFDictionaryRef
        val image = CGImageSourceCreateThumbnailAtIndex(source, 0u, cfOptions)
        CFRelease(cfOptions)
        CFRelease(source)
        image ?: throw PhotoImportException("This image format isn't supported.")
        try {
            val width = CGImageGetWidth(image).toDouble()
            val height = CGImageGetHeight(image).toDouble()
            // Scale 1 keeps pixel dimensions; the opaque white context flattens any transparency.
            UIGraphicsBeginImageContextWithOptions(CGSizeMake(width, height), true, 1.0)
            try {
                UIColor.whiteColor.setFill()
                UIRectFill(CGRectMake(0.0, 0.0, width, height))
                UIImage.imageWithCGImage(image).drawInRect(CGRectMake(0.0, 0.0, width, height))
                val flattened = UIGraphicsGetImageFromCurrentImageContext()
                    ?: throw PhotoImportException("Couldn't read the photo.")
                return UIImageJPEGRepresentation(flattened, 0.88)
                    ?: throw PhotoImportException("Couldn't read the photo.")
            } finally {
                UIGraphicsEndImageContext()
            }
        } finally {
            CGImageRelease(image)
        }
    }

    private fun bridged(key: CFStringRef?): Any = CFBridgingRelease(CFRetain(key))!!

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size == 0) return ByteArray(0)
        return ByteArray(size).also { bytes ->
            bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
        }
    }
}
