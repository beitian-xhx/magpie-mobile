package cc.fplj.magpiemobile

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * P1 第七块（PoC 内）：loopback-only 健康端点。
 *
 * 注意：com.sun.net.httpserver 是 JDK 模块，Android 运行时不含——
 * CI 编译已抓出（v1 教训）。此处用原始 ServerSocket 实现极简 HTTP。
 *
 * 安全（plan.md §4.3）：显式绑定 127.0.0.1（非 0.0.0.0）；无敏感数据；
 * 端口 3431（管理 3430 / 网关 3425 之外的观测端口）。
 */
class HealthServer(
    private val infoProvider: () -> Map<String, String>,
    private val port: Int = 3431,
) {
    private var socket: ServerSocket? = null
    private var executor: java.util.concurrent.ExecutorService? = null
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        check(socket == null) { "HealthServer 已启动" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", port), 8)
        socket = ss
        val ex = Executors.newSingleThreadExecutor()
        executor = ex
        worker = Thread {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    ex.execute { handle(client) }
                } catch (_: Exception) {
                    break // socket closed
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun handle(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 3000
                val reader = c.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                // 消费请求头（极简实现：不需要请求体）
                while (reader.readLine()?.isNotEmpty() == true) { /* drain */ }
                val body = buildString {
                    append("{")
                    infoProvider().entries.forEachIndexed { i, (k, v) ->
                        if (i > 0) append(",")
                        append("\"").append(k.escape()).append("\":\"").append(v.escape()).append("\"")
                    }
                    append("}")
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: " + bytes.size + "\r\n" +
                    "Connection: close\r\n\r\n"
                c.getOutputStream().apply {
                    write(head.toByteArray(Charsets.UTF_8))
                    write(bytes)
                    flush()
                }
            } catch (_: Exception) {
                // 客户端断开等——观测端点，不打扰主流程
            }
        }
    }

    @Synchronized
    fun stop() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        executor?.shutdownNow()
        executor = null
        worker = null
    }

    val isRunning: Boolean
        @Synchronized get() = socket != null && !socket!!.isClosed

    private fun String.escape(): String = filter { it != '"' && it != '\\' }
}
