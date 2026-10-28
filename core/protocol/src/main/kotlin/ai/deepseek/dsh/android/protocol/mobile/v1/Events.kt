package ai.deepseek.dsh.android.protocol.mobile.v1

import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.protocol.gateway.v0.BaselineEntryDto
import ai.deepseek.dsh.android.protocol.gateway.v0.EventFrameDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `/mobile/v1/events` (ACCEPTANCE §13). The body **deliberately reuses the gateway v0 frame envelope
 * field names** (`epoch`/`since`/`latestSeq`/`truncated`/`reset`/`baseline`/`events`) so v0 and v1 decode
 * through the identical, D29-correct path and G15 parity is provable by construction. In particular an
 * event frame is the recorded outer shape `{seq, ts, event:{rpcId, payload}}` (see
 * [ai.deepseek.dsh.android.protocol.gateway.v0.EventFrameDto]) and a baseline entry is
 * `{rpcId, payload}` with no outer `seq` (see [BaselineEntryDto]).
 *
 * The channel-dependent / mux-double-envelope dispatch lives ONLY in that shared v0 DTO; v1 does not
 * re-derive it, because the recorded BFF body uses the same field spellings — this reuse is the one case
 * where the field names genuinely match (DECISIONS.md D29). v1-only additions (`projectionVersion`,
 * `apiVersion`, `minClientVersion`, [cursor], `capabilities`) are additive and never collide, so
 * [toDomain] returns the exact same [EventBatch] the gateway produces from an equivalent body.
 */
@Serializable
data class MobileEventsRequest(
    val since: Long,
    val epoch: String? = null,
    @SerialName("wait") val waitMs: Long = 0L,
    val cursor: String? = null,
)

@Serializable
data class MobileEventsResponse(
    // ---- shared gateway envelope field names (byte-comparable with v0) ----
    val epoch: String? = null,
    val since: Long = 0L,
    val latestSeq: Long = 0L,
    val truncated: Boolean = false,
    val reset: Boolean = false,
    val baseline: List<BaselineEntryDto>? = null,
    val events: List<EventFrameDto> = emptyList(),
    // ---- v1-only additions ----
    @SerialName("projectionVersion") val projectionVersion: String? = null,
    @SerialName("apiVersion") val apiVersion: String? = null,
    @SerialName("minClientVersion") val minClientVersion: String? = null,
    @SerialName("waitSupported") val waitSupported: Boolean? = null,
    val cursor: String? = null,
    val capabilities: JsonObject? = null,
    val ok: Boolean? = null,
) {
    fun toDomain(channel: ChannelKind = ChannelKind.Mux, defaultSessionId: SessionId = SessionId("")): EventBatch =
        EventBatch(
            channel = channel,
            epoch = epoch,
            since = since,
            latestSeq = latestSeq,
            truncated = truncated,
            reset = reset,
            waitSupported = waitSupported,
            // baseline rows carry no outer seq: lastSeq is the inner `payload.event.seq` when present
            // and null otherwise (D29); pendingRef is derived by decoding through DurableEventCodec.
            baseline = baseline?.map { it.toBaselineEntry(channel, defaultSessionId) },
            frames = events.map { it.toFrame(channel, defaultSessionId) },
        )
}
