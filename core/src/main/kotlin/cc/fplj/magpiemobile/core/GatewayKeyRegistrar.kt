package cc.fplj.magpiemobile.core

import java.io.File

/** Legacy CLI maintenance helper. App registration uses CoreManagementClient, not terminal tables. */
object GatewayKeyRegistrar {
    fun listKeysArguments(exePath: String): List<String> {
        require(File(exePath).isAbsolute) { "Executable must be absolute" }
        return listOf(exePath, "gateway-key", "list")
    }
    fun addKeyArguments(exePath: String, name: String): List<String> {
        require(File(exePath).isAbsolute) { "Executable must be absolute" }
        require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Unsupported key name" }
        return listOf(exePath, "gateway-key", "add", name)
    }

    @Deprecated("Do not prove registration by searching a masked CLI table. Verify the core key id and an authenticated gateway request.")
    fun appKeyRegistered(engineListOutput: String, appKey: String): Boolean {
        throw UnsupportedOperationException("Masked CLI output cannot establish gateway key registration")
    }
}
