package com.example.wadrova.data

import kotlinx.coroutines.CompletableDeferred

class FakePhotoStore(val stored: MutableSet<String> = mutableSetOf()) : GarmentPhotoStore {
    var importFailure: Exception? = null
    var deleteFailure: Exception? = null
    var nextId = 0
    val deleted = mutableListOf<String>()
    val failingLocations = mutableSetOf<String>()
    val importedFrom = mutableMapOf<String, String>()
    var importGate: CompletableDeferred<Unit>? = null

    override suspend fun import(photo: PickedPhoto): String {
        importGate?.await()
        importFailure?.let { throw it }
        if (photo.location in failingLocations) throw IllegalStateException("Unreadable")
        return "photo-${++nextId}".also {
            stored += it
            importedFrom[it] = photo.location
        }
    }

    override suspend fun read(id: String, size: PhotoSize): ByteArray? = if (id in stored) ByteArray(1) else null

    override suspend fun delete(id: String) {
        deleteFailure?.let { throw it }
        deleted += id
        stored -= id
    }

    override suspend fun storedIds(): Set<String> = stored.toSet()
}
