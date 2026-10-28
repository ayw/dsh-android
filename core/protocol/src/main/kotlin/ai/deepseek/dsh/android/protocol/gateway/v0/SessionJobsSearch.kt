package ai.deepseek.dsh.android.protocol.gateway.v0

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `session/jobs` (background jobs for a session) and `session.search` (full-text across sessions).
 * These have no `:core:model` counterpart yet, so they remain raw query DTOs for the UI/feature layer.
 */
@Serializable
data class SessionJobsRequest(
    @SerialName("sessionId") val sessionIdStr: String,
)

@Serializable
data class JobDto(
    val id: String,
    val status: String? = null,
    val label: String? = null,
    @SerialName("startedAt") val startedAtMs: Long? = null,
    @SerialName("endedAt") val endedAtMs: Long? = null,
)

@Serializable
data class SessionJobsResponse(
    val jobs: List<JobDto> = emptyList(),
    val ok: Boolean? = null,
)

@Serializable
data class SessionSearchRequest(
    val query: String,
    @SerialName("maxResults") val maxResults: Int = 20,
)

@Serializable
data class SessionSearchHitDto(
    @SerialName("sessionId") val sessionIdStr: String,
    @SerialName("seq") val seq: Long? = null,
    @SerialName("snippet") val snippet: String? = null,
)

@Serializable
data class SessionSearchResponse(
    val hits: List<SessionSearchHitDto> = emptyList(),
    val ok: Boolean? = null,
)
