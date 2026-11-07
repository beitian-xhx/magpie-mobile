package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayCredentialStoreTest {
    @TempDir lateinit var directory: Path
    private fun file() = directory.resolve("registered-gateway.json").toFile()
    private fun key() = RegisteredGatewayKey("real-core-id", GatewayKeyProvisioner.RESERVED_NAME, ManagementApiFixture.SECRET)

    @Test fun missingFileIsNullAndDoesNotCreatePlaceholder() {
        assertNull(GatewayCredentialStore(file()).load())
        assertFalse(file().exists())
        assertFalse(directory.resolve("gateway-key.txt").toFile().exists())
    }

    @Test fun atomicPrivateRoundTripStoresOnlyIdNameAndRealSecret() {
        val store = GatewayCredentialStore(file())
        store.save(key())
        assertEquals(key(), store.load())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file().toPath()))
        val document = Json.parseToJsonElement(file().readText()).jsonObject
        assertEquals(setOf("id", "name", "secret"), document.keys)
        assertEquals(listOf("registered-gateway.json"), Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.toList() })
    }

    @Test fun rewriteReplacesInodeAndTightensLegacyPermissionWithoutTouchingOtherFiles() {
        val store = GatewayCredentialStore(file())
        store.save(key())
        val oldIdentity = Files.getAttribute(file().toPath(), "basic:fileKey")
        Files.setPosixFilePermissions(file().toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        val oldUserData = directory.resolve("gateway-key.txt")
        Files.writeString(oldUserData, "user owned old data")
        val updated = key().copy(name = "renamed key")
        store.save(updated)
        assertEquals(updated, store.load())
        assertFalse(oldIdentity == Files.getAttribute(file().toPath(), "basic:fileKey"))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file().toPath()))
        assertEquals("user owned old data", Files.readString(oldUserData))
    }

    @Test fun faultBeforeCommitPreservesOldBytesAndCleansOnlyOwnedTemp() {
        val store = GatewayCredentialStore(file())
        store.save(key())
        val oldBytes = Files.readAllBytes(file().toPath())
        store.beforeCommitForTest = { throw IOException("failure with " + ManagementApiFixture.SECRET) }
        val error = assertFailsWith<GatewayCredentialStoreException> { store.save(key().copy(secret = "sk-magpie-key-" + "b".repeat(48))) }
        assertTrue(oldBytes.contentEquals(Files.readAllBytes(file().toPath())))
        assertEquals(1L, Files.list(directory).use { it.count() })
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(ManagementApiFixture.SECRET))
    }

    @Test fun invalidOrPlaceholderSaveDoesNotDamageExistingCredential() {
        val store = GatewayCredentialStore(file())
        store.save(key())
        val oldBytes = Files.readAllBytes(file().toPath())
        for (bad in listOf(key().copy(id = ""), key().copy(secret = ""), key().copy(secret = "bad\nheader"),
            key().copy(off = true), key().copy(readOnly = true), key().copy(browser = true),
            key().copy(expires = "2000-01-01T00:00:00Z"))) {
            assertFailsWith<GatewayCredentialStoreException> { store.save(bad) }
            assertTrue(oldBytes.contentEquals(Files.readAllBytes(file().toPath())))
        }
    }

    @Test fun corruptedExistingFileIsNotNoLocalIdAndCannotBeOverwritten() {
        Files.writeString(file().toPath(), "broken JSON " + ManagementApiFixture.SECRET)
        val oldBytes = Files.readAllBytes(file().toPath())
        val store = GatewayCredentialStore(file())
        val error = assertFailsWith<GatewayCredentialStoreException> { store.load() }
        assertFailsWith<GatewayCredentialStoreException> { store.save(key()) }
        assertTrue(oldBytes.contentEquals(Files.readAllBytes(file().toPath())))
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(ManagementApiFixture.SECRET))
    }

    @Test fun oversizedStoredFileIsRejectedAndPreserved() {
        val oldBytes = ByteArray(GatewayCredentialStore.MAX_STORE_BYTES + 1) { 120 }
        Files.write(file().toPath(), oldBytes)
        val store = GatewayCredentialStore(file())
        assertFailsWith<GatewayCredentialStoreException> { store.load() }
        assertFailsWith<GatewayCredentialStoreException> { store.save(key()) }
        assertTrue(oldBytes.contentEquals(Files.readAllBytes(file().toPath())))
    }

    @Test fun targetSymlinkCannotReadOrOverwriteItsVictim() {
        val victim = directory.resolve("victim")
        Files.writeString(victim, "user data must remain")
        Files.createSymbolicLink(file().toPath(), victim)
        val store = GatewayCredentialStore(file())
        assertFailsWith<GatewayCredentialStoreException> { store.load() }
        assertFailsWith<GatewayCredentialStoreException> { store.save(key()) }
        assertTrue(Files.isSymbolicLink(file().toPath()))
        assertEquals("user data must remain", Files.readString(victim))
    }

    @Test fun danglingSymlinkIsNotTreatedAsMissing() {
        Files.createSymbolicLink(file().toPath(), directory.resolve("missing-victim"))
        val store = GatewayCredentialStore(file())
        assertFailsWith<GatewayCredentialStoreException> { store.load() }
        assertFailsWith<GatewayCredentialStoreException> { store.save(key()) }
        assertTrue(Files.isSymbolicLink(file().toPath()))
        assertFalse(Files.exists(directory.resolve("missing-victim")))
    }

    @Test fun symlinkedParentAndDotDotPathAreRejectedWithoutChangingUserFiles() {
        val real = Files.createDirectory(directory.resolve("real"))
        val link = directory.resolve("link")
        Files.createSymbolicLink(link, real)
        val store = GatewayCredentialStore(link.resolve("key.json").toFile())
        assertFailsWith<GatewayCredentialStoreException> { store.load() }
        assertFailsWith<GatewayCredentialStoreException> { store.save(key()) }
        val odd = GatewayCredentialStore(real.resolve("..").resolve("registered-gateway.json").toFile())
        assertFailsWith<GatewayCredentialStoreException> { odd.save(key()) }
        assertFalse(Files.exists(real.resolve("key.json")))
    }

    @Test fun missingParentAndDirectoryTargetFailClosed() {
        val missing = GatewayCredentialStore(directory.resolve("absent/key.json").toFile())
        assertFailsWith<GatewayCredentialStoreException> { missing.load() }
        assertFailsWith<GatewayCredentialStoreException> { missing.save(key()) }
        val target = Files.createDirectory(file().toPath())
        assertFailsWith<GatewayCredentialStoreException> { GatewayCredentialStore(target.toFile()).save(key()) }
        assertTrue(Files.isDirectory(target))
    }

    @Test fun targetSwapToSymlinkBeforeCommitCannotClobberVictim() {
        val store = GatewayCredentialStore(file())
        store.save(key())
        val oldFile = directory.resolve("old.json")
        val victim = directory.resolve("victim.txt")
        Files.writeString(victim, "unchanged")
        store.beforeCommitForTest = {
            Files.move(file().toPath(), oldFile)
            Files.createSymbolicLink(file().toPath(), victim)
        }
        assertFailsWith<GatewayCredentialStoreException> { store.save(key().copy(name = "new")) }
        assertEquals("unchanged", Files.readString(victim))
        assertEquals(key(), GatewayCredentialStore(oldFile.toFile()).load())
        assertTrue(Files.isSymbolicLink(file().toPath()))
    }
}
