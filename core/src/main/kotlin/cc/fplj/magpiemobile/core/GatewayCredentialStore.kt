package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/** Stores only the real core id/name/secret in an existing app-private directory.
 * No gateway-key.txt placeholder, migration/deletion of user files, or per-field Keystore.
 * Temp file is created 0600 in the same directory, fsynced, then atomically replaces the
 * target. There is deliberately no non-atomic fallback. Corruption/links/path anomalies
 * fail closed, rather than returning "no credential" and accidentally issuing another key.
 */
class GatewayCredentialStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    // Deterministic fault injection available only to module tests, before the commit point.
    internal var beforeCommitForTest: (() -> Unit)? = null

    @Synchronized
    fun load(): RegisteredGatewayKey? = safe {
        val location = checkedLocation()
        val path = location.first
        val initial = regularFile(path) ?: return@safe null
        if (initial.size() > MAX_STORE_BYTES) throw GatewayCredentialStoreException()
        val bytes = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val out = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(4096)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                out.write(buffer.array(), 0, count)
                if (out.size() > MAX_STORE_BYTES) throw GatewayCredentialStoreException()
            }
            out.toByteArray()
        }
        verifyLocation(location)
        verifySameFile(initial, regularFile(path))
        val saved = json.decodeFromString(SavedGatewayCredential.serializer(), bytes.toString(Charsets.UTF_8))
        val key = RegisteredGatewayKey(saved.id, saved.name, saved.secret)
        if (!validGatewayIdentity(key)) throw GatewayCredentialStoreException()
        key
    }

    @Synchronized
    fun save(key: RegisteredGatewayKey) {
        safe {
            if (!usableGatewayKey(key)) throw GatewayCredentialStoreException()
            val location = checkedLocation()
            val path = location.first
            // Never silently overwrite a corrupt or unexpected pre-existing file.
            if (regularFile(path) != null) load()
            val previous = regularFile(path)
            val data = json.encodeToString(SavedGatewayCredential(key.id, key.name, key.secret)).toByteArray(Charsets.UTF_8)
            if (data.size > MAX_STORE_BYTES) throw GatewayCredentialStoreException()
            val privateMode = PosixFilePermissions.fromString("rw-------")
            val temp = Files.createTempFile(path.parent, "." + path.fileName + ".", ".tmp",
                PosixFilePermissions.asFileAttribute(privateMode))
            val tempIdentity = regularFile(temp) ?: throw GatewayCredentialStoreException()
            var committed = false
            try {
                FileChannel.open(temp, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.wrap(data)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                if (Files.getPosixFilePermissions(temp, NOFOLLOW_LINKS) != privateMode) {
                    throw GatewayCredentialStoreException()
                }
                beforeCommitForTest?.invoke()
                verifyLocation(location)
                verifySameFile(previous, regularFile(path))
                if (regularFile(temp)?.fileKey() != tempIdentity.fileKey()) throw GatewayCredentialStoreException()
                Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
                committed = true
            } finally {
                if (!committed) {
                    // Only the owned temp, still in the verified parent, may be removed.
                    try {
                        verifyLocation(location)
                        if (temp.parent == path.parent && temp.fileName.toString().startsWith("." + path.fileName + ".") &&
                            regularFile(temp)?.fileKey() == tempIdentity.fileKey()) Files.deleteIfExists(temp)
                    } catch (_: Exception) { /* never touch an unverified path or mask the original failure */ }
                }
            }
        }
    }

    /** Pin every ancestor's identity. Existing symlinks (even dangling ones) are forbidden. */
    private fun checkedLocation(): Pair<Path, List<Pair<Path, Any?>>> {
        val supplied = file.toPath()
        if (supplied.any { it.toString() == ".." || it.toString() == "." }) throw GatewayCredentialStoreException()
        val path = supplied.toAbsolutePath()
        val parent = path.parent ?: throw GatewayCredentialStoreException()
        if (path.fileName == null || path.fileName.toString().isBlank()) throw GatewayCredentialStoreException()
        val identities = mutableListOf<Pair<Path, Any?>>()
        var ancestor = parent.root ?: throw GatewayCredentialStoreException()
        fun record(directory: Path) {
            val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isDirectory || attributes.isSymbolicLink) throw GatewayCredentialStoreException()
            identities.add(directory to attributes.fileKey())
        }
        record(ancestor)
        for (component in parent) {
            ancestor = ancestor.resolve(component)
            record(ancestor)
        }
        regularFile(path) // reject directories, links and other unusual targets before any IO
        return path to identities
    }

    private fun verifyLocation(location: Pair<Path, List<Pair<Path, Any?>>>) {
        val current = checkedLocation()
        if (current.first != location.first || current.second != location.second) throw GatewayCredentialStoreException()
    }

    private fun regularFile(path: Path): BasicFileAttributes? {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { return null }
        if (!attributes.isRegularFile || attributes.isSymbolicLink) throw GatewayCredentialStoreException()
        return attributes
    }

    private fun verifySameFile(before: BasicFileAttributes?, after: BasicFileAttributes?) {
        if (before == null && after == null) return
        if (before == null || after == null || before.fileKey() != after.fileKey() ||
            before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime()) {
            throw GatewayCredentialStoreException()
        }
    }

    private fun <T> safe(action: () -> T): T = try { action() } catch (_: Exception) {
        // Filesystem/JSON errors may quote the file name/content. Never expose or chain them.
        throw GatewayCredentialStoreException()
    }
    companion object { internal const val MAX_STORE_BYTES = 64 * 1024 }
}

@Serializable
private data class SavedGatewayCredential(val id: String, val name: String, val secret: String)
class GatewayCredentialStoreException : RuntimeException("私有网关凭据文件读写失败；拒绝覆盖异常文件或自动创建替代密钥")
