package com.example.wadrova.data

class FakePhotoStore(val stored: MutableSet<String> = mutableSetOf()) : GarmentPhotoStore {
    var importFailure: Exception? = null
    var deleteFailure: Exception? = null
    var nextId = 0
    val deleted = mutableListOf<String>()

    override suspend fun import(photo: PickedPhoto): String {
        importFailure?.let { throw it }
        return "photo-${++nextId}".also { stored += it }
    }

    override suspend fun read(id: String, size: PhotoSize): ByteArray? = if (id in stored) ByteArray(1) else null

    override suspend fun delete(id: String) {
        deleteFailure?.let { throw it }
        deleted += id
        stored -= id
    }

    override suspend fun storedIds(): Set<String> = stored.toSet()
}
