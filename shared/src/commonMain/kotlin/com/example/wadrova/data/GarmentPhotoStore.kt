package com.example.wadrova.data

/** A photo chosen in the system picker. [location] means something only to the platform's store. */
data class PickedPhoto(val location: String)

class PhotoImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The stored renditions of a photo. Both are re-encoded JPEGs, so they carry no location or camera metadata. */
enum class PhotoSize(val maxSide: Int, val fileName: String) {
    DISPLAY(1600, "display.jpg"),
    THUMBNAIL(640, "thumbnail.jpg"),
}

/**
 * App-private photo files, identified by opaque ids rather than paths. A photo's id never changes
 * meaning: replacing a photo stores a new id. Nothing here leaves the device.
 */
interface GarmentPhotoStore {
    /** Stores properly oriented renditions and returns the new photo id. Throws [PhotoImportException]. */
    suspend fun import(photo: PickedPhoto): String

    /**
     * Like [import], from encoded image bytes (such as a downloaded PNG cutout). Transparency is flattened onto white,
     * as for picked photos. Throws [PhotoImportException] if the bytes don't decode.
     */
    suspend fun importBytes(bytes: ByteArray): String

    /** Returns null if the file is unavailable, for example after a backup restored only the database. */
    suspend fun read(id: String, size: PhotoSize): ByteArray?

    suspend fun delete(id: String)

    suspend fun storedIds(): Set<String>
}

/** Rejects anything that isn't a generated id, so a bad database value can't reach outside the photo folder. */
internal fun isValidPhotoId(id: String): Boolean =
    id.isNotEmpty() && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

/** The largest power-of-two subsampling that still leaves the image at least [maxSide] on its longest side. */
internal fun photoSampleSize(width: Int, height: Int, maxSide: Int): Int {
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= maxSide) sample *= 2
    return sample
}
