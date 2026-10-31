@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.example.wadrova.data

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.numberWithBool
import platform.Foundation.writeToFile
import platform.posix.memcpy

/** Stores images in a folder excluded from iCloud and device backups. */
class IosGeneratedImageStore(folderName: String = "generated_images") : GeneratedImageStore {
    private val files = NSFileManager.defaultManager
    private val root: String = run {
        val support = files.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
            ?: error("Application Support is unavailable.")
        support.path + "/" + folderName
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun save(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        files.createDirectoryAtPath(root, true, null, null)
        check(NSURL.fileURLWithPath(root).setResourceValue(NSNumber.numberWithBool(true), NSURLIsExcludedFromBackupKey, null)) {
            "Couldn't protect the image folder."
        }
        val id = Uuid.random().toString()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToFile("$root/$id.png", true)) { "Couldn't store the image." }
        id
    }

    override suspend fun read(id: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!isValidPhotoId(id)) return@withContext null
        val data = NSData.dataWithContentsOfFile("$root/$id.png") ?: return@withContext null
        ByteArray(data.length.toInt()).also { bytes ->
            if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        }
    }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            if (isValidPhotoId(id)) files.removeItemAtPath("$root/$id.png", null)
        }
    }

    override suspend fun storedIds(): Set<String> = withContext(Dispatchers.IO) {
        files.contentsOfDirectoryAtPath(root, null).orEmpty().filterIsInstance<String>()
            .filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }.toSet()
    }
}
