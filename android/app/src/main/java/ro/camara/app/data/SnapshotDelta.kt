package ro.camara.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
private data class SnapshotDelta(@SerialName("base_etag") val baseTag: String, val changes: JsonObject)

/** Applies a delta to the confirmed snapshot, never to the projection of local edits. */
internal fun applySnapshotDelta(json: Json, body: String, tag: String, base: Snapshot): Snapshot {
    val delta = json.decodeFromString<SnapshotDelta>(body)
    require(delta.baseTag == tag && base.epoch.isNotBlank()) { "Revizia locală nu corespunde." }
    val original = json.encodeToJsonElement(base) as JsonObject
    val result = json.decodeFromJsonElement<Snapshot>(JsonObject(original + delta.changes))
    require(result.epoch == base.epoch) { "Baza de date s-a schimbat." }
    return result
}
