package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `workspace.list` / `workspace.create` / `workspace.archiveSession`. No `:core:model` target for a
 *  workspace itself exists yet, so only the archive mutation maps to a [WriteResult]; the other two
 *  return raw DTOs the network/UI layer consumes. */
@Serializable
data class WorkspaceDto(
    val id: String,
    val name: String = "",
    @SerialName("rootPath") val rootPath: String? = null,
    val archived: Boolean? = null,
)

@Serializable
data class WorkspaceListResponse(
    val workspaces: List<WorkspaceDto> = emptyList(),
    val ok: Boolean? = null,
)

@Serializable
data class WorkspaceCreateRequest(
    val name: String,
    @SerialName("rootPath") val rootPath: String? = null,
)

@Serializable
data class WorkspaceCreateResponse(
    val id: String? = null,
    val ok: Boolean? = null,
) {
    fun toDomain(): WriteResult = writeResultOf(accepted = ok, receiptId = id)
}

@Serializable
data class WorkspaceArchiveSessionRequest(
    @SerialName("sessionId") val sessionIdStr: String,
)

@Serializable
data class WorkspaceArchiveSessionResponse(
    val ok: Boolean? = null,
) {
    fun toDomain(): WriteResult = writeResultOf(accepted = ok)
}
