package ro.camara.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SnapshotDeltaTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val base = Snapshot(
        pantry = listOf(PantryItem("a", "Ouă", 3.0)),
        shopping = listOf(ShoppingItem("b", "Lapte")),
        epoch = "epoch",
    )

    @Test fun replacesEmptyCollectionsWhilePreservingUnchangedData() {
        val result = applySnapshotDelta(json, """{"base_etag":"base","changes":{"pantry":[]}}""", "base", base)
        assertTrue(result.pantry.isEmpty())
        assertEquals(base.shopping, result.shopping)
        assertEquals(base.epoch, result.epoch)
    }

    @Test fun rejectsDeltaWithAStaleBaseOrNewDatabaseEpoch() {
        assertThrows(IllegalArgumentException::class.java) {
            applySnapshotDelta(json, """{"base_etag":"stale","changes":{"pantry":[]}}""", "base", base)
        }
        assertThrows(IllegalArgumentException::class.java) {
            applySnapshotDelta(json, """{"base_etag":"base","changes":{"epoch":"new"}}""", "base", base)
        }
    }
}
