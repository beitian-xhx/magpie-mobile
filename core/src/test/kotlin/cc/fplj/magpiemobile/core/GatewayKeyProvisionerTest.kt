package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayKeyProvisionerTest {
    @TempDir lateinit var directory: Path
    private lateinit var api: ManagementApiFixture
    private lateinit var store: GatewayCredentialStore
    @BeforeEach fun start() {
        api = ManagementApiFixture()
        store = GatewayCredentialStore(directory.resolve("registered-gateway.json").toFile())
    }
    @AfterEach fun stop() { api.close() }
    private fun key() = RegisteredGatewayKey("owned-id", GatewayKeyProvisioner.RESERVED_NAME, ManagementApiFixture.SECRET)
    private fun provisioner(timeout: Duration = Duration.ofSeconds(2)) = GatewayKeyProvisioner(api.client(timeout), store)

    @Test fun firstProvisionNegotiatesThenListsThenIssuesAndStoresActual48HexKey() {
        val key = provisioner().provision()
        assertEquals("000000000000000000000001", key.id)
        assertEquals(GatewayKeyProvisioner.RESERVED_NAME, key.name)
        assertEquals(ManagementApiFixture.SECRET, key.secret)
        assertEquals(key, store.load())
        assertEquals(1, api.addRequests.get())
        assertEquals(0, api.copyRequests.get())
        assertEquals(listOf("/api/mobile/capabilities", "/api/caller-keys", "/api/caller-keys", "/api/caller-keys/add-key"), api.requests.map { it.path })
        assertFalse(Files.exists(directory.resolve("gateway-key.txt")))
    }

    @Test fun everySafetyMismatchRejectsBeforeKeyReadOrWrite() {
        val original = Json.parseToJsonElement(ManagementApiFixture.CAPABILITIES).jsonObject
        val variants = listOf(
            "schemaVersion" to JsonPrimitive(2), "coreVersion" to JsonPrimitive(""),
            "mobile" to JsonPrimitive(false), "loopbackKeys" to JsonPrimitive(false),
            "adminLoopbackOnly" to JsonPrimitive(false), "debugEndpoints" to JsonPrimitive(true),
            "nativeAutoUpdate" to JsonPrimitive(true),
        )
        for ((name, bad) in variants) {
            api.capabilitiesBody = JsonObject(original + (name to bad)).toString()
            assertFailsWith<ManagementCapabilitiesMismatch> { provisioner().provision() }
        }
        assertEquals(0, api.listRequests.get())
        assertEquals(0, api.addRequests.get())
        assertEquals(0, api.copyRequests.get())
        assertNull(store.load())
    }

    @Test fun missingCapabilityFieldsFailClosedRatherThanUsingDefaults() {
        api.capabilitiesBody = """{"schemaVersion":1,"coreVersion":"old","mobile":true}"""
        assertFailsWith<ManagementProtocolException> { provisioner().provision() }
        assertEquals(0, api.addRequests.get())
        assertEquals(0, api.listRequests.get())
    }

    @Test fun validLocalIdReusesLocalSecretNotEmptyListSecretAndSavesPrivately() {
        store.save(key())
        Files.setPosixFilePermissions(directory.resolve("registered-gateway.json"), PosixFilePermissions.fromString("rw-r--r--"))
        api.keys = listOf(key().copy(name = "renamed in core"))
        val recovered = provisioner().provision()
        assertEquals("owned-id", recovered.id)
        assertEquals("renamed in core", recovered.name)
        assertEquals(ManagementApiFixture.SECRET, recovered.secret)
        assertEquals(recovered, store.load())
        assertEquals(0, api.addRequests.get())
        assertEquals(0, api.copyRequests.get())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("registered-gateway.json")))
    }

    @Test fun uniqueReservedMetadataWithoutLocalIdRecoversThroughCopyNotAdd() {
        api.keys = listOf(key())
        val recovered = provisioner().provision()
        assertEquals(key(), recovered)
        assertEquals(key(), store.load())
        assertEquals(1, api.copyRequests.get())
        assertEquals(0, api.addRequests.get())
        assertTrue(api.requests.filter { it.method == "POST" }.all { it.path.endsWith("copy-key") })
    }

    @Test fun duplicateReservedNamesNeverBlindlyChooseEvenIfOnlyOneIsValid() {
        api.keys = listOf(key(), key().copy(id = "duplicate", off = true))
        assertFailsWith<GatewayKeyProvisioningException> { provisioner().provision() }
        assertNull(store.load())
        assertEquals(0, api.copyRequests.get())
        assertEquals(0, api.addRequests.get())
    }

    @Test fun revokedLocalIdNeverRecreatesOrAdoptsAnotherReservedId() {
        store.save(key())
        api.keys = listOf(key().copy(id = "replacement"))
        assertFailsWith<GatewayKeyProvisioningException> { provisioner().provision() }
        assertEquals(key(), store.load())
        assertEquals(0, api.copyRequests.get())
        assertEquals(0, api.addRequests.get())
    }

    @Test fun disabledBrowserReadOnlyAndExpiredLocalMetadataNeverRevive() {
        store.save(key())
        val restricted = listOf(key().copy(off = true), key().copy(browser = true), key().copy(readOnly = true),
            key().copy(expires = "2000-01-01T00:00:00Z"), key().copy(expires = "invalid-expiry"))
        for (metadata in restricted) {
            api.keys = listOf(metadata)
            assertFailsWith<GatewayKeyProvisioningException> { provisioner().provision() }
            assertEquals(key(), store.load())
        }
        assertEquals(0, api.copyRequests.get())
        assertEquals(0, api.addRequests.get())
    }

    @Test fun disabledOrExpiredUniqueReservedKeyWithoutLocalIsNotCopiedOrRecreated() {
        for (metadata in listOf(key().copy(off = true), key().copy(browser = true),
            key().copy(readOnly = true), key().copy(expires = "2000-01-01T00:00:00Z"))) {
            api.keys = listOf(metadata)
            assertFailsWith<GatewayKeyProvisioningException> { provisioner().provision() }
        }
        assertNull(store.load())
        assertEquals(0, api.copyRequests.get())
        assertEquals(0, api.addRequests.get())
    }

    @Test fun futureRfc3339ExpiryIsUsableAndPreservedOnReturnedKey() {
        api.keys = listOf(key().copy(expires = "2999-01-01T00:00:00.123456789+00:00"))
        val recovered = provisioner().provision()
        assertEquals(api.keys.single().expires, recovered.expires)
        assertEquals(key().secret, store.load()!!.secret)
        assertEquals(0, api.addRequests.get())
    }

    @Test fun reservedKeyAppearingOnCreatePreflightNeverTriggersAnotherAdd() {
        var reads = 0
        api.listHandler = { exchange ->
            reads++
            if (reads == 1) api.respond(exchange, 200, api.listBody()) else {
                api.keys = listOf(key())
                api.respond(exchange, 200, api.listBody())
            }
        }
        assertFailsWith<GatewayKeyProvisioningException> { provisioner().provision() }
        assertEquals(0, api.addRequests.get())
        assertEquals(0, api.copyRequests.get())
        assertNull(store.load())
        api.listHandler = null
        assertEquals(key(), provisioner().provision())
        assertEquals(0, api.addRequests.get())
        assertEquals(1, api.copyRequests.get())
    }

    @Test fun duplicateCoreIdsFailClosedBeforeAddOrCopy() {
        api.keys = listOf(key(), key().copy(name = "another name"))
        assertFailsWith<GatewayKeyProvisioningException> { provisioner().provision() }
        assertEquals(0, api.addRequests.get())
        assertEquals(0, api.copyRequests.get())
    }

    @Test fun corruptedLocalFileCannotBecomeNoLocalIdAndIssuePlaceholder() {
        val path = directory.resolve("registered-gateway.json")
        val broken = "corrupted " + ManagementApiFixture.SECRET
        Files.writeString(path, broken)
        val error = assertFailsWith<GatewayCredentialStoreException> { provisioner().provision() }
        assertEquals(broken, Files.readString(path))
        assertEquals(0, api.listRequests.get())
        assertEquals(0, api.addRequests.get())
        assertNull(error.cause)
        assertFalse(error.stackTraceToString().contains(ManagementApiFixture.SECRET))
    }

    @Test fun addHttpFailureDoesNotSavePlaceholderOrRetry() {
        api.addHandler = { api.respond(it, 500, "error " + ManagementApiFixture.SECRET) }
        assertFailsWith<ManagementHttpException> { provisioner().provision() }
        assertNull(store.load())
        assertEquals(1, api.addRequests.get())
        assertEquals(0, api.copyRequests.get())
    }

    @Test fun addTimeoutSurfacesUnknownAndExplicitRetryRecoversCommittedUniqueIdWithoutReAdd() {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        api.addHandler = { exchange ->
            api.keys = listOf(key()) // core committed, but its response never reaches the first request
            try {
                release.await(3, TimeUnit.SECONDS)
                api.respond(exchange, 200, api.listBody(ManagementApiFixture.SECRET))
            } finally { finished.countDown() }
        }
        try {
            assertFailsWith<ManagementExecutionUnknown> { provisioner(Duration.ofMillis(100)).provision() }
            assertNull(store.load())
            assertEquals(1, api.addRequests.get())
        } finally {
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }
        val recovered = provisioner().provision() // explicit UI retry; read metadata, then read-only copy-key
        assertEquals(key(), recovered)
        assertEquals(key(), store.load())
        assertEquals(1, api.addRequests.get())
        assertEquals(1, api.copyRequests.get())
    }

    @Test fun copyTimeoutDoesNotFallBackToIssuingNewKey() {
        api.keys = listOf(key())
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        api.copyHandler = { exchange ->
            try {
                release.await(3, TimeUnit.SECONDS)
                api.respond(exchange, 200, api.listBody(ManagementApiFixture.SECRET))
            } finally { finished.countDown() }
        }
        try {
            assertFailsWith<ManagementExecutionUnknown> { provisioner(Duration.ofMillis(100)).provision() }
            assertNull(store.load())
            assertEquals(1, api.copyRequests.get())
            assertEquals(0, api.addRequests.get())
        } finally {
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun saveFailureAfterCoreIssueDoesNotReAddAndLaterRetryCanRecover() {
        store.beforeCommitForTest = { throw IOException("write failed " + ManagementApiFixture.SECRET) }
        assertFailsWith<GatewayCredentialStoreException> { provisioner().provision() }
        assertNull(store.load())
        assertEquals(1, api.addRequests.get())
        store.beforeCommitForTest = null
        val recovered = provisioner().provision()
        assertEquals(ManagementApiFixture.SECRET, recovered.secret)
        assertEquals(recovered, store.load())
        assertEquals(1, api.addRequests.get())
        assertEquals(1, api.copyRequests.get())
    }

    @Test fun sequentialAndConcurrentCallsOnSameProvisionerIssueOnlyOnce() {
        val provisioner = provisioner()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map { executor.submit<RegisteredGatewayKey> { provisioner.provision() } }
            val ids = futures.map { it.get(5, TimeUnit.SECONDS).id }.toSet()
            assertEquals(1, ids.size)
            assertEquals(1, api.addRequests.get())
            assertEquals(0, api.copyRequests.get())
        } finally { executor.shutdownNow() }
    }
}
