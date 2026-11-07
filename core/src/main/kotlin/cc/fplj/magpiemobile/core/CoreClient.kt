package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Duration

/** Gateway probe only (fixed upstream 54dc257f): /api/hello JSON lives on gateway 3425,
 * not GUI 3430. New management code must use [CoreManagementClient]. State is a GUI
 * management operation, not an unauthenticated gateway probe, and needs explicit cookie.
 */
class CoreClient(
    baseUrl: String = "http://127.0.0.1:3425",
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val transport = BoundedCoreHttp(LoopbackEndpoint(baseUrl), timeout)

    /** No manager credential is stored or sent by this gateway probe. */
    fun hello(): Hello {
        val body = try { transport.request("GET", "/api/hello") } catch (e: ManagementException) {
            throw CoreException(e.message ?: "核心探测失败")
        }
        return try { json.decodeFromString(Hello.serializer(), body) } catch (_: Exception) {
            throw CoreException("核心探测响应格式无效")
        }
    }

    @Deprecated("State belongs to authenticated GUI management; use CoreManagementClient.state")
    fun state(
        credential: ManagerCredential,
        managementBaseUrl: String = "http://127.0.0.1:" + credential.port,
    ): JsonObject = CoreManagementClient(credential, managementBaseUrl, timeout).state()
}

@kotlinx.serialization.Serializable
data class Hello(val name: String, val version: String)
class CoreException(message: String) : RuntimeException(message)
