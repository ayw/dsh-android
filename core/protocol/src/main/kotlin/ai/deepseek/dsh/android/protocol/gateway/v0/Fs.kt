package ai.deepseek.dsh.android.protocol.gateway.v0

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `/fs/list`, `/fs/preview`, `/fs/file`. No `:core:model` target — these are read queries consumed by the
 * files UI. `/fs/list` has NO server pagination (confirmed original constraint); the soft cap
 * `fsListSoftCap` and local paging are enforced upstream in `:core:data`, not here.
 */
@Serializable
data class FsListRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    val path: String = "",
)

@Serializable
data class FsEntryDto(
    val name: String,
    val path: String,
    val directory: Boolean = false,
    val bytes: Long? = null,
    @SerialName("modifiedAt") val modifiedAtMs: Long? = null,
)

@Serializable
data class FsListResponse(
    val entries: List<FsEntryDto> = emptyList(),
    val ok: Boolean? = null,
)

/** `/fs/preview`: up to 1 MiB (F14). `preview-too-large` (413) is handled by `:core:network`, not here. */
@Serializable
data class FsPreviewRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    val path: String,
)

@Serializable
data class FsPreviewResponse(
    val path: String? = null,
    val text: String? = null,
    @SerialName("truncated") val truncated: Boolean? = null,
    val ok: Boolean? = null,
)

/** `/fs/file`: full bytes for a path above the preview cap. */
@Serializable
data class FsFileRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    val path: String,
)

@Serializable
data class FsFileResponse(
    val path: String? = null,
    val mediaType: String? = null,
    @SerialName("base64") val base64: String? = null,
    val bytes: Long? = null,
    val ok: Boolean? = null,
)
