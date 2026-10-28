package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `session.create` — spawn a new session, optionally seeded with a title / workspace. */
@Serializable
data class SessionCreateRequest(
    val title: String? = null,
    @SerialName("workspaceId") val workspaceId: String? = null,
)

@Serializable
data class SessionCreateResponse(
    @SerialName("sessionId") val sessionIdStr: String? = null,
    val ok: Boolean? = null,
) {
    fun toDomain(): WriteResult = writeResultOf(accepted = ok, receiptId = sessionIdStr)
    fun createdSessionId(): SessionId? = sessionIdStr?.let { SessionId(it) }
}

/** `session.rename` — set a session's title. Optimistic write; resolved by the following `session/title` event. */
@Serializable
data class SessionRenameRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    val title: String,
)

/** `session.fork` — branch a new child from a prefix of an existing session. */
@Serializable
data class SessionForkRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    /** Optional exclusive upper bound of the inherited prefix; gateway default when absent. */
    @SerialName("throughSeq") val throughSeq: Long? = null,
)

@Serializable
data class SessionMutationResponse(
    val ok: Boolean? = null,
    @SerialName("sessionId") val sessionIdStr: String? = null,
) {
    fun toDomain(): WriteResult = writeResultOf(accepted = ok, receiptId = sessionIdStr)
}
