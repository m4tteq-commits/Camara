package ro.camara.app.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive

/** One atomic journal per server identity. A crash cannot lose the snapshot/outbox boundary. */
class OfflineStore(private val context: Context) {
    private fun file(config: ConnectionConfig): AtomicFile {
        val hash = MessageDigest.getInstance("SHA-256").digest(config.storageIdentity.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(File(context.filesDir, "offline-state").apply { mkdirs() }, "$hash.json"))
    }
    fun read(config: ConnectionConfig): LocalState = synchronized(lock) {
        try { ActionRules.json.decodeFromString<LocalState>(String(file(config).readFully(), Charsets.UTF_8)) }
        catch (_: FileNotFoundException) {
            val old = SnapshotCache(context).read(config)
            LocalState(old?.snapshot ?: Snapshot(), old?.savedAt)
        }
    }
    private fun save(config: ConnectionConfig, value: LocalState): LocalState {
        val updated = value.copy(revision = value.revision + 1)
        val target = file(config)
        val stream = target.startWrite()
        try { stream.write(ActionRules.json.encodeToString(updated).toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
        return updated
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
        val kinds = action.mutation.changes.map { it.kind }.toSet() + action.mutation.guards.keys
        val records = kinds.associateWith { kind -> ActionRules.records(projected, kind).associateBy { it.getValue("id").jsonPrimitive.content } }
        require(action.mutation.changes.all { it.before == records.getValue(it.kind)[it.id] }) { "Datele s-au schimbat. Redeschide previzualizarea." }
        require(action.mutation.guards.all { (kind, expected) -> expected.toSet() == records.getValue(kind).values.toSet() }) { "Datele s-au schimbat. Recalculează lista." }
        save(config, current.copy(pending = current.pending + action))
    }
    fun server(config: ConnectionConfig, snapshot: Snapshot): LocalState = synchronized(lock) {
        save(config, read(config).copy(server = snapshot, savedAt = System.currentTimeMillis()))
    }
    fun acknowledge(config: ConnectionConfig, id: String, refreshed: Snapshot? = null): LocalState = synchronized(lock) {
        val current = read(config)
        val action = current.pending.firstOrNull()
        check(action != null && action.id == id)
        val server = refreshed ?: ActionRules.apply(current.server, action.mutation)
        save(config, current.copy(server = server, pending = current.pending.drop(1),
            savedAt = if (refreshed != null) System.currentTimeMillis() else current.savedAt))
    }
    fun issue(config: ConnectionConfig, id: String, message: String, snapshot: Snapshot? = null): LocalState = synchronized(lock) {
        val current = read(config)
        save(config, current.copy(server = snapshot ?: current.server,
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
            val rebased = first.copy(id = java.util.UUID.randomUUID().toString(), issue = null,
                mutation = first.mutation.copy(epoch = current.server.epoch, changes = first.mutation.changes.map { it.copy(before = ActionRules.find(current.server, it.kind, it.id)) }))
            save(config, current.copy(pending = listOf(rebased) + rest))
        }
    }
    fun discardStale(config: ConnectionConfig): LocalState = synchronized(lock) {
        val current = read(config)
        save(config, current.copy(pending = current.pending.filterNot { staleEpoch(it, current.server) }))
    }
    companion object { private val lock = Any() }
}
