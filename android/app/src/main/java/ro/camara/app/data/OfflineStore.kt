package ro.camara.app.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive

/** One atomic journal per server identity. A crash cannot lose the snapshot/outbox boundary. */
class OfflineStore internal constructor(private val namespace: String, private val storage: (ConnectionConfig) -> JournalStorage) {
    constructor(context: Context) : this(context.filesDir.absolutePath, { config -> AndroidJournalStorage(context, config) })
    private fun cacheKey(config: ConnectionConfig) = namespace + "\n" + config.storageIdentity
    private fun remember(config: ConnectionConfig, value: LocalState): LocalState {
        val key = cacheKey(config)
        cached.remove(key)
        cached[key] = value
        while (cached.size > 4) cached.remove(cached.keys.first())
        return value
    }
    fun read(config: ConnectionConfig): LocalState = synchronized(lock) {
        cached[cacheKey(config)]?.let { return@synchronized it }
        val value = storage(config).read()
        remember(config, value)
    }
    private fun save(config: ConnectionConfig, value: LocalState): LocalState {
        val updated = value.copy(revision = value.revision + 1)
        storage(config).write(updated)
        return remember(config, updated)
    }
    fun enqueue(config: ConnectionConfig, build: (Snapshot) -> PendingAction): LocalState = synchronized(lock) {
        val current = read(config)
        require(current.pending.none { staleEpoch(it, current.server) }) { "Serverul a fost restaurat. Rezolvă schimbările vechi din Sincronizare." }
        require(current.pending.none { it.issue != null }) { "Rezolvă acțiunea blocată din Sincronizare înainte de a salva." }
        val projected = current.projected()
        val built = build(projected)
        require(built.mutation.epoch.isBlank() || built.mutation.epoch == current.server.epoch) { "Serverul a fost restaurat. Recalculează propunerea înainte de confirmare." }
        val action = built.copy(mutation = built.mutation.copy(epoch = current.server.epoch))
        require(action.mutation.changes.isNotEmpty() && action.mutation.changes.size <= 200)
        val byKind = action.mutation.changes.groupBy({ it.kind }, { it.id })
        val kinds = byKind.keys + action.mutation.guards.keys
        val records = kinds.associateWith { kind ->
            val ids = if (kind in action.mutation.guards) null else byKind.getValue(kind).toSet()
            ActionRules.records(projected, kind, ids).associateBy { it.getValue("id").jsonPrimitive.content }
        }
        require(action.mutation.changes.all { it.before == records.getValue(it.kind)[it.id] }) { "Datele s-au schimbat. Redeschide previzualizarea." }
        require(action.mutation.guards.all { (kind, expected) -> expected.toSet() == records.getValue(kind).values.toSet() }) { "Datele s-au schimbat. Recalculează lista." }
        val modified = current.pending.flatMap { pending -> pending.mutation.changes.map(Change::kind) }.toSet()
        val guards = action.mutation.guards
        val revisions = guards.keys.filter { it !in modified && current.etag != null }
            .mapNotNull { kind -> current.server.collection_revisions[kind]?.let { revision -> kind to revision } }.toMap()
        val compact = action.copy(mutation = action.mutation.copy(guards = guards - revisions.keys, guard_revisions = revisions))
        save(config, current.copy(pending = current.pending + compact))
    }
    fun server(config: ConnectionConfig, snapshot: Snapshot, etag: String? = null): LocalState = synchronized(lock) {
        val current = read(config)
        val updated = current.copy(server = snapshot, savedAt = System.currentTimeMillis(), etag = etag)
        // The durable snapshot/outbox boundary stays atomic. A freshness tick is memory-only.
        if (current.server == snapshot) remember(config, updated) else save(config, updated)
    }
    fun acknowledge(config: ConnectionConfig, id: String, refreshed: Snapshot? = null): LocalState = synchronized(lock) {
        val current = read(config)
        val action = current.pending.firstOrNull()
        check(action != null && action.id == id)
        val server = refreshed ?: ActionRules.apply(current.server, action.mutation)
        save(config, current.copy(server = server, pending = current.pending.drop(1), etag = null,
            savedAt = if (refreshed != null) System.currentTimeMillis() else current.savedAt))
    }
    fun issue(config: ConnectionConfig, id: String, message: String, snapshot: Snapshot? = null): LocalState = synchronized(lock) {
        val current = read(config)
        save(config, current.copy(server = snapshot ?: current.server, etag = if (snapshot != null) null else current.etag,
            pending = current.pending.map { if (it.id == id) it.copy(issue = message) else it },
            savedAt = if (snapshot != null) System.currentTimeMillis() else current.savedAt))
    }
    fun resolve(config: ConnectionConfig, id: String, keepMine: Boolean): LocalState = synchronized(lock) {
        val current = read(config)
        val first = current.pending.firstOrNull() ?: return@synchronized current
        check(first.id == id && first.issue != null) { "Acțiunea în așteptare s-a schimbat. Verifică din nou lista." }
        val rest = current.pending.drop(1)
        if (!keepMine) save(config, current.copy(pending = rest))
        else {
            check(first.rebasable) { "Reia consumul sau anularea după actualizarea inventarului." }
            check(!staleEpoch(first, current.server)) { "Renunță la schimbările anterioare restaurării și refă-le pe datele actuale." }
            val rebased = first.copy(id = ActionRules.newActionId(), issue = null,
                mutation = first.mutation.copy(epoch = current.server.epoch, changes = first.mutation.changes.map { it.copy(before = ActionRules.find(current.server, it.kind, it.id)) }))
            save(config, current.copy(pending = listOf(rebased) + rest))
        }
    }
    fun discardStale(config: ConnectionConfig): LocalState = synchronized(lock) {
        val current = read(config)
        save(config, current.copy(pending = current.pending.filterNot { staleEpoch(it, current.server) }))
    }
    companion object {
        private val lock = Any()
        private val cached = linkedMapOf<String, LocalState>()
    }
}

internal interface JournalStorage {
    fun read(): LocalState
    fun write(value: LocalState)
}

private class AndroidJournalStorage(private val context: Context, private val config: ConnectionConfig) : JournalStorage {
    private fun file(): AtomicFile {
        val hash = MessageDigest.getInstance("SHA-256").digest(config.storageIdentity.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(File(context.filesDir, "offline-state").apply { mkdirs() }, "$hash.json"))
    }
    override fun read(): LocalState = try {
        ActionRules.json.decodeFromString<LocalState>(String(file().readFully(), Charsets.UTF_8))
    } catch (_: FileNotFoundException) {
        val old = SnapshotCache(context).read(config)
        LocalState(old?.snapshot ?: Snapshot(), old?.savedAt)
    }
    override fun write(value: LocalState) {
        val target = file()
        val stream = target.startWrite()
        try { stream.write(ActionRules.json.encodeToString(value).toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
}
