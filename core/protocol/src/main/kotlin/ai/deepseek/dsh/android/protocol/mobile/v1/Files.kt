package ai.deepseek.dsh.android.protocol.mobile.v1

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `/mobile/v1/files` (ACCEPTANCE §13). The BFF adds a server-side [cursor] that v0's `/fs/list` never had
 * (F14 / confirmed `/fs/list` has no pagination), so paging here is server-driven rather than the
 * client-side soft-cap paging `:core:data` does for v0. No `:core:model` target; consumed by the files UI.
 */
@Serializable
data class MobileFilesRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    val path: String = "",
    val cursor: String? = null,
    @SerialName("maxEntries") val maxEntries: Int = 50,
)

@Serializable
data class MobileFileEntryDto(
    val name: String,
    val path: String,
    val directory: Boolean = false,
    val bytes: Long? = null,
    @SerialName("modifiedAt") val modifiedAtMs: Long? = null,
)

@Serializable
data class MobileFilesResponse(
    val entries: List<MobileFileEntryDto> = emptyList(),
    val cursor: String? = null,
    val ok: Boolean? = null,
)
