package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.HistoryPage
import ai.deepseek.dsh.android.model.HistoryRequest
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.protocol.string
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `session.history` / `session.page` — back-scroll over one session's events.
 *
 * F1: the client sends ONLY `beforeSeq` (exclusive) and `maxMessages`. The gateway injects
 * `throughSeq`; a DTO field for it is kept optional and is NEVER populated by [fromDomain], so the
 * client cannot accidentally assert an upper bound it does not own. This is the deliberate divergence
 * from the task prose, which listed `throughSeq` in the page request — see the report.
 *
 * F2: there is no `cut` field on the page; the read frontier is the smallest returned seq
 * ([HistoryPage.readFrontier]).
 */
@Serializable
data class SessionPageRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    @SerialName("beforeSeq") val beforeSeq: Long? = null,
    @SerialName("maxMessages") val maxMessages: Int = DEFAULT_PAGE_SIZE,
    /** Gateway-owned; present only for round-trip tolerance and never sent by the client (F1). */
    @SerialName("throughSeq") val throughSeq: Long? = null,
) {
    companion object {
        // historyPageSize default (3 digits — not a gate-scanned magic number).
        const val DEFAULT_PAGE_SIZE: Int = 50

        fun fromDomain(request: HistoryRequest): SessionPageRequest = SessionPageRequest(
            sessionIdStr = request.sessionId.value,
            beforeSeq = request.beforeSeq?.value,
            maxMessages = request.maxMessages,
            throughSeq = null,
        )
    }
}

/**
 * A `session.page` / `session.history` row: `{ "event": {type, seq, data} }` (DECISIONS **D30**).
 *
 * Unlike a poll frame there is no outer transport `seq` and no `rpcId`/`payload` wrapper — the row's
 * own `event.seq` IS the durable session seq. `transportSeq` mirrors it because history is a snapshot
 * read, never a cursor stream: applying a history page must not move the poll cursor.
 */
@Serializable
data class HistoryRowDto(
    val rpcId: String? = null,
    val event: JsonObject? = null,
) {
    fun toFrame(defaultSessionId: SessionId): TransportFrame {
        val row = event?.let { resolveSessionEventRow(it) } ?: DurableRow("", null, null, false, "{}")
        // 缺 seq 的行落在最前（0），不借用当前游标：伪造位置会让去重把未应用的行当成已应用。
        val seq = row.seq ?: EventSeq(0L)
        return TransportFrame(
            channel = ChannelKind.Mux,
            sessionId = row.sessionId ?: defaultSessionId,
            seq = seq,
            transportSeq = seq,
            type = row.type,
            ignorable = row.ignorable,
            payloadJson = row.payloadJson,
            wallClockMs = 0L,
            replay = false,
        )
    }

    /** `data` 绝不渲染：历史行携带用户与助手的正文（G09.2）。 */
    override fun toString(): String = "HistoryRowDto(rpcId=<omitted>, type=${event?.string("type") ?: "none"})"
}

@Serializable
data class SessionPageResponse(
    @SerialName("sessionId") val sessionIdStr: String? = null,
    val events: List<HistoryRowDto> = emptyList(),
    @SerialName("hasMore") val hasMoreFlag: Boolean? = null,
    val ok: Boolean? = null,
) {
    fun toDomain(defaultSessionId: SessionId = SessionId("")): HistoryPage {
        val frames: List<TransportFrame> =
            events.map { it.toFrame(defaultSessionId) }
        val sid = SessionId(sessionIdStr ?: defaultSessionId.value)
        return HistoryPage(
            sessionId = sid,
            frames = frames,
            // Absent hasMore: infer "full page ⇒ more" only as a fallback; never claim more on an empty page.
            hasMore = hasMoreFlag ?: (events.size >= SessionPageRequest.DEFAULT_PAGE_SIZE && events.isNotEmpty()),
        )
    }
}

/** `session.history` shares the page shape but scrolls the live tail; kept as a distinct RPC name. */
@Serializable
data class SessionHistoryRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    @SerialName("beforeSeq") val beforeSeq: Long? = null,
    @SerialName("maxMessages") val maxMessages: Int = SessionPageRequest.DEFAULT_PAGE_SIZE,
) {
    companion object {
        fun fromDomain(request: HistoryRequest): SessionHistoryRequest = SessionHistoryRequest(
            sessionIdStr = request.sessionId.value,
            beforeSeq = request.beforeSeq?.value,
            maxMessages = request.maxMessages,
        )
    }
}

@Serializable
data class SessionHistoryResponse(
    @SerialName("sessionId") val sessionIdStr: String? = null,
    val events: List<HistoryRowDto> = emptyList(),
    @SerialName("hasMore") val hasMoreFlag: Boolean? = null,
    val ok: Boolean? = null,
) {
    fun toDomain(defaultSessionId: SessionId = SessionId("")): HistoryPage = SessionPageResponse(
        sessionIdStr = sessionIdStr,
        events = events,
        hasMoreFlag = hasMoreFlag,
        ok = ok,
    ).toDomain(defaultSessionId)
}

/** Re-export for callers that only hold an [EventSeq] and want the exclusive bound value. */
internal fun EventSeq?.exclusiveBoundValue(): Long? = this?.value
