package ro.camara.app.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
private data class CartEnvelope(val draft: ShoppingCart? = null, val closedId: String? = null, val closedIds: List<String> = emptyList())
class CartStore(private val context: Context) {
    private fun file(config: ConnectionConfig): AtomicFile {
        val hash = MessageDigest.getInstance("SHA-256").digest(config.storageIdentity.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(File(context.filesDir, "shopping-carts").apply { mkdirs() }, "$hash.json"))
    }
    private fun envelope(config: ConnectionConfig): CartEnvelope = try { ActionRules.json.decodeFromString(String(file(config).readFully(), Charsets.UTF_8)) }
        catch (_: FileNotFoundException) { CartEnvelope() }
    fun read(config: ConnectionConfig): ShoppingCart? = synchronized(lock) { envelope(config).draft }
    fun open(config: ConnectionConfig, snapshot: Snapshot): ShoppingCart = synchronized(lock) {
        val existing = envelope(config).draft
        if (existing != null) {
            if (snapshot.purchases.none { it.id == existing.id }) return@synchronized existing
            clear(config, existing.id)
        }
        CheckoutRules.start(snapshot).also { save(config, it) }
    }
    private fun write(config: ConnectionConfig, value: CartEnvelope) {
        val target = file(config); val stream = target.startWrite()
        try { stream.write(ActionRules.json.encodeToString(value).toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    fun save(config: ConnectionConfig, cart: ShoppingCart) = synchronized(lock) {
        val old = envelope(config)
        if (old.closedId == cart.id || cart.id in old.closedIds || (old.draft != null && old.draft.id != cart.id) ||
            (old.draft?.id == cart.id && old.draft.revision > cart.revision)) return@synchronized
        write(config, old.copy(draft = cart))
    }
    fun clear(config: ConnectionConfig, id: String) = synchronized(lock) {
        val old = envelope(config)
        write(config, CartEnvelope(draft = old.draft?.takeUnless { it.id == id }, closedId = id,
            closedIds = (old.closedIds + listOfNotNull(old.closedId) + id).distinct().takeLast(64)))
    }
    companion object { private val lock = Any() }
}
