package ai.deepseek.dsh.android.protocol.mobile.v1

import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.HistoryPage
import ai.deepseek.dsh.android.model.PromptRequest
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.WriteResult
import ai.deepseek.dsh.android.protocol.gateway.v0.EventFrameDto
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionSummaryDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `/mobile/v1/sessions`, `.../sessions/{id}/timeline`, and `POST .../sessions/{id}/messages`
 * (ACCEPTANCE §13).
 *
 * The timeline endpoint deliberately returns the SAME `{seq,type,payload,replay?}` frame shape as the
 * gateway, so a single [ai.deepseek.dsh.android.protocol.DurableEventCodec] decodes both backends and the
 * G15 v0/v1 parity snapshot is byte-comparable. v1 layers a server-side [cursor] on top (v0 scrolls with
 * client-side `beforeSeq`).
 */
@Serializable
data class MobileSessionsResponse(
    val sessions: List<SessionSummaryDto> = emptyList(),
    val cursor: String? = null,
    val ok: Boolean? = null,
) {
    fun toRoster(ranksBySession: Map<String, Long>): List<RosterEntry> =
        sessions.map { it.toRosterEntry(ranksBySession[it.id] ?: 0L) }
}

@Serializable
data class MobileTimelineRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    val cursor: String? = null,
    @SerialName("maxMessages") val maxMessages: Int = 50,
)

@Serializable
data class MobileTimelineResponse(
    @SerialName("sessionId") val sessionIdStr: String? = null,
    val events: List<EventFrameDto> = emptyList(),
    val cursor: String? = null,
    val hasMore: Boolean? = null,
    @SerialName("projectionVersion") val projectionVersion: String? = null,
    val ok: Boolean? = null,
) {
    fun toDomain(defaultSessionId: SessionId = SessionId("")): HistoryPage {
        val sid = SessionId(sessionIdStr ?: defaultSessionId.value)
        return HistoryPage(
            sessionId = sid,
            frames = events.map { it.toFrame(ChannelKind.Mux, sid) },
            hasMore = hasMore ?: events.isNotEmpty(),
        )
    }
}

/** `POST .../sessions/{id}/messages` with an `Idempotency-Key` (D13 / §13): writes are idempotent at v1. */
@Serializable
data class MobileMessagesRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    val text: String,
    val mode: String = "queue",
    @SerialName("idempotencyKey") val idempotencyKey: String,
    val attachments: List<MobileAttachmentDto> = emptyList(),
) {
    companion object {
        fun fromDomain(request: PromptRequest, sessionId: String, idempotencyKey: String): MobileMessagesRequest =
            MobileMessagesRequest(
                sessionIdStr = sessionId,
                text = request.text,
                mode = "queue",
                idempotencyKey = idempotencyKey,
                attachments = request.attachments.map { MobileAttachmentDto(it.fileName, it.mediaType, it.base64) },
            )
    }
}

@Serializable
data class MobileAttachmentDto(
    @SerialName("fileName") val fileName: String,
    @SerialName("mediaType") val mediaType: String,
    @SerialName("base64") val base64: String,
)

@Serializable
data class MobileMessagesResponse(
    val ok: Boolean? = null,
    val accepted: Boolean? = null,
    @SerialName("messageId") val messageId: String? = null,
    @SerialName("idempotencyKey") val idempotencyKey: String? = null,
) {
    fun toDomain(): WriteResult = if (accepted == true || ok == true) {
        WriteResult(accepted = true, receiptId = messageId, outcome = WriteResult.WriteOutcome.Accepted)
    } else if (accepted == false) {
        WriteResult(accepted = false, receiptId = messageId, outcome = WriteResult.WriteOutcome.Rejected)
    } else {
        WriteResult(accepted = null, receiptId = messageId, outcome = WriteResult.WriteOutcome.Unknown)
    }
}
