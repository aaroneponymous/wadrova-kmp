package com.example.wadrova.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Each value is AES-GCM encrypted with a key that never leaves the Android Keystore, and written under
 * noBackupFilesDir. A restored or copied file can't be decrypted elsewhere, and then reads as missing.
 */
class AndroidCredentialStore(context: Context) : CredentialStore {
    private val root = File(context.applicationContext.noBackupFilesDir, "credentials")

    override suspend fun read(credential: Credential): String? = withContext(Dispatchers.IO) {
        val file = File(root, credential.id)
        if (!file.isFile) return@withContext null
        try {
            val stored = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES))
            cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES).decodeToString()
        } catch (_: Exception) {
            // The key is gone (for example after a backup restore) or the file is damaged: ask for the token again.
            file.delete()
            null
        }
    }

    override suspend fun write(credential: Credential, value: String) = withContext(Dispatchers.IO) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            check(iv.size == IV_BYTES)
            val sealed = iv + cipher.doFinal(value.encodeToByteArray())
            root.mkdirs()
            val staging = File(root, "${credential.id}.tmp")
            staging.writeBytes(sealed)
            if (!staging.renameTo(File(root, credential.id))) throw CredentialStoreException("Couldn't store the credential.")
        } catch (e: CredentialStoreException) {
            throw e
        } catch (e: Exception) {
            throw CredentialStoreException("Couldn't store the credential.", e)
        }
    }

    override suspend fun delete(credential: Credential) {
        withContext(Dispatchers.IO) { File(root, credential.id).delete() }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "wadrova_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
