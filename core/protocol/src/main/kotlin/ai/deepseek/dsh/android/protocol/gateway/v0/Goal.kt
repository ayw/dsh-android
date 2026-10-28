package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `goal.*` — set / clear / describe the session goal. The authoritative durable projection is the
 * `goal/change` event; these RPCs are optimistic writes and the describe query only.
 */
@Serializable
data class GoalSetRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    val goal: String,
)

@Serializable
data class GoalClearRequest(
    @SerialName("sessionId") val sessionIdStr: String,
)

@Serializable
data class GoalDto(
    val kind: String? = null,
    val objective: String? = null,
    val phase: String? = null,
    val rounds: Long? = null,
)

@Serializable
data class GoalDescribeResponse(
    val goal: GoalDto? = null,
    val ok: Boolean? = null,
)

@Serializable
data class GoalMutationResponse(val ok: Boolean? = null) {
    fun toDomain(): WriteResult = writeResultOf(accepted = ok)
}
