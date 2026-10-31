package com.example.wadrova.data

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stores images under noBackupFilesDir, which Android excludes from Auto Backup. */
class AndroidGeneratedImageStore(context: Context) : GeneratedImageStore {
    private val root = File(context.applicationContext.noBackupFilesDir, "generated_images")

    override suspend fun save(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        root.mkdirs()
        val id = UUID.randomUUID().toString()
        val staging = File(root, "$id.tmp")
        try {
            staging.writeBytes(bytes)
            check(staging.renameTo(File(root, "$id.png"))) { "Couldn't store the image." }
        } finally {
            staging.delete()
        }
        id
    }

    override suspend fun read(id: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!isValidPhotoId(id)) return@withContext null
        File(root, "$id.png").takeIf { it.isFile }?.readBytes()
    }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            if (isValidPhotoId(id)) File(root, "$id.png").delete()
        }
    }

    /** Also clears partial files left by an interrupted save. */
    override suspend fun storedIds(): Set<String> = withContext(Dispatchers.IO) {
        val files = root.listFiles().orEmpty()
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        files.filter { it.name.endsWith(".png") }.map { it.name.removeSuffix(".png") }.toSet()
    }
}
