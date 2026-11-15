package ro.camara.app.data

import java.util.UUID
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class OfflineJournalTest {
    private class Disk : JournalStorage {
        var value = LocalState(server = Snapshot(epoch = "epoch"))
        var reads = 0
        var writes = 0
        var fail = false
        override fun read(): LocalState { reads++; return value }
        override fun write(value: LocalState) {
            if (fail) throw IOException("full")
            writes++
            this.value = value
        }
    }
    private val config = ConnectionConfig("https://example.com", "a".repeat(40))
    private fun store(disk: Disk) = OfflineStore(UUID.randomUUID().toString()) { disk }

    @Test fun pendingActionsUseCompactGuardsOnlyForConfirmedCollections() {
        val disk = Disk()
        val store = store(disk)
        val snapshot = disk.value.server.copy(collection_revisions = mapOf("pantry" to "5", "shopping" to "1"))
        store.server(config, snapshot, "tag")
        fun save(id: String, guards: Set<String>) = store.enqueue(config) { projected ->
            val row = ShoppingItem(id, "Lapte")
            ActionRules.action("Listă", listOf(ActionRules.change(projected, "shopping", id, ActionRules.record(row))),
                rebasable = false, guards = guards.associateWith { ActionRules.records(projected, it) })
        }
        val first = save("one", setOf("pantry")).pending.single()
        assertEquals(mapOf("pantry" to "5"), first.mutation.guard_revisions)
        assertTrue(first.mutation.guards.isEmpty())
        assertEquals(7, UUID.fromString(first.id).version())
        val next = save("two", setOf("shopping")).pending.last()
        assertTrue(next.mutation.guard_revisions.isEmpty())
        assertEquals(setOf("shopping"), next.mutation.guards.keys)
    }

    @Test fun unchangedRefreshDoesNotReadOrWriteDiskAndKeepsPendingActions() {
        val disk = Disk()
        val store = store(disk)
        val pending = store.enqueue(config) {
            val item = ShoppingItem("id", "Lapte")
            ActionRules.action("Cumpărături", listOf(ActionRules.change(it, "shopping", item.id, ActionRules.record(item))))
        }
        repeat(10) { store.server(config, pending.server, "tag") }
        val result = store.read(config)
        assertEquals(1, disk.reads)
        assertEquals(1, disk.writes)
        assertEquals(pending.pending, result.pending)
        assertEquals("Lapte", result.projected().shopping.single().name)
        assertEquals("tag", result.etag)
    }

    @Test fun failedWriteDoesNotPublishMemoryStateAndRestartRetainsOutbox() {
        val disk = Disk()
        val store = store(disk)
        val snapshot = store.read(config).server
        disk.fail = true
        try {
            store.server(config, snapshot.copy(shopping = listOf(ShoppingItem("id", "Ouă"))))
            fail("write must fail")
        } catch (_: IOException) { }
        assertEquals(snapshot, store.read(config).server)
        assertEquals(snapshot, store(disk).read(config).server)
    }
}
