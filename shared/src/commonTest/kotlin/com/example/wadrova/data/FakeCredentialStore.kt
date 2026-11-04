package com.example.wadrova.data

class FakeCredentialStore(val values: MutableMap<Credential, String> = mutableMapOf()) : CredentialStore {
    var failure: Exception? = null

    override suspend fun read(credential: Credential): String? {
        failure?.let { throw it }
        return values[credential]
    }

    override suspend fun write(credential: Credential, value: String) {
        failure?.let { throw it }
        values[credential] = value
    }

    override suspend fun delete(credential: Credential) {
        failure?.let { throw it }
        values.remove(credential)
    }
}
