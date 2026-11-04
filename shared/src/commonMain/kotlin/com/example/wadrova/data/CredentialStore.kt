package com.example.wadrova.data

/** The secrets the app holds. Each is stored on its own, never in the database, which backups can include. */
enum class Credential(val id: String) {
    /** The `X-Wadrova-Dev-Token` for the local Mirror backend. */
    MIRROR_DEV_TOKEN("mirror_dev_token"),

    /** A development `wdr_` bearer token for the Wadrova API, issued by the backend operator until sign-in exists. */
    WADROVA_ACCESS_TOKEN("wadrova_access_token"),
}

class CredentialStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Platform-secured storage: the iOS Keychain (this device only, after first unlock) and, on Android, files encrypted
 * with an Android Keystore key and kept out of backups. Values are never logged or put in exception messages.
 */
interface CredentialStore {
    /** Null if nothing is stored, or if the stored value can no longer be decrypted. */
    suspend fun read(credential: Credential): String?

    /** Throws [CredentialStoreException] if the platform refuses. */
    suspend fun write(credential: Credential, value: String)

    suspend fun delete(credential: Credential)
}
