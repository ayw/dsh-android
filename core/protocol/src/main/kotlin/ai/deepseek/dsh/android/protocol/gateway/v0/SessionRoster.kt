package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.protocol.string
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `session.list` — the full visible-session roster, the sole add/remove authority (D11).
 * The gateway ignores any cursor the client sends (confirmed original constraint), so the request
 * carries only the archived filter. [RosterEntry] is NOT produced here: `rosterRank` is a client-side
 * monotonic sequence assigned during persistence, so each summary is mapped with the caller-supplied rank.
 *
 * Two wire dialects exist in the wild:
 *  - modern (`result.value.items[]`): `sessionId`, ISO-8601 `updatedAt`, title at
 *    `projections.values.title`, subagent rows tagged `origin: "subagent"`;
 *  - legacy (`sessions[]`): flat `id`/`title`/numeric `updatedAt`.
 * Both decode into this one DTO; [visibleRows] drops subagent rows either way.
 */
@Serializable
data class SessionListRequest(
    @SerialName("includeArchived") val includeArchived: Boolean? = null,
) {
    companion object {
        val DEFAULT = SessionListRequest(includeArchived = true)
    }
}

/** Modern roster projection wrapper; `values` is where `title` lives. */
@Serializable
data class SessionProjectionsDto(
    @SerialName("asOfSeq") val asOfSeq: Long? = null,
    val kind: String? = null,
    val values: JsonObject? = null,
)

@Serializable
data class SessionSummaryDto(
    /** Legacy roster key. */
    val id: String = "",
    /** Modern roster key; wins over [id] when present. */
    @SerialName("sessionId") val sessionId: String? = null,
    /** Legacy flat title; modern carries it under [projections]. */
    val title: String = "",
    /** Numeric (legacy) or ISO-8601 string (modern); read tolerantly, never throws. */
    @SerialName("updatedAt") val updatedAt: JsonElement? = null,
    val running: Boolean? = null,
    val archived: Boolean? = null,
    @SerialName("workspaceId") val workspaceId: String? = null,
    val cwd: String? = null,
    val origin: String? = null,
    @SerialName("parentSessionId") val parentSessionId: String? = null,
    val projections: SessionProjectionsDto? = null,
) {
    /** The durable session key, whichever dialect supplied it. */
    val key: String get() = sessionId?.takeIf { it.isNotBlank() } ?: id

    /** Subagent logs are internal fragments, never a user-visible roster row. */
    val isSubagent: Boolean get() = origin == "subagent" || parentSessionId != null

    val displayTitle: String get() = title.ifBlank { projections?.values?.string("title").orEmpty() }

    fun toRosterEntry(rosterRank: Long): RosterEntry = RosterEntry(
        sessionId = SessionId(key),
        title = displayTitle,
        updatedAtMs = parseEpochMs(updatedAt) ?: 0L,
        running = running ?: false,
        archived = archived ?: false,
        workspaceId = workspaceId,
        rosterRank = rosterRank,
    )
}

@Serializable
data class SessionListResponse(
    val sessions: List<SessionSummaryDto> = emptyList(),
    /** Modern dialect. */
    val items: List<SessionSummaryDto> = emptyList(),
    val ok: Boolean? = null,
) {
    /** The dialect that actually carried rows; modern wins when both are present. */
    fun visibleRows(): List<SessionSummaryDto> =
        (if (items.isNotEmpty()) items else sessions).filterNot { it.isSubagent }

    /** Ranks are assigned by the caller (network/roster sync); this mapper does not invent order. */
    fun toRoster(ranksBySession: Map<String, Long>): List<RosterEntry> =
        visibleRows().filter { it.key.isNotBlank() }.map { it.toRosterEntry(ranksBySession[it.key] ?: 0L) }
}

/**
 * Epoch millis from a number, an ISO-8601 instant (`Z` or offset), or a recorder placeholder.
 * A placeholder degrades to `null` (sorts last) instead of throwing (D08/F16).
 */
private fun parseEpochMs(element: JsonElement?): Long? {
    val prim = element as? JsonPrimitive ?: return null
    prim.content.toLongOrNull()?.let { return it }
    val text = prim.content
    if (text.isBlank() || text.startsWith("{{")) return null
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        .recoverCatching { Instant.parse(text).toEpochMilli() }
        .getOrNull()
}
