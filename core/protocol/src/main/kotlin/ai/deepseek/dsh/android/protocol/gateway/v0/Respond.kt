package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.ResolveRequest
import ai.deepseek.dsh.android.model.WriteResult
import ai.deepseek.dsh.android.protocol.DshJson
import ai.deepseek.dsh.android.protocol.string
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `POST /api/respond` — settle a pending approval or question.
 *
 * Gateway 0.8.1 (modern upstream) only accepts the envelope shape the web client uses:
 *
 * ```
 * { "type": "client-response", "rpcId": <waterfall eventId>,
 *   "result": { "ok": true, "value": { "outcome": ... } | { "answer": ... } } }
 * ```
 *
 * - **rpcId** is the gateway waterfall `eventId` carried on the poll frame envelope
 *   ([ai.deepseek.dsh.android.model.TransportFrame.rpcId]); it is NOT the payload's `approvalId`
 *   or the question `callId`. [ResolveRequest.transportKey] carries it; when absent the payload
 *   identity is used as the fallback (the legacy dialect did exactly that).
 * - approval value: `{sessionId?, approvalId, outcome}` where `outcome` is
 *   `allowed-once | rejected | cancelled | unavailable`;
 * - question value: `{sessionId?, answer:{answers:[...]}}` — the caller passes the answer batch and
 *   this DTO wraps it under `answer`.
 *
 * Legacy fields (`approvalId`/`callId`/`outcome`/`value`) are still emitted at the top level so a
 * legacy-flavored upstream ignores the modern envelope instead of rejecting the request.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RespondRequest(
    // The shared Json spec has `encodeDefaults = false`; the modern upstream requires the discriminator
    // to be present even when it equals the default, so it is always encoded.
    @EncodeDefault val type: String = TYPE_CLIENT_RESPONSE,
    val rpcId: String,
    val result: RespondResult,
    /** Compatibility for a legacy upstream behind the gateway; ignored by the modern branch. */
    @SerialName("sessionId") val sessionIdStr: String? = null,
    @SerialName("approvalId") val legacyApprovalId: String? = null,
    @SerialName("callId") val legacyCallId: String? = null,
    val outcome: String? = null,
    val value: JsonElement? = null,
) {
    companion object {
        const val TYPE_CLIENT_RESPONSE: String = "client-response"

        fun fromDomain(request: ResolveRequest, sessionId: String? = null): RespondRequest {
            val rpcId = request.transportKey?.takeIf { it.isNotBlank() } ?: request.requestId.value
            val value = when (request.kind) {
                PendingKind.Approval -> RespondValue.approval(
                    sessionId = sessionId,
                    approvalId = request.requestId.value,
                    outcome = outcomeOf(request.valueJson),
                )
                PendingKind.Question -> RespondValue.question(
                    sessionId = sessionId,
                    answer = answerOf(request.valueJson),
                )
            }
            return RespondRequest(
                rpcId = rpcId,
                result = RespondResult(ok = true, value = value),
                sessionIdStr = sessionId,
                legacyApprovalId = request.requestId.value.takeIf { request.kind == PendingKind.Approval },
                legacyCallId = request.requestId.value.takeIf { request.kind == PendingKind.Question },
                outcome = (value["outcome"] as? JsonPrimitive)?.content,
                value = value["answer"] ?: value["outcome"],
            )
        }

        /** `valueJson` may be a bare string (`"allowed-once"`), a quoted string, or `{outcome:...}`. */
        private fun outcomeOf(valueJson: String): String {
            val parsed = runCatching { DshJson.spec.parseToJsonElement(valueJson) }.getOrNull()
            return when (parsed) {
                is JsonPrimitive -> parsed.content
                is JsonObject -> parsed.string("outcome") ?: valueJson
                else -> valueJson
            }
        }

        private fun answerOf(valueJson: String): JsonElement =
            runCatching { DshJson.spec.parseToJsonElement(valueJson) }.getOrNull() ?: JsonPrimitive(valueJson)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RespondResult(
    @EncodeDefault val ok: Boolean = true,
    val value: JsonObject? = null,
)

/** Builders for the two `result.value` shapes; keys with a `null` sessionId are omitted. */
private object RespondValue {
    fun approval(sessionId: String?, approvalId: String, outcome: String): JsonObject = buildMap {
        if (!sessionId.isNullOrBlank()) put("sessionId", JsonPrimitive(sessionId))
        put("approvalId", JsonPrimitive(approvalId))
        put("outcome", JsonPrimitive(outcome))
    }.let(::JsonObject)

    fun question(sessionId: String?, answer: JsonElement): JsonObject = buildMap {
        if (!sessionId.isNullOrBlank()) put("sessionId", JsonPrimitive(sessionId))
        put("answer", answer)
    }.let(::JsonObject)
}

@Serializable
data class RespondResponse(
    val ok: Boolean? = null,
    /** F5: v0 collapses the 409 three-way split to a single `accepted` boolean. */
    val accepted: Boolean? = null,
) {
    fun toDomain(): WriteResult = writeResultOf(accepted = accepted ?: ok)
}
