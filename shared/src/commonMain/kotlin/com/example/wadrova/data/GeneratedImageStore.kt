package com.example.wadrova.data

/**
 * App-private PNG files for generated images, kept apart from garment photos and excluded from cloud
 * backups. An id's content never changes, so regenerating can't overwrite an approved image.
 */
interface GeneratedImageStore {
    suspend fun save(bytes: ByteArray): String

    /** Returns null if the file is unavailable, for example after a backup restored only the database. */
    suspend fun read(id: String): ByteArray?

    suspend fun delete(id: String)

    suspend fun storedIds(): Set<String>
}
