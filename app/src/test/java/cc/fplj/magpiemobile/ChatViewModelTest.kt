package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.fplj.magpiemobile.core.GatewayConfig
import cc.fplj.magpiemobile.core.SessionRecord
import cc.fplj.magpiemobile.data.ChatDatabase
import cc.fplj.magpiemobile.data.ChatRepository
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChatViewModelTest {
    private lateinit var app: Application
    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, ChatDatabase::class.java).build()
        repo = ChatRepository(db)
    }

    @After fun teardown() {
        stores.forEach { it.clear() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun settled(vm: ChatViewModel) = runBlocking {
        withTimeout(10000) { vm.state.first { !it.busy && !it.sending } }
    }

    private fun vm(connection: () -> ChatConnection? = { null }): ChatViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(app, { repo }, connection) as T
            }
        }
        return ViewModelProvider(store, factory)[ChatViewModel::class.java].also { settled(it) }
    }

    @Test fun initializesOfflineAndKeepsSeparateConversationDrafts() {
        val vm = vm()
        assertTrue(vm.state.value.ready)
        val first = vm.state.value.selected!!.id
        vm.editDraft("draft one")
        vm.newConversation(); settled(vm)
        assertNotEquals(first, vm.state.value.selected!!.id)
        assertEquals("", vm.state.value.draft)
        vm.editDraft("draft two")
        vm.selectConversation(first); settled(vm)
        assertEquals("draft one", vm.state.value.draft)
        vm.send("provider/model")
        assertFalse(vm.state.value.sending)
        assertEquals("draft one", vm.state.value.draft)
    }

    @Test fun reopeningRestoresOnlySuccessfulContextAndSendsNothing() {
        val first = vm()
        val id = first.state.value.selected!!.id
        runBlocking(Dispatchers.IO) {
            val turns = repo.turnStore(id)
            val success = turns.begin(SessionRecord(1, "user", "chat:p/m", "completed user"))
            turns.complete(success, SessionRecord(2, "assistant", "stream:p/m", "completed answer"))
            val failed = turns.begin(SessionRecord(3, "user", "chat:p/m", "failed user"))
            turns.fail(failed, SessionRecord(4, "error", "stream:p/m", "HTTP 429"))
        }
        val reopened = vm()
        assertEquals(id, reopened.state.value.selected!!.id)
        assertEquals(4, reopened.state.value.messages.size)
        assertFalse(reopened.state.value.sending)
        assertEquals("", reopened.state.value.draft)
        val context = runBlocking(Dispatchers.IO) { repo.context(id) }
        assertEquals(listOf("completed user", "completed answer"), context.map { it.content })
    }

    @Test fun retainedViewModelDoesNotDuplicateSendAndRefreshIsReadOnly() {
        Probe().use { probe ->
            val vm = vm { ChatConnection(probe.config) { true } }
            val store = stores.last()
            vm.editDraft("one paid turn")
            vm.send("provider/model")
            assertTrue(probe.started.await(10, TimeUnit.SECONDS))
            assertTrue(vm.state.value.sending)
            val retained = ViewModelProvider(store, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Must retain ViewModel")
            })[ChatViewModel::class.java]
            assertSame(vm, retained)
            retained.send("provider/model")
            retained.refresh()
            retained.newConversation()
            assertEquals(1, probe.requests.get())
            probe.release.countDown()
            settled(vm)
            assertEquals("", vm.state.value.draft)
            assertEquals(listOf("user", "assistant"), vm.state.value.messages.map { it.role })
            assertFalse(vm.state.value.toString().contains("synthetic-gateway-secret"))
            vm.refresh(); settled(vm)
            assertEquals(1, probe.requests.get())
        }
    }

    @Test fun failedStreamKeepsDraftAndDoesNotReplayOnRefreshOrRecreation() {
        Probe(success = false).use { probe ->
            val vm = vm { ChatConnection(probe.config) { true } }
            vm.editDraft("retry only by user")
            vm.send("provider/model")
            assertTrue(probe.started.await(10, TimeUnit.SECONDS))
            probe.release.countDown(); settled(vm)
            assertEquals("retry only by user", vm.state.value.draft)
            assertTrue(vm.state.value.notice.contains("未自动重发"))
            assertEquals(1, probe.requests.get())
            vm.refresh(); settled(vm)
            val reopened = vm { ChatConnection(probe.config) { true } }
            assertFalse(reopened.state.value.sending)
            assertEquals(1, probe.requests.get())
            assertTrue(runBlocking(Dispatchers.IO) { repo.context(vm.state.value.selected!!.id) }.isEmpty())
        }
    }

    /** Local synthetic SSE only: no vendor key, DNS, inference, or public network. */
    private class Probe(val success: Boolean = true) : AutoCloseable {
        private val socket = ServerSocket(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val config = GatewayConfig(gatewayBaseUrl = "http://127.0.0.1:" + socket.localPort,
            gatewayKey = "synthetic-gateway-secret", readTimeout = Duration.ofSeconds(10))
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val accepted = try { socket.accept() } catch (_: java.net.SocketException) { break }
                requests.incrementAndGet()
                accepted.use { client ->
                val input = client.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    if (line.isEmpty()) break
                }
                repeat(length) { input.read() } // ASCII synthetic request only.
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                val body = "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n" +
                    if (success) "data: [DONE]\n\n" else ""
                val bytes = body.toByteArray()
                val header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n"
                client.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
                }
            }
        }
        override fun close() { release.countDown(); socket.close(); worker.join(10000) }
    }
}
