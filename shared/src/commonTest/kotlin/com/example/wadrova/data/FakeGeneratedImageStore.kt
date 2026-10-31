package com.example.wadrova.data

class FakeGeneratedImageStore : GeneratedImageStore {
    val stored = mutableMapOf<String, ByteArray>()
    val deleted = mutableListOf<String>()
    private var nextId = 0

    fun put(id: String, bytes: ByteArray = ByteArray(1)) {
        stored[id] = bytes
    }

    override suspend fun save(bytes: ByteArray): String = "generated-${++nextId}".also { stored[it] = bytes }

    override suspend fun read(id: String): ByteArray? = stored[id]

    override suspend fun delete(id: String) {
        deleted += id
        stored.remove(id)
    }

    override suspend fun storedIds(): Set<String> = stored.keys.toSet()
}
