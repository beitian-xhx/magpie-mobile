package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.RegisteredGatewayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayKeysViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val sampleKey1 = RegisteredGatewayKey(
        id = "id-1",
        name = "Magpie Mobile 内置聊天",
        secret = "",
        off = false,
        readOnly = false,
    )

    private val sampleKey2 = RegisteredGatewayKey(
        id = "id-2",
        name = "Chatbox",
        secret = "",
        off = false,
        readOnly = false,
    )

    @Test
    fun refreshLoadsKeysSuccessfully() = runTest(testDispatcher) {
        val keys = listOf(sampleKey1, sampleKey2)
        val vm = GatewayKeysViewModel(
            keysReader = { keys }
        )

        vm.refresh()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(2, state.keys.size)
        assertEquals("id-1", state.keys[0].id)
        assertEquals("Chatbox", state.keys[1].name)
        assertEquals("", state.error)
    }

    @Test
    fun refreshHandlesError() = runTest(testDispatcher) {
        val vm = GatewayKeysViewModel(
            keysReader = { error("网络超时") }
        )

        vm.refresh()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertTrue(state.keys.isEmpty())
        assertTrue(state.error.contains("网络超时"))
    }

    @Test
    fun createKeyRejectsBlankName() = runTest(testDispatcher) {
        var createCalled = false
        val vm = GatewayKeysViewModel(
            keyCreator = { name ->
                createCalled = true
                RegisteredGatewayKey(id = "new-id", name = name, secret = "sk-test")
            }
        )

        vm.createKey("   ")
        advanceUntilIdle()

        assertFalse(createCalled)
        assertEquals("密钥名称不能为空", vm.state.value.error)
    }

    @Test
    fun createKeySucceedsAndNotifiesCallback() = runTest(testDispatcher) {
        val createdKey = RegisteredGatewayKey(
            id = "id-new",
            name = "NextChat",
            secret = "sk-magpie-key-0123456789abcdef0123456789abcdef0123456789abcdef",
        )
        val readCount = AtomicInteger(0)
        var callbackSecret = ""

        val vm = GatewayKeysViewModel(
            keysReader = {
                readCount.incrementAndGet()
                listOf(createdKey)
            },
            keyCreator = { name ->
                assertEquals("NextChat", name)
                createdKey
            }
        )

        vm.createKey(" NextChat ") { secret ->
            callbackSecret = secret.secret
        }
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(createdKey, state.newlyCreatedKey)
        assertEquals("sk-magpie-key-0123456789abcdef0123456789abcdef0123456789abcdef", callbackSecret)
        assertTrue(state.notice.contains("NextChat"))
        assertEquals(1, state.keys.size)

        vm.dismissNewKeyDialog()
        assertNull(vm.state.value.newlyCreatedKey)
    }

    @Test
    fun copyKeySucceedsAndReturnsSecret() = runTest(testDispatcher) {
        val copiedKey = RegisteredGatewayKey(
            id = "id-2",
            name = "Chatbox",
            secret = "sk-magpie-key-abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdef",
        )
        var returnedSecret = ""

        val vm = GatewayKeysViewModel(
            keyCopier = { id ->
                assertEquals("id-2", id)
                copiedKey
            }
        )

        vm.copyKey("id-2") { secret ->
            returnedSecret = secret
        }
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals("sk-magpie-key-abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdef", returnedSecret)
        assertTrue(state.notice.contains("Chatbox"))
        assertEquals("", state.error)
    }
}
