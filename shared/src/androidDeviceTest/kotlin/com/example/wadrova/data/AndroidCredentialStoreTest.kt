package com.example.wadrova.data

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AndroidCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.noBackupFilesDir, "credentials")

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun valuesRoundTripAndAreEncryptedOutsideBackups() = runTest {
        val store = AndroidCredentialStore(context)
        assertNull(store.read(Credential.WADROVA_ACCESS_TOKEN))
        store.write(Credential.WADROVA_ACCESS_TOKEN, "wdr_secret_value")
        store.write(Credential.MIRROR_DEV_TOKEN, "mirror")

        // A new instance, as after a relaunch.
        val reopened = AndroidCredentialStore(context)
        assertEquals("wdr_secret_value", reopened.read(Credential.WADROVA_ACCESS_TOKEN))
        assertEquals("mirror", reopened.read(Credential.MIRROR_DEV_TOKEN))

        val file = File(root, Credential.WADROVA_ACCESS_TOKEN.id)
        assertTrue(file.path.startsWith(context.noBackupFilesDir.path))
        assertFalse("wdr_secret_value" in file.readBytes().decodeToString(throwOnInvalidSequence = false))
    }

    @Test
    fun overwritingAndDeleting() = runTest {
        val store = AndroidCredentialStore(context)
        store.write(Credential.WADROVA_ACCESS_TOKEN, "first")
        store.write(Credential.WADROVA_ACCESS_TOKEN, "second")
        assertEquals("second", store.read(Credential.WADROVA_ACCESS_TOKEN))
        store.delete(Credential.WADROVA_ACCESS_TOKEN)
        assertNull(store.read(Credential.WADROVA_ACCESS_TOKEN))
        store.delete(Credential.WADROVA_ACCESS_TOKEN)
    }

    @Test
    fun aDamagedValueReadsAsMissingSoTheUserIsAskedAgain() = runTest {
        val store = AndroidCredentialStore(context)
        store.write(Credential.WADROVA_ACCESS_TOKEN, "secret")
        val file = File(root, Credential.WADROVA_ACCESS_TOKEN.id)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(store.read(Credential.WADROVA_ACCESS_TOKEN))
        assertFalse(file.exists())
    }
}
