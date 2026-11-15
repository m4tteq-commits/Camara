package ro.camara.app.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CachedSnapshot(val snapshot: Snapshot, val savedAt: Long)

/** Reads pre-journal installations during migration. New state is stored only in OfflineStore. */
internal class SnapshotCache(context: Context) {
    private val directory = File(context.filesDir, "snapshots")
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(config: ConnectionConfig): AtomicFile {
        val hash = MessageDigest.getInstance("SHA-256").digest(config.storageIdentity.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$hash.json"))
    }

    fun read(config: ConnectionConfig): CachedSnapshot? = synchronized(lock) { runCatching {
        json.decodeFromString<CachedSnapshot>(String(file(config).readFully(), Charsets.UTF_8))
    }.getOrNull() }

    companion object { private val lock = Any() }
}
