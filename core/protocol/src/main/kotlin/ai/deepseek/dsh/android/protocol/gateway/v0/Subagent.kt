package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `subagent.list` / `subagent.interrupt`. */
@Serializable
data class SubagentDto(
    val id: String,
    @SerialName("label") val label: String? = null,
    val state: String? = null,
    @SerialName("parentCallId") val parentCallId: String? = null,
)

@Serializable
data class SubagentListResponse(
    val subagents: List<SubagentDto> = emptyList(),
    val ok: Boolean? = null,
)

@Serializable
data class SubagentInterruptRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    val id: String,
)

@Serializable
data class SubagentInterruptResponse(val ok: Boolean? = null) {
    fun toDomain(): WriteResult = writeResultOf(accepted = ok)
}
