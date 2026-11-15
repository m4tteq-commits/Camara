package ro.camara.app.data

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SyncEngine {
    private val mutex = Mutex()
    private data class CompletedSync(val config: ConnectionConfig, val network: Long, val time: Long)
    private var completed: CompletedSync? = null

    suspend fun changeConnection(context: Context, candidate: ConnectionConfig): Pair<ConnectionConfig, LocalState> = mutex.withLock {
        val connections = ConnectionStore(context)
        val offline = OfflineStore(context)
        val current = connections.read()
        val previous = current?.let(offline::read)
        val snapshot = CamaraApi(candidate).snapshot()
        val sameConnection = current != null && current.token == candidate.token && current.baseUrl == candidate.baseUrl
        val sameDatabase = current?.token == candidate.token && snapshot.epoch.isNotBlank() && previous?.server?.epoch == snapshot.epoch
        require(previous?.pending.isNullOrEmpty() || sameConnection || sameDatabase) {
            "Sincronizează sau rezolvă acțiunile în așteptare înainte de a schimba serverul ori cheia."
        }
        val config = if (current != null && (sameConnection || sameDatabase)) candidate.copy(storageUrl = current.storageUrl) else candidate
        val local = offline.server(config, snapshot)
        connections.save(config)
        config to local
    }

    suspend fun restore(context: Context, config: ConnectionConfig, id: String, request: RestoreRequest): LocalState = mutex.withLock {
        val store = OfflineStore(context)
        check(store.read(config).pending.isEmpty()) { "Sincronizează sau rezolvă toate acțiunile înainte de restaurare." }
        val api = CamaraApi(config)
        api.restore(id, request)
        completed = null
        store.server(config, api.snapshot())
    }

    suspend fun resolve(context: Context, config: ConnectionConfig, id: String, keepMine: Boolean): LocalState = mutex.withLock {
        OfflineStore(context).resolve(config, id, keepMine)
    }

    suspend fun discardStale(context: Context, config: ConnectionConfig): LocalState = mutex.withLock {
        OfflineStore(context).discardStale(config)
    }

    suspend fun sync(context: Context, config: ConnectionConfig, reuseRecent: Boolean = false): LocalState = mutex.withLock {
        syncLocked(context, config, reuseRecent)
    }

    internal suspend fun syncSavedConnection(context: Context) = mutex.withLock {
        val config = ConnectionStore(context).read() ?: return@withLock
        syncLocked(context, config, reuseRecent = true)
    }

    private suspend fun syncLocked(context: Context, config: ConnectionConfig, reuseRecent: Boolean): LocalState {
        val store = OfflineStore(context)
        val network = CamaraApi.routeGeneration
        val recent = completed
        if (reuseRecent && recent?.config == config && recent.network == network &&
            SystemClock.elapsedRealtime() - recent.time < 2_000) {
            val local = store.read(config)
            if (local.pending.isEmpty()) return local
        }
        completed = null
        return synchronize(store, config).also { completed = CompletedSync(config, network, SystemClock.elapsedRealtime()) }
    }

    private suspend fun synchronize(store: OfflineStore, config: ConnectionConfig): LocalState {
        val api = CamaraApi(config, fast = true)
        var local = store.server(config, api.snapshot())
        var needsRefresh = false
        while (true) {
            val action = local.pending.firstOrNull() ?: break
            if (action.issue != null) break
            if (staleEpoch(action, local.server)) {
                return store.issue(config, action.id, "Serverul a fost restaurat. Verifică schimbările vechi.")
            }
            val result = try { api.applyAction(action.id, action.mutation) }
            catch (e: ApiException) {
                if (e.code == 409 || e.code == 422) {
                    return store.issue(config, action.id, e.message ?: "Verifică acțiunea înainte de sincronizare.", api.snapshot())
                }
                throw e
            }
            if (result.replayed) {
                // The confirmed write might have been followed by another device's edit.
                local = store.acknowledge(config, action.id, api.snapshot())
                needsRefresh = false
            } else {
                local = store.acknowledge(config, action.id)
                needsRefresh = true
            }
        }
        return if (needsRefresh) store.server(config, api.snapshot()) else store.read(config)
    }
    fun schedule(context: Context, now: Boolean = false) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork("camara-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
        if (now) manager.enqueueUniqueWork("camara-sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try { SyncEngine.syncSavedConnection(applicationContext); Result.success() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
