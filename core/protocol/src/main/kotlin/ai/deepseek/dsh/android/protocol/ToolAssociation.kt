package ai.deepseek.dsh.android.protocol

import ai.deepseek.dsh.android.model.CallId
import kotlinx.serialization.json.JsonObject

/**
 * THE single tool-association helper. Every mapper and the durable-event codec resolve a tool
 * call's identity through this — it is never re-derived per DTO.
 *
 * Resolution order (F13, the authoritative correction of the older `data.callId ?? data.message
 * ?.source?.callId` sketch):
 *   1. `callId` — present only on `tool/call` (and on `approval/asked`), the top-level id.
 *   2. `message.toolCallId` — the provider-issued id the `tool/result` message carries.
 *   3. `message.source.callId` — the tool-role message source.
 *
 * Embedded `tool-call` content blocks use `block.id`, which lives in the *same id space under a
 * different field name*; [resolveFromBlock] covers that key so the "one helper, not duplicated"
 * rule still holds when the caller is looking at a content block rather than an event payload.
 */
object ToolAssociation {

    /** Resolve a call identity from a session event's payload object (`tool/call`, `tool/result`, …). */
    fun resolve(payload: JsonObject): CallId? {
        payload.string("callId")?.let { return CallId(it) }
        val message = payload.obj("message")
        message?.string("toolCallId")?.let { return CallId(it) }
        message?.obj("source")?.string("callId")?.let { return CallId(it) }
        return null
    }

    /** Resolve from a single embedded `tool-call` content block (its identity field is `id`). */
    fun resolveFromBlock(block: JsonObject): CallId? {
        block.string("id")?.let { return CallId(it) }
        return resolve(block)
    }
}
