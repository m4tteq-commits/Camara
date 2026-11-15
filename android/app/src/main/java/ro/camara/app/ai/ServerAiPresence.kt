package ro.camara.app.ai

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ro.camara.app.data.CamaraApi
import ro.camara.app.data.ConnectionConfig
import ro.camara.app.data.ConnectionStore

/** Only the visible main activity owns a lease; background sync never starts the server model. */
class ServerAiPresence(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val sessionId = UUID.randomUUID().toString()
    private val visibleOwners = mutableSetOf<Any>()
    @Volatile private var foreground = false

    init {
        scope.launch {
            var leased: ConnectionConfig? = null
            while (isActive) {
                val desired = if (foreground && AiPreferences(app).mode == "server") runCatching { ConnectionStore(app).read() }.getOrNull() else null
                // Serialize release and heartbeat so a late request cannot revive a closed session.
                if (leased != null && leased != desired) quietly { CamaraApi(leased!!, fast = true).releaseAiSession(sessionId) }
                leased = desired
                if (desired != null) quietly { CamaraApi(desired, fast = true).touchAiSession(sessionId) }
                if (desired == null) signals.receive() else withTimeoutOrNull(25_000) { signals.receive() }
            }
        }
    }

    private suspend fun quietly(action: suspend () -> Unit) {
        try { action() } catch (e: CancellationException) { throw e } catch (_: Exception) {
            // A lost connection expires on the server; ordinary app operations stay usable.
        }
    }

    @Synchronized
    fun setVisible(owner: Any, value: Boolean) {
        if (value) visibleOwners.add(owner) else visibleOwners.remove(owner)
        val visible = visibleOwners.isNotEmpty()
        if (foreground == visible) return
        foreground = visible
        settingsChanged()
    }
    fun settingsChanged() { signals.trySend(Unit) }
}
