@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.example.wadrova.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutableCopy
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * Generic-password Keychain items, readable after the first unlock and never migrated to another device
 * (`ThisDeviceOnly`), so they stay out of iCloud and encrypted backups restored elsewhere.
 */
class IosCredentialStore(private val service: String = "com.example.wadrova.credentials") : CredentialStore {

    override suspend fun read(credential: Credential): String? = withContext(Dispatchers.IO) {
        val base = dictionary(itemKeys(credential) + mapOf(bridged(kSecMatchLimit) to bridged(kSecMatchLimitOne)))
        // kSecReturnData must be a real CFBoolean. Any boolean that passes through a Kotlin map arrives as the
        // integer 1, which SecItemCopyMatching rejects with errSecParam (-50), so it's added on the CF side.
        val query = CFDictionaryCreateMutableCopy(null, 0, base)
        CFRelease(base)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFRelease(query)
            when (status) {
                errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray()?.decodeToString()
                errSecItemNotFound -> null
                else -> throw CredentialStoreException("The Keychain couldn't be read (status $status).")
            }
        }
    }

    override suspend fun write(credential: Credential, value: String) = withContext(Dispatchers.IO) {
        remove(credential)
        val attributes = dictionary(
            itemKeys(credential) + mapOf(
                bridged(kSecValueData) to value.encodeToByteArray().toNSData(),
                bridged(kSecAttrAccessible) to bridged(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly),
            ),
        )
        val status = SecItemAdd(attributes, null)
        CFRelease(attributes)
        if (status != errSecSuccess) throw CredentialStoreException("The Keychain refused the credential (status $status).")
    }

    override suspend fun delete(credential: Credential) = withContext(Dispatchers.IO) { remove(credential) }

    private fun remove(credential: Credential) {
        val query = dictionary(itemKeys(credential))
        val status = SecItemDelete(query)
        CFRelease(query)
        if (status != errSecSuccess && status != errSecItemNotFound) {
            throw CredentialStoreException("The Keychain couldn't remove the credential (status $status).")
        }
    }

    private fun itemKeys(credential: Credential): Map<Any?, Any?> = mapOf(
        bridged(kSecClass) to bridged(kSecClassGenericPassword),
        bridged(kSecAttrService) to service,
        bridged(kSecAttrAccount) to credential.id,
    )

    private fun dictionary(entries: Map<Any?, Any?>): CFDictionaryRef = CFBridgingRetain(entries) as CFDictionaryRef

    private fun bridged(value: CFTypeRef?): Any = CFBridgingRelease(CFRetain(value))!!

    private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size == 0) return ByteArray(0)
        return ByteArray(size).also { bytes -> bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) } }
    }
}
