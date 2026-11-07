package cc.fplj.magpiemobile.core

/** Recover/reuse only an owned id or a unique reserved name. Revocation is never undone.
 * All network/parse failures stop the operation; add-key is sent at most once per attempt.
 * UNKNOWN is surfaced to the UI. A later explicit retry first rereads the list and can
 * recover a unique reserved key with copy-key instead of repeating add-key.
 */
class GatewayKeyProvisioner(
    private val client: CoreManagementClient,
    private val store: GatewayCredentialStore,
) {
    @Synchronized
    fun provision(): RegisteredGatewayKey {
        client.capabilities()
        val local = store.load()
        val keys = client.listCallerKeys()
        if (keys.any { !validGatewayMetadata(it) } || keys.map { it.id }.toSet().size != keys.size) {
            throw GatewayKeyProvisioningException()
        }
        val key = if (local != null) {
            val metadata = keys.filter { it.id == local.id }.singleOrNull() ?: throw GatewayKeyProvisioningException()
            if (!usableGatewayMetadata(metadata)) throw GatewayKeyProvisioningException()
            // access.List() intentionally removes secret. Never replace the owned secret with "".
            metadata.copy(secret = local.secret)
        } else {
            val reserved = keys.filter { it.name == RESERVED_NAME }
            when (reserved.size) {
                0 -> client.createCallerKey(RESERVED_NAME)
                1 -> {
                    val metadata = reserved.single()
                    if (!usableGatewayMetadata(metadata)) throw GatewayKeyProvisioningException()
                    val recovered = client.copyCallerKey(metadata.id)
                    if (recovered.name != RESERVED_NAME) throw GatewayKeyProvisioningException()
                    recovered
                }
                else -> throw GatewayKeyProvisioningException()
            }
        }
        if (!usableGatewayKey(key)) throw GatewayKeyProvisioningException()
        store.save(key)
        return key
    }

    companion object { const val RESERVED_NAME = "Magpie Mobile 内置聊天" }
}

class GatewayKeyProvisioningException : RuntimeException("网关密钥已撤销、受限、过期或身份不唯一；拒绝自动重建，请显式处理")
