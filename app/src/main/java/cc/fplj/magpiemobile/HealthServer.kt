package cc.fplj.magpiemobile

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Local observation only; loopback is NOT a root-only permission boundary. No credentials returned. */
class HealthServer(private val infoProvider: () -> Map<String, String>, private val port: Int = 3431) {
    private var socket: ServerSocket? = null
    private var executor: ThreadPoolExecutor? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Synchronized fun start() {
        check(socket == null) { "HealthServer 已启动" }
        val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", port), 8) }
        val pool = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
            { task -> Thread(task, "MagpieHealthClient").apply { isDaemon = true } })
        socket = server; executor = pool
        Thread({
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    clients.add(client)
                    try { pool.execute { handle(client) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
                } catch (_: Exception) { break }
            }
        }, "MagpieHealthAccept").apply { isDaemon = true; start() }
    }

    val boundPort: Int @Synchronized get() = socket?.localPort ?: -1
    val isRunning: Boolean @Synchronized get() = socket?.isClosed == false

    private fun handle(client: Socket) {
        try {
            client.use { connection ->
                val head = StringBuilder()
                val input = connection.getInputStream()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!head.endsWith("\r\n\r\n")) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0 || head.length >= 8192) return
                    connection.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).toInt().coerceAtLeast(1)
                    val byte = input.read()
                    if (byte < 0) return
                    head.append(byte.toChar())
                }
                val valid = head.toString().substringBefore("\r\n").split(' ').let {
                    it.size == 3 && it[0] == "GET" && it[1] == "/healthz"
                }
                val body = if (valid) infoProvider().entries.joinToString(",", "{", "}") {
                    "\"" + escape(it.key) + "\":\"" + escape(it.value) + "\""
                } else "{}"
                val bytes = body.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 " + (if (valid) "200 OK" else "404 Not Found") + "\r\n" +
                    "Content-Type: application/json\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n"
                connection.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
            }
        } catch (_: Exception) {
            // Failed or slow observation must not affect gateway startup.
        } finally { clients.remove(client); try { client.close() } catch (_: Exception) {} }
    }

    @Synchronized fun stop() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        executor?.shutdownNow(); executor = null
        clients.forEach { try { it.close() } catch (_: Exception) {} }; clients.clear()
    }

    private fun escape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
    }
}
