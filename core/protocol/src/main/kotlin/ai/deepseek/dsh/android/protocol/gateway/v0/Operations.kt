package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `POST /api/operations?id=<operationKey>` (F15, capability `operations >= 1`): submit a write and get a
 * **202 + persistent receipt**. The receipt narrows the write's UNKNOWN window to "receipt exists but not
 * yet settled" ([WriteResult.WriteOutcome.ReceiptPending]) — D13. The client owns the receipt id: it is
 * the `id` query parameter (so the actor can persist the id before sending). The network layer also sets
 * it as an idempotency header.
 *
 * The gateway 0.8.1 snapshot shape is:
 *
 * ```
 * { "id", "method", "phase": "running|complete|rejected|unknown",
 *   "createdAt", "completedAt",
 *   "result": { "result": { "ok": true|false, "value": {...} | "error": {...} } },
 *   "error"?: "result-unconfirmed", "diagnostic"?: {...} }
 * ```
 *
 * `result` is a full dot-RPC envelope, hence the double nesting. A snapshot that is merely `running`
 * carries no `result` at all.
 */
@Serializable
data class OperationsRequest(
    val method: String,
    val payload: JsonElement? = null,
)

@Serializable
data class OperationsResponse(
    /** Receipt id; the client-chosen `id` query echoed back. */
    val id: String? = null,
    val method: String? = null,
    val phase: String? = null,
    /** Epoch millis in production, a recorder placeholder in the contract files; never parsed. */
    val createdAt: JsonElement? = null,
    val completedAt: JsonElement? = null,
    /** Full rpc envelope: `{result:{ok,value|error}}`; kept raw (D06). */
    val result: JsonElement? = null,
    /** `result-unconfirmed` when the gateway could not settle the write. */
    val error: JsonElement? = null,
    val diagnostic: JsonElement? = null,
    // ---- legacy receipt dialect (kept for tolerant reading) ----
    @SerialName("receiptId") val legacyReceiptId: String? = null,
    val status: String? = null,
    val accepted: Boolean? = null,
    val ok: Boolean? = null,
) {
    fun toDomain(): WriteResult {
        val receipt = id ?: legacyReceiptId
        val state = phase?.lowercase() ?: status?.lowercase()
        val rpcOk = flagAt("result", "ok")
        val rpcAccepted = flagAt("result", "value", "accepted")
        return when (state) {
            // Settled running: the write was accepted but the upstream result is not durable yet.
            "running", "pending" -> writeResultOf(accepted = null, receiptId = receipt)
            "rejected", "failed", "error" -> writeResultOf(accepted = false, receiptId = receipt)
            // The gateway lost certainty; never retry automatically (D33), never claim a verdict.
            "unknown" -> WriteResult(accepted = null, receiptId = receipt, outcome = WriteResult.WriteOutcome.Unknown)
            "complete", "done", "settled", "succeeded" ->
                writeResultOf(accepted = rpcAccepted ?: (rpcOk ?: true), receiptId = receipt)
            else -> writeResultOf(accepted = rpcAccepted ?: rpcOk ?: accepted ?: ok, receiptId = receipt)
        }
    }

    /** Read a boolean at a nested path; absent / non-primitive / JSON null all read as `null`. */
    private fun flagAt(vararg path: String): Boolean? {
        var current: JsonElement? = result
        for (step in path) current = (current as? JsonObject)?.get(step)
        return (current as? JsonPrimitive)?.booleanOrNull
    }
}
