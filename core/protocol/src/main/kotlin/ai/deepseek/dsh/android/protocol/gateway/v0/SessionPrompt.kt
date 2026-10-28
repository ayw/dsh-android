package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.PromptAttachment
import ai.deepseek.dsh.android.model.PromptRequest
import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The gateway's `session.prompt` body is `{sessionId, mode, content}` where `content` is an ordered
 * array of blocks — images first, then the text block (see the web client's `sendSessionContent`).
 * A flat `{text, attachments}` body is accepted by older harness revisions but not by the current
 * gateway, so the content array is the only shape built here.
 */
@Serializable
data class PromptContentDto(
    val type: String,
    val text: String? = null,
    @SerialName("mediaType") val mediaType: String? = null,
    val data: String? = null,
    val name: String? = null,
) {
    companion object {
        const val TYPE_TEXT: String = "text"
        const val TYPE_IMAGE: String = "image"

        fun text(value: String): PromptContentDto = PromptContentDto(type = TYPE_TEXT, text = value)

        fun image(attachment: PromptAttachment): PromptContentDto = PromptContentDto(
            type = TYPE_IMAGE,
            mediaType = attachment.mediaType,
            data = attachment.base64,
            name = attachment.fileName.ifBlank { null },
        )
    }
}

/**
 * `session.prompt` with `{mode:"queue"}` (F15: prefer the `/api/operations` receipt path when the
 * capability allows, so the UNKNOWN window shrinks). Attachments are base64 inline; the image-prompt
 * transport capability gates whether the UI offers them at all (G08).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SessionPromptRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    // `mode` is meaningful to the gateway even when it equals "queue" (the default is not a no-op).
    @EncodeDefault val mode: String = MODE_QUEUE,
    @EncodeDefault val content: List<PromptContentDto> = emptyList(),
) {
    companion object {
        const val MODE_QUEUE: String = "queue"

        fun fromDomain(request: PromptRequest): SessionPromptRequest {
            val content = buildList {
                request.attachments.forEach { add(PromptContentDto.image(it)) }
                if (request.text.isNotEmpty()) add(PromptContentDto.text(request.text))
            }
            return SessionPromptRequest(
                sessionIdStr = request.sessionId.value,
                mode = MODE_QUEUE,
                content = content,
            )
        }
    }
}

/** `session.updateQueue` — reorder/edit the still-unclaimed queued prompt for a session. */
@Serializable
data class SessionUpdateQueueRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    @SerialName("queueId") val queueId: String,
    val text: String,
)

/** `session.cancel` — abort the live turn. */
@Serializable
data class SessionCancelRequest(
    @SerialName("sessionId") val sessionIdStr: String,
)

@Serializable
data class SessionPromptResponse(
    val ok: Boolean? = null,
    @SerialName("accepted") val accepted: Boolean? = null,
    @SerialName("queueId") val queueId: String? = null,
) {
    fun toDomain(): WriteResult = writeResultOf(accepted = accepted ?: ok, receiptId = queueId)
}
