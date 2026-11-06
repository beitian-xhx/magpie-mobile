package cc.fplj.magpiemobile

import android.app.Activity
import android.os.Bundle
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * P1 第七块（PoC 内）：loopback-only 健康端点。
 *
 * 目的：root 验证无需读屏——pm install 后直接 curl http://127.0.0.1:3431/healthz
 * 即可判定应用存活与载荷状态。
 *
 * 安全（plan.md §4.3）：仅绑定 127.0.0.1（构造期强制）；无敏感数据，
 * 只回 JSON 状态；端口固定 3431（管理 3430 / 网关 3425 之外的观测端口）。
 */
class HealthServer(
    private val infoProvider: () -> Map<String, String>,
    private val port: Int = 3431,
) {
    private var server: HttpServer? = null

    /** 端口必须 loopback 语义——InetSocketAddress 显式绑 127.0.0.1，不是 0.0.0.0。 */
    @Synchronized
    fun start() {
        check(server == null) { "HealthServer 已启动" }
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        s.createContext("/healthz") { ex ->
            val payload = buildString {
                append("{")
                infoProvider().entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) append(",")
                    append("\"").append(k.escape()).append("\":\"").append(v.escape()).append("\"")
                }
                append("}")
            }.toByteArray()
            ex.getResponseHeaders().add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        s.executor = Executors.newSingleThreadExecutor()
        s.start()
        server = s
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
    }

    val isRunning: Boolean
        @Synchronized get() = server != null

    private fun String.escape(): String = filter { it != '"' && it != '\\' }