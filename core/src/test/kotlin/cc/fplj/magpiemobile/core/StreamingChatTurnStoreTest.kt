package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingChatTurnStoreTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var server: HttpServer
    private var port = 0

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        port = server.address.port
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun session(
        turnStore: ChatTurnStore? = null,
        initialMessages: List<ChatClient.Message> = emptyList(),
        store: SessionStore? = null,
    ): StreamingChatSession {
        val config = GatewayConfig(gatewayKey = "k", gatewayBaseUrl = "http://127.0.0.1:$port")
        return StreamingChatSession(
            StreamingChatClient(config), ChatClient(config), store, "m", { 123L },
            turnStore, initialMessages,
        )
    }

    private fun pair(text: String = "old user") = listOf(
        ChatClient.Message("user", text), ChatClient.Message("assistant", "old reply"),
    )

    private fun sse(text: String, done: Boolean = true): String =
        """data: {"choices":[{"delta":{"content":"$text"}}]}""" + "\n\n" +
            if (done) "data: [DONE]\n\n" else ""

    private fun respond(ex: HttpExchange, body: String, type: String = "text/event-stream") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun status(ex: HttpExchange, code: Int) {
        ex.sendResponseHeaders(code, -1)
        ex.close()
    }

    private fun messages(body: String): List<ChatClient.Message> =
        Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray.map {
            ChatClient.Message(it.jsonObject.getValue("role").jsonPrimitive.content,
                it.jsonObject.getValue("content").jsonPrimitive.content)
        }

    private class RecordingTurnStore : ChatTurnStore {
        val begun = linkedMapOf<String, SessionRecord>()
        val completed = linkedMapOf<String, SessionRecord>()
        val failures = linkedMapOf<String, SessionRecord>()
        val completeAttempts = mutableListOf<Pair<String, SessionRecord>>()
        var beginAttempts = 0
        var beginFailure: Exception? = null
        var completeFailure: Exception? = null
        var failFailure: Exception? = null
        var onBegin: () -> Unit = {}
        var onComplete: () -> Unit = {}

        override fun begin(user: SessionRecord): String {
            beginAttempts++
            onBegin()
            beginFailure?.let { throw it }
            val id = "turn-$beginAttempts"
            begun[id] = user
            return id
        }

        override fun complete(turnId: String, assistant: SessionRecord) {
            completeAttempts.add(turnId to assistant)
            onComplete()
            completeFailure?.let { throw it }
            check(begun.containsKey(turnId))
            completed[turnId] = assistant
        }

        override fun fail(turnId: String, failure: SessionRecord) {
            check(begun.containsKey(turnId))
            failures[turnId] = failure
            failFailure?.let { throw it }
        }

        fun successfulMessages(): List<ChatClient.Message> = completed.flatMap { (id, assistant) ->
            val user = begun.getValue(id)
            listOf(ChatClient.Message(user.role, user.text), ChatClient.Message(assistant.role, assistant.text))
        }
    }

    @Test
    fun beginPrecedesHttpAndCompletePrecedesInMemoryAssistant() {
        val n = AtomicInteger(0)
        val begunBeforeHttp = AtomicBoolean(false)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val turns = RecordingTurnStore()
        val initial = pair()
        val pending = ChatClient.Message("user", "new user")
        val s = session(turns, initial)
        turns.onBegin = {
            assertEquals(0, n.get())
            assertEquals(initial, s.messages())
            events.add("begin")
            begunBeforeHttp.set(true)
        }
        turns.onComplete = {
            assertEquals(1, n.get())
            assertEquals(initial + pending, s.messages())
            assertTrue(turns.completed.isEmpty())
            events.add("complete")
            assertFailsWith<IllegalStateException> { s.send("nested") }
            assertFailsWith<IllegalStateException> { s.reset() }
        }
        server.createContext("/v1/chat/completions") { ex ->
            assertTrue(begunBeforeHttp.get())
            events.add("http")
            n.incrementAndGet()
            assertEquals(initial + pending, messages(ex.requestBody.bufferedReader().readText()))
            respond(ex, sse("new reply"))
        }

        assertEquals("new reply", s.send(pending.content))
        assertEquals(listOf("begin", "http", "complete"), events)
        assertEquals(SessionRecord(123L, "user", "chat:m", pending.content), turns.begun.getValue("turn-1"))
        assertEquals(SessionRecord(123L, "assistant", "chat:m:stream", "new reply"),
            turns.completed.getValue("turn-1"))
        assertEquals(initial + pending + ChatClient.Message("assistant", "new reply"), s.messages())
        assertTrue(turns.failures.isEmpty())
        assertEquals(1, n.get())
    }

    @Test
    fun beginFailuresMakeZeroHttpCallsNeverFailAndRetainOnlyRecoveredHistory() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, sse("unexpected"))
        }
        val secret = "ordinary.store.key/123+private-path"
        for (storageError in listOf(
            IOException(secret), IllegalStateException(secret), ChatException(secret, 405),
            StreamingChatException(StreamingChatFailure.SSE_ERROR, secret, 501),
        )) {
            val turns = RecordingTurnStore().apply { beginFailure = storageError }
            val initial = pair()
            val s = session(turns, initial)
            val e = assertFailsWith<StreamingChatException> { s.send("failed user") }
            assertEquals(if (storageError is IOException) StreamingChatFailure.IO_FAILURE
                else StreamingChatFailure.CHAT_FAILURE, e.failure)
            assertEquals(e.failure.safeMessage, e.message)
            assertEquals("", e.partialText)
            assertNull(e.httpCode)
            assertNull(e.cause)
            assertTrue(e.suppressed.isEmpty())
            assertFalse(e.stackTraceToString().contains(secret))
            assertEquals(initial, s.messages())
            assertEquals(1, turns.beginAttempts)
            assertTrue(turns.begun.isEmpty())
            assertTrue(turns.completeAttempts.isEmpty())
            assertTrue(turns.failures.isEmpty())
            assertEquals(0, n.get())
            s.reset()
            assertTrue(s.messages().isEmpty())
        }
    }

    @Test
    fun completeIOExceptionMakesOneHttpCallRollsBackOnlyPendingAndAllowsExplicitNewSend() {
        val n = AtomicInteger(0)
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/v1/chat/completions") { ex ->
            bodies.add(ex.requestBody.bufferedReader().readText())
            n.incrementAndGet()
            respond(ex, sse("complete reply"))
        }
        val secret = "store-private-path-and-secret"
        val turns = RecordingTurnStore().apply { completeFailure = IOException(secret) }
        val initial = pair("same user")
        val s = session(turns, initial)
        val e = assertFailsWith<StreamingChatException> { s.send("same user") }
        assertEquals(StreamingChatFailure.IO_FAILURE, e.failure)
        assertEquals("Stream I/O failure", e.message)
        assertEquals("", e.partialText)
        assertNull(e.cause)
        assertFalse(e.stackTraceToString().contains(secret))
        assertEquals(1, n.get())
        assertEquals(initial, s.messages())
        assertEquals(1, turns.completeAttempts.size)
        assertTrue(turns.completed.isEmpty())
        assertEquals(SessionRecord(123L, "error", "chat:m:stream", "Stream I/O failure; partial characters=0"),
            turns.failures.getValue("turn-1"))
        assertEquals(initial + ChatClient.Message("user", "same user"), messages(bodies[0]))

        turns.completeFailure = null
        assertEquals("complete reply", s.send("same user"))
        assertEquals(2, n.get()) // 仅用户主动的新 send 发出第二个 HTTP 请求。
        assertEquals(messages(bodies[0]), messages(bodies[1]))
        assertEquals(setOf("turn-2"), turns.completed.keys)
        assertEquals(initial + ChatClient.Message("user", "same user") +
            ChatClient.Message("assistant", "complete reply"), s.messages())
    }

    @Test
    fun completeCannotForgeHttpOrLeakStreamingPartial() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, sse("not committed"))
        }
        val secret = "ordinary.store.key/opaque-value"
        for (storageError in listOf(
            ChatException(secret, 501), StreamingChatException(StreamingChatFailure.SSE_ERROR, secret, 405),
        )) {
            val turns = RecordingTurnStore().apply { completeFailure = storageError }
            val s = session(turns)
            val before = n.get()
            val e = assertFailsWith<StreamingChatException> { s.send("hello") }
            assertEquals(before + 1, n.get())
            assertEquals(StreamingChatFailure.CHAT_FAILURE, e.failure)
            assertEquals("Chat failed", e.message)
            assertEquals("", e.partialText)
            assertNull(e.httpCode)
            assertNull(e.cause)
            assertFalse(e.stackTraceToString().contains(secret))
            assertTrue(s.messages().isEmpty())
            assertEquals("Chat failed; partial characters=0", turns.failures.getValue("turn-1").text)
            assertEquals(1, turns.completeAttempts.size)
            assertTrue(turns.completed.isEmpty())
        }
    }

    @Test
    fun failIOExceptionDoesNotReplaceFixedStreamExceptionOrStorePartial() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, sse("partial-private-text", done = false))
        }
        val secret = "fail-store-private-path-and-secret"
        val turns = RecordingTurnStore().apply { failFailure = IOException(secret) }
        val initial = pair()
        val s = session(turns, initial)
        val e = assertFailsWith<StreamingChatException> { s.send("failed user") }
        assertEquals(StreamingChatFailure.EOF_BEFORE_DONE, e.failure)
        assertEquals("Stream EOF before [DONE]", e.message)
        assertEquals("partial-private-text", e.partialText)
        assertEquals(200, e.httpCode)
        assertNull(e.cause)
        assertFalse(e.stackTraceToString().contains(secret))
        assertTrue(e.suppressed.isEmpty())
        assertEquals(1, n.get())
        assertEquals(initial, s.messages())
        assertTrue(turns.completeAttempts.isEmpty())
        assertEquals(SessionRecord(123L, "error", "chat:m:stream",
            "Stream EOF before [DONE]; partial characters=20"), turns.failures.getValue("turn-1"))
        s.reset()
        assertTrue(s.messages().isEmpty())
    }

    @Test
    fun failExceptionDoesNotReplaceFixedHttpFailure() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            status(ex, 401)
        }
        val secret = "fail-private-token"
        val turns = RecordingTurnStore().apply { failFailure = ChatException(secret, 501) }
        val s = session(turns)
        val e = assertFailsWith<ChatException> { s.send("hello") }
        assertEquals(401, e.httpCode)
        assertEquals("chat -> HTTP 401", e.message)
        assertNull(e.cause)
        assertFalse(e.stackTraceToString().contains(secret))
        assertEquals("HTTP 401", turns.failures.getValue("turn-1").text)
        assertEquals(1, n.get())
        assertTrue(s.messages().isEmpty())
        assertTrue(turns.completeAttempts.isEmpty())
    }

    @Test
    fun restoredContextExcludesFailedPartialUncommittedAndPendingTurns() {
        val n = AtomicInteger(0)
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/v1/chat/completions") { ex ->
            bodies.add(ex.requestBody.bufferedReader().readText())
            when (n.incrementAndGet()) {
                1 -> respond(ex, sse("saved reply"))
                2 -> respond(ex, sse("partial-private", done = false))
                3 -> respond(ex, sse("uncommitted reply"))
                else -> respond(ex, sse("fresh reply"))
            }
        }
        val turns = RecordingTurnStore()
        val s = session(turns)
        s.send("saved user")
        val saved = s.messages()
        assertFailsWith<StreamingChatException> { s.send("failed user") }
        turns.completeFailure = IOException("private-store-path")
        assertFailsWith<StreamingChatException> { s.send("uncommitted user") }
        assertEquals(3, n.get())
        assertEquals(saved, s.messages())
        turns.completeFailure = null
        turns.begin(SessionRecord(123L, "user", "chat:m", "orphaned pending user"))
        val restored = session(turns, turns.successfulMessages())
        assertEquals(saved, restored.messages())
        restored.send("fresh user")
        assertEquals(4, n.get())
        assertEquals(saved + ChatClient.Message("user", "fresh user"), messages(bodies[3]))
        for (excluded in listOf("failed user", "partial-private", "uncommitted user", "uncommitted reply",
            "orphaned pending user")) {
            assertFalse(bodies[3].contains(excluded))
        }
        assertEquals(listOf("user", "assistant", "user", "assistant"), restored.messages().map { it.role })
    }

    @Test
    fun initialMessagesAreCopiedAndSnapshotsAndResetDoNotMutateCallerHistory() {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/v1/chat/completions") { ex ->
            bodies.add(ex.requestBody.bufferedReader().readText())
            respond(ex, sse("reply"))
        }
        val original = (pair() + pair("second user")).toMutableList()
        val expected = original.toList()
        val s = session(initialMessages = original)
        for (index in original.indices) assertNotSame(original[index], s.messages()[index])
        original.clear()
        assertEquals(expected, s.messages())
        val snapshot = s.messages() as MutableList<ChatClient.Message>
        snapshot.clear()
        assertEquals(expected, s.messages())
        s.send("fresh user")
        assertEquals(expected + ChatClient.Message("user", "fresh user"), messages(bodies.single()))
        s.reset()
        assertTrue(s.messages().isEmpty())
        assertTrue(original.isEmpty())
        assertEquals(4, expected.size)
    }

    @Test
    fun initialMessagesRejectAnythingOtherThanCompleteAlternatingPairsBeforePersistenceOrHttp() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, sse("unexpected"))
        }
        val turns = RecordingTurnStore()
        for (roles in listOf(
            listOf("user"), listOf("assistant"), listOf("assistant", "user"),
            listOf("user", "user"), listOf("system", "assistant"), listOf("user", "error"),
            listOf("user", "assistant", "user"), listOf("user", "assistant", "assistant", "user"),
        )) {
            val e = assertFailsWith<IllegalArgumentException> {
                session(turns, roles.map { ChatClient.Message(it, "private-history-text") })
            }
            assertEquals("Initial messages must be complete user/assistant pairs", e.message)
            assertFalse(e.stackTraceToString().contains("private-history-text"))
        }
        assertEquals(0, turns.beginAttempts)
        assertTrue(turns.completeAttempts.isEmpty())
        assertTrue(turns.failures.isEmpty())
        assertEquals(0, n.get())
        assertTrue(session(turns, emptyList()).messages().isEmpty())
        assertEquals(listOf(ChatClient.Message("user", ""), ChatClient.Message("assistant", "")),
            session(turns, listOf(ChatClient.Message("user", ""), ChatClient.Message("assistant", ""))).messages())
    }

    @Test
    fun turnStoreTakesPrecedenceAndNeverCreatesOrAppendsLegacyJsonl() {
        server.createContext("/v1/chat/completions") { ex -> respond(ex, sse("reply")) }
        val file = File(tmp, "legacy.jsonl")
        val legacy = SessionStore(file)
        val turns = RecordingTurnStore()
        val s = session(turns, store = legacy)
        s.send("hello")
        assertFalse(file.exists())
        assertTrue(legacy.readAll().isEmpty())
        assertEquals(1, turns.beginAttempts)
        assertEquals(1, turns.completed.size)
    }

    @Test
    fun genuine405And501FallbackOnceUseSameTurnAndRestoredContext() {
        val code = AtomicInteger(405)
        val n = AtomicInteger(0)
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.bufferedReader().readText()
            bodies.add(body)
            n.incrementAndGet()
            if (Json.parseToJsonElement(body).jsonObject["stream"]?.jsonPrimitive?.content == "true") {
                status(ex, code.get())
            } else {
                respond(ex, """{"choices":[{"message":{"content":"fallback reply"}}]}""", "application/json")
            }
        }
        for (unsupported in listOf(405, 501)) {
            code.set(unsupported)
            val before = n.get()
            val turns = RecordingTurnStore()
            val initial = pair()
            val s = session(turns, initial)
            assertEquals("fallback reply", s.send("new user"))
            assertEquals(before + 2, n.get())
            assertEquals(1, turns.beginAttempts)
            assertEquals(1, turns.completeAttempts.size)
            assertEquals("chat:m:fallback", turns.completed.getValue("turn-1").engine)
            assertTrue(turns.failures.isEmpty())
            assertEquals(initial + ChatClient.Message("user", "new user"), messages(bodies[before]))
            assertEquals(messages(bodies[before]), messages(bodies[before + 1]))
            assertEquals(initial + ChatClient.Message("user", "new user") +
                ChatClient.Message("assistant", "fallback reply"), s.messages())
        }
    }

    @Test
    fun persistenceFailureAfterFallbackNeverAddsThirdHttpCall() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            if (n.incrementAndGet() == 1) status(ex, 501) else {
                respond(ex, """{"choices":[{"message":{"content":"fallback reply"}}]}""", "application/json")
            }
        }
        val turns = RecordingTurnStore().apply { completeFailure = ChatException("private-store-key", 405) }
        val s = session(turns)
        val e = assertFailsWith<StreamingChatException> { s.send("hello") }
        assertEquals(StreamingChatFailure.CHAT_FAILURE, e.failure)
        assertEquals(2, n.get())
        assertTrue(s.messages().isEmpty())
        assertEquals(SessionRecord(123L, "error", "chat:m:fallback", "Chat failed; partial characters=0"),
            turns.failures.getValue("turn-1"))
        assertTrue(turns.completed.isEmpty())
    }
}
