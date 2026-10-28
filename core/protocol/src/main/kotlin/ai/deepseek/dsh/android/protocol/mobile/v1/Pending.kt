package ai.deepseek.dsh.android.protocol.mobile.v1

import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `/mobile/v1/pending` and `POST .../pending/{id}/resolve` (ACCEPTANCE §13).
 *
 * v1 has a real pending read endpoint (v0 does not, D13), so its completeness is
 * [PendingCompleteness.CompleteByServer] — the enum value reserved for exactly this transition. Identity
 * follows the same rules as v0: approval `requestId` is the approval id, question `requestId` is the
 * `ask_user_question` `callId` (F8/F9).
 */
@Serializable
data class MobilePendingItemDto(
    val requestId: String,
    /** `"approval"` or `"question"`; anything else is classified unknown and ignored (D08). */
    val kind: String,
    @SerialName("sessionId") val sessionIdStr: String,
    val status: String? = null,
    @SerialName("createdAt") val createdAtMs: Long? = null,
    @SerialName("firstSeenAt") val firstSeenAtMs: Long? = null,
    @SerialName("anchorSeq") val anchorSeq: Long? = null,
    val callId: String? = null,
    val toolName: String? = null,
    val payload: kotlinx.serialization.json.JsonElement? = null,
) {
    fun toPendingRequest(serverId: ServerId): PendingRequest? {
        val resolvedKind = when (kind.lowercase()) {
            "approval" -> PendingKind.Approval
            "question" -> PendingKind.Question
            else -> return null // unrepresentable → caller classifies as ignorable
        }
        return PendingRequest(
            serverId = serverId,
            kind = resolvedKind,
            requestId = RequestId(requestId),
            sessionId = SessionId(sessionIdStr),
            status = statusOrNull() ?: PendingStatus.Pending,
            completeness = PendingCompleteness.CompleteByServer,
            payloadJson = payload?.toString() ?: "{}",
            firstSeenAtMs = firstSeenAtMs ?: createdAtMs ?: 0L,
            createdAtMs = createdAtMs ?: 0L,
            anchorSeq = anchorSeq?.let { EventSeq(it) },
            callId = callId?.let { CallId(it) },
            toolName = toolName,
        )
    }

    private fun statusOrNull(): PendingStatus? = when (status?.lowercase()) {
        "pending" -> PendingStatus.Pending
        "answering" -> PendingStatus.Answering
        "resolved" -> PendingStatus.Resolved
        "stale" -> PendingStatus.Stale
        "unknown" -> PendingStatus.Unknown
        else -> null
    }
}

@Serializable
data class MobilePendingResponse(
    val items: List<MobilePendingItemDto> = emptyList(),
    val completeness: String? = null,
    val cursor: String? = null,
    val ok: Boolean? = null,
) {
    fun toDomain(serverId: ServerId): List<PendingRequest> =
        items.mapNotNull { it.toPendingRequest(serverId) }
}

/** `POST .../pending/{id}/resolve` with `Idempotency-Key`. */
@Serializable
data class MobileResolveRequest(
    val requestId: String,
    @SerialName("idempotencyKey") val idempotencyKey: String,
    /** Approval outcome or question answer batch, forwarded verbatim (D06). */
    val value: String,
)

@Serializable
data class MobileResolveResponse(
    val ok: Boolean? = null,
    val accepted: Boolean? = null,
    /** v1 keeps the 409 three-way split the v0 path flattened away (F5); stable machine code. */
    val code: String? = null,
) {
    fun toDomain(): WriteResult = when {
        accepted == true || (ok == true && code == null) ->
            WriteResult(accepted = true, receiptId = null, outcome = WriteResult.WriteOutcome.Accepted)
        code.equals(MobileErrorEnvelope.CODE_FORBIDDEN, ignoreCase = true) ->
            WriteResult(accepted = false, receiptId = null, outcome = WriteResult.WriteOutcome.Forbidden)
        accepted == false || code.equals(MobileErrorEnvelope.CODE_STALE, ignoreCase = true) ->
            WriteResult(accepted = false, receiptId = null, outcome = WriteResult.WriteOutcome.Rejected)
        else -> WriteResult(accepted = null, receiptId = null, outcome = WriteResult.WriteOutcome.Unknown)
    }
}
