package cc.fplj.magpiemobile.core

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json

/** Authentication-only probe on gateway 3425, never the management port/cookie. No model inference. */
class GatewayHelloClient(private val config: GatewayConfig) {
    private val json = Json { ignoreUnknownKeys = true }
    fun hello(): Hello {
        val conn = URL(config.gatewayBaseUrl.trimEnd('/') + "/api/hello").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = config.connectTimeout.toMillis().toInt()
        conn.readTimeout = config.readTimeout.toMillis().toInt()
        conn.setRequestProperty("Authorization", "Bearer " + config.gatewayKey)
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code != 200) throw CoreException("Gateway authentication probe -> HTTP " + code)
            val body = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 65536) { "Gateway probe response too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val hello = json.decodeFromString(Hello.serializer(), body.toString(Charsets.UTF_8))
            check(hello.name == "magpie") { "Unexpected gateway identity" }
            return hello
        } finally { conn.disconnect() }
    }
}
