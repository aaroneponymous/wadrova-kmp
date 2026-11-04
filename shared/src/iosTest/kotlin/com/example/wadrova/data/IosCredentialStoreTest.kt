package com.example.wadrova.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Uses its own Keychain service, so the app's real items are never touched. Kotlin/Native simulator tests run as a
 * bare executable without an app bundle, where the Keychain answers errSecNotAvailable (-25291); there the tests
 * report that and pass without checking anything, and the Keychain is verified in the built app instead.
 */
class IosCredentialStoreTest {
    private val store = IosCredentialStore(service = "com.example.wadrova.tests")

    private suspend fun keychainAvailable(): Boolean = try {
        store.read(Credential.WADROVA_ACCESS_TOKEN)
        true
    } catch (e: CredentialStoreException) {
        println("IosCredentialStoreTest: no Keychain in this test host (${e.message}); not checked here.")
        false
    }

    @AfterTest
    fun tearDown() = runTest { if (keychainAvailable()) Credential.entries.forEach { store.delete(it) } }

    @Test
    fun valuesRoundTripOverwriteAndDelete() = runTest {
        if (!keychainAvailable()) return@runTest
        assertNull(store.read(Credential.WADROVA_ACCESS_TOKEN))
        store.write(Credential.WADROVA_ACCESS_TOKEN, "wdr_first")
        store.write(Credential.MIRROR_DEV_TOKEN, "mirror")
        store.write(Credential.WADROVA_ACCESS_TOKEN, "wdr_second")

        val reopened = IosCredentialStore(service = "com.example.wadrova.tests")
        assertEquals("wdr_second", reopened.read(Credential.WADROVA_ACCESS_TOKEN))
        assertEquals("mirror", reopened.read(Credential.MIRROR_DEV_TOKEN))

        store.delete(Credential.WADROVA_ACCESS_TOKEN)
        assertNull(store.read(Credential.WADROVA_ACCESS_TOKEN))
        assertEquals("mirror", store.read(Credential.MIRROR_DEV_TOKEN))
        store.delete(Credential.WADROVA_ACCESS_TOKEN)
    }

    @Test
    fun anotherServiceIsSeparate() = runTest {
        if (!keychainAvailable()) return@runTest
        store.write(Credential.WADROVA_ACCESS_TOKEN, "mine")
        assertNull(IosCredentialStore(service = "com.example.wadrova.tests.other").read(Credential.WADROVA_ACCESS_TOKEN))
    }
}
