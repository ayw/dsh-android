package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.BaselineEntry
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.protocol.BaselinePendingRef
import ai.deepseek.dsh.android.protocol.DshJson
import ai.deepseek.dsh.android.protocol.long
import ai.deepseek.dsh.android.protocol.obj
import ai.deepseek.dsh.android.protocol.rawOrEmptyObject
import ai.deepseek.dsh.android.protocol.string
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The wire frame as RECORDED from gateway 0.8.1 (`contract/gateway/v0/poll-*.json`), NOT the flat
 * `{seq, type, payload, replay?}` the original brief assumed. DECISIONS.md **D29** is binding here:
 * the envelope is channel-dependent and mux is a **double** envelope. The observed shape of a poll
 * `events[]` entry is:
 *
 * ```
 * { "seq": <outer>, "ts": <wallClock>, "event": { "rpcId": <id>, "payload": { ... } } }
 * ```
 *
 * - `seq` is the OUTER frame number — a JSON **number**, never a string. It is the transport cursor
 *   ([TransportFrame.transportSeq]).
 * - `ts` (not `time`) is the frame wall clock. In the recorded artifacts every `ts` value is the
 *   normalization placeholder `{{TS_n}}` (see `contract/gateway/v0/README.md`), so it is modeled as a
 *   raw [JsonElement] and read tolerantly: a real numeric `ts` becomes [TransportFrame.wallClockMs],
 *   a placeholder string degrades to `0` rather than throwing (D08/F16 forbid crashing on shape).
 * - `event.payload` is where the content lives, and its shape depends on the channel:
 *     - **host** ([ChannelKind.Host]): `payload` IS the row — `{sessionId, type, ...rowFields}`
 *       (e.g. `session/subscribed`, `host/session-status`). No inner `seq`, so the durable seq equals
 *       the outer transport seq.
 *     - **mux** ([ChannelKind.Mux]): `payload` is usually ANOTHER envelope
 *       `{sessionId, type:"session/event", event:{type, data, seq}}`. The real durable event is at
 *       `payload.event` and carries its own session-internal `seq`; the outer `session/event` marker
 *       MUST NOT leak as the event type. Some mux rows are flat (e.g. `approval/requested`,
 *       `session/subscribed`) exactly like host rows — mux is therefore "unwrap if it is a
 *       `session/event` envelope, else treat the payload as the row".
 *
 * `replay` is transport metadata (F10/D08): retained on [TransportFrame], never on a durable event.
 * A missing `ignorable` means NOT ignorable (F16/D08).
 *
 * This DTO is decoded through the single shared [DshJson] instance (D06); `payload` is kept verbatim
 * and re-parsed only by the channel dispatch in [toFrame].
 */
@Serializable
data class EventFrameDto(
    val seq: Long,
    /** Frame wall clock. Raw element: numeric in production, `{{TS_n}}` placeholder in recordings. */
    val ts: JsonElement? = null,
    val event: FrameEventDto? = null,
    /** Transport metadata (F10/D08): retained on [TransportFrame], never on a durable event. */
    val replay: Boolean? = null,
) {
    /**
     * Decode to a [TransportFrame] using the single, explicit, [ChannelKind]-keyed dispatch in
     * [resolveDurableRow]. Family/type decisions are made on the PARSED `payload` object — never by
     * substring-sniffing raw JSON text (D27).
     */
    fun toFrame(channel: ChannelKind, defaultSessionId: SessionId): TransportFrame {
        val transportSeq = EventSeq(seq)
        val row = resolveDurableRow(channel, event?.payload as? JsonObject)
        return TransportFrame(
            channel = channel,
            sessionId = row.sessionId ?: defaultSessionId,
            // mux durable seq is the INNER payload.event.seq when present; host/flat rows have none,
            // so they fall back to the outer transport seq (host: seq == transportSeq).
            seq = row.seq ?: transportSeq,
            transportSeq = transportSeq,
            type = row.type,
            ignorable = row.ignorable,
            payloadJson = row.payloadJson,
            wallClockMs = tsToMillis(ts),
            replay = replay ?: false,
            rpcId = event?.rpcId,
        )
    }

    override fun toString(): String =
        // `payload` is intentionally omitted: it can hold prompt text / tool arguments / file paths /
        // session titles, none of which may reach a loggable toString (requirement: DTO toString is
        // secret- and PII-free). `seq`/`ts`/`replay` are transport numbers, safe to print.
        "EventFrameDto(seq=$seq, ts=<omitted>, event=${event?.mask()}, replay=$replay)"
}

/**
 * The `event` object of a frame: `{rpcId, payload}`. `rpcId` is an opaque connection-layer id (F9:
 * `rpcId` only exists at the client connection layer) and is not one of the forbidden toString fields,
 * but `payload` is masked by [mask].
 */
@Serializable
data class FrameEventDto(
    val rpcId: String? = null,
    val payload: JsonElement? = null,
) {
    /** Masked rendering used by [EventFrameDto.toString]; the raw payload is never printed. */
    internal fun mask(): String = "FrameEventDto(rpcId=<omitted>, payload=<omitted>)"

    override fun toString(): String = mask()
}

/**
 * A `baseline` entry is `{rpcId, payload}` with **no `seq` at all** (D29). Therefore
 * [BaselineEntry.lastSeq] can only be sourced from the inner `payload.event.seq` (mux double envelope)
 * and is `null` for every flat row — which is exactly what the recordings show.
 */
@Serializable
data class BaselineEntryDto(
    val rpcId: String? = null,
    val payload: JsonElement? = null,
) {
    /**
     * The decoded `lastSeq`: the session-internal seq from `payload.event.seq` when present, else
     * `null`. Sweep watermarks must take the max over non-null values, not the last row (D29).
     */
    fun lastSeq(channel: ChannelKind): EventSeq? = resolveDurableRow(channel, payload as? JsonObject).seq

    /**
     * A [TransportFrame] view of this baseline row, used purely to derive the pending identity via
     * [BaselinePendingRef]. Baseline rows carry no outer seq, so both seq fields default to `0`; the
     * pending-ref derivation reads only `type`/`payloadJson`, never the seq.
     */
    fun toFrame(channel: ChannelKind, defaultSessionId: SessionId): TransportFrame {
        val row = resolveDurableRow(channel, payload as? JsonObject)
        val seq = row.seq ?: EventSeq(0)
        return TransportFrame(
            channel = channel,
            sessionId = row.sessionId ?: defaultSessionId,
            seq = seq,
            transportSeq = seq,
            type = row.type,
            ignorable = row.ignorable,
            payloadJson = row.payloadJson,
            wallClockMs = 0L,
            replay = false,
            rpcId = rpcId,
        )
    }

    fun toBaselineEntry(channel: ChannelKind, defaultSessionId: SessionId): BaselineEntry {
        val row = resolveDurableRow(channel, payload as? JsonObject)
        return BaselineEntry(
            sessionId = row.sessionId ?: defaultSessionId,
            lastSeq = row.seq,
            payloadJson = row.payloadJson,
            pendingRef = BaselinePendingRef.of(toFrame(channel, defaultSessionId)),
        )
    }

    override fun toString(): String = "BaselineEntryDto(rpcId=<omitted>, payload=<omitted>)"
}

/**
 * The already-dispatched durable view of a frame's `event.payload`, produced by the single
 * [ChannelKind]-keyed [resolveDurableRow] below. [seq] is the session-internal number (`null` for
 * flat rows, which then fall back to the outer transport seq in the caller).
 */
internal class DurableRow(
    val type: String,
    val sessionId: SessionId?,
    val seq: EventSeq?,
    val ignorable: Boolean,
    val payloadJson: String,
)

/** The outer mux marker that wraps a durable event one level deeper (D29). */
internal const val SESSION_EVENT_TYPE: String = "session/event"

/**
 * The ONE explicit, documented dispatch used by both [EventFrameDto.toFrame] and [BaselineEntryDto].
 * It is keyed on [ChannelKind]:
 *
 *  - [ChannelKind.Mux]: unwrap iff `payload.type == "session/event"` and `payload.event` is an object;
 *    the durable type is the INNER `payload.event.type` (the outer `session/event` marker never leaks),
 *    the durable seq is `payload.event.seq`, and the codec payload is `payload.event.data`. A mux row
 *    that is NOT a `session/event` envelope (e.g. `approval/requested`, `session/subscribed`) is a flat
 *    row and handled like host.
 *  - anything else (host): the `payload` IS the row; durable type is `payload.type`, there is no inner
 *    seq (so seq falls back to transport seq), and the codec payload is the row itself.
 *
 * The session id is always read from the `payload` envelope level (both shapes carry `sessionId`
 * there), falling back to the caller-supplied default. Missing `ignorable` => NOT ignorable (F16/D08).
 */
internal fun resolveDurableRow(channel: ChannelKind, payload: JsonObject?): DurableRow {
    val p = payload ?: return DurableRow("", null, null, false, "{}")
    val sessionId = (p.string("sessionId") ?: p.string("sessionKey"))?.let(::SessionId)
    val isMuxDoubleEnvelope = channel == ChannelKind.Mux &&
        p.string("type") == SESSION_EVENT_TYPE &&
        p.obj("event") != null
    return if (isMuxDoubleEnvelope) {
        resolveSessionEventRow(p.obj("event")!!, fallbackSessionId = sessionId, outerIgnorable = ignorableOf(p))
    } else {
        // Flat row (host rows, and mux rows that are not session/event envelopes).
        DurableRow(
            type = p.string("type").orEmpty(),
            sessionId = sessionId,
            seq = null,
            ignorable = ignorableOf(p),
            payloadJson = p.toString(),
        )
    }
}

/**
 * The durable event object itself: `{type, seq, data}`. A mux poll envelope wraps it in
 * `payload.event`, while a `session.page` / `session.history` row carries it directly under `event`
 * with **no** outer transport seq and **no** `rpcId`/`payload` wrapper (DECISIONS **D30**). One reader
 * for both, so the two paths cannot drift apart.
 */
internal fun resolveSessionEventRow(
    inner: JsonObject,
    fallbackSessionId: SessionId? = null,
    outerIgnorable: Boolean = false,
): DurableRow = DurableRow(
    type = inner.string("type").orEmpty(),
    sessionId = (inner.string("sessionId") ?: inner.string("sessionKey"))?.let(::SessionId) ?: fallbackSessionId,
    seq = inner.long("seq")?.let(::EventSeq),
    ignorable = ignorableOf(inner) || outerIgnorable,
    // The DurableEventCodec reads data fields at the top level, so hand it `event.data`.
    payloadJson = (inner.obj("data") ?: inner).rawOrEmptyObject(),
)

/** `ignorable` is a JSON boolean on the wire; tolerate the string `"true"`; absent => false (F16/D08). */
private fun ignorableOf(obj: JsonObject): Boolean {
    val prim = obj["ignorable"] as? JsonPrimitive ?: return false
    if (prim.isString) return prim.content.equals("true", ignoreCase = true)
    return prim.booleanOrNull ?: false
}

/** Read `ts` tolerantly: a numeric element -> millis; a placeholder string -> `0` (never throws). */
private fun tsToMillis(ts: JsonElement?): Long = when (ts) {
    null -> 0L
    is JsonPrimitive -> ts.content.toLongOrNull() ?: 0L
    else -> 0L
}

/**
 * The gateway event envelope — the fields confirmed in `contract/gateway/v0/poll-*.json`:
 * `epoch/since/latestSeq/reset/truncated/waitSupported/ok/kind` plus `baseline?` and `events`.
 *
 * `waitSupported` is `false` when the request had `wait=0`; it means "this request mode was not held
 * open", NOT "the server lacks long-poll" (D29). It is passed through verbatim; the caller decides how
 * to degrade (only when it explicitly requested `wait > 0`).
 *
 * `baseline?` carries the shorter `{rpcId, payload}` rows (see [BaselineEntryDto]); its ABSENCE (not
 * emptiness) means "this response has no baseline family, do not sweep" ([EventBatch.baseline] doc),
 * which is why it decodes nullable with a `null` default and is mapped straight through.
 *
 * These are transport fields per D06/G15 normalization: `epoch/since/latestSeq/reset/truncated/replay/
 * ok/kind/waitSupported` are dropped before a projection snapshot is hashed for v0/v1 parity.
 */
@Serializable
data class EventBatchDto(
    val epoch: String? = null,
    val since: Long = 0L,
    val latestSeq: Long = 0L,
    val reset: Boolean = false,
    val truncated: Boolean = false,
    val waitSupported: Boolean? = null,
    val ok: Boolean? = null,
    val kind: String? = null,
    val baseline: List<BaselineEntryDto>? = null,
    val events: List<EventFrameDto> = emptyList(),
) {
    fun toDomain(channel: ChannelKind, defaultSessionId: SessionId = SessionId("")): EventBatch = EventBatch(
        channel = channel,
        epoch = epoch,
        since = since,
        latestSeq = latestSeq,
        truncated = truncated,
        reset = reset,
        waitSupported = waitSupported,
        baseline = baseline?.map { it.toBaselineEntry(channel, defaultSessionId) },
        frames = events.map { it.toFrame(channel, defaultSessionId) },
    )
}

/** Request for `/api/events.poll` (F3): MUST echo `epoch`; `wait` is the long-poll hold in ms. */
@Serializable
data class PollRequestDto(
    val since: Long,
    val epoch: String? = null,
    @SerialName("wait") val waitMs: Long = 0L,
) {
    companion object {
        fun fromDomain(
            since: Long,
            epoch: String?,
            waitMs: Long,
        ): PollRequestDto = PollRequestDto(since = since, epoch = epoch, waitMs = waitMs)
    }
}

/** Response of `/api/ws-ticket` — a single-use, 90s-TTL secret (F4). See [secret] for redaction. */
@Serializable
data class WsTicketResponse(
    // Wire key is `ticket`, but the Kotlin property is deliberately not named `ticket` so the
    // generated `toString()` never prints the forbidden token name; the value itself is redacted.
    @SerialName("ticket") val secret: String,
    /** Legacy gateways report a relative TTL; current gateways report an absolute [expiresAt]. */
    @SerialName("expiresInMs") val expiresInMs: Long? = null,
    @SerialName("expiresAt") val expiresAt: Long? = null,
    @SerialName("maxUses") val maxUses: Int? = null,
    val ok: Boolean? = null,
) {
    /** Remaining TTL at [nowMs]; `null` when the gateway published neither form (caller keeps its default). */
    fun ttlMs(nowMs: Long): Long? = expiresInMs ?: expiresAt?.let { (it - nowMs).coerceAtLeast(0L) }

    override fun toString(): String =
        "WsTicketResponse(secret=<redacted>,expiresAt=$expiresAt,expiresInMs=$expiresInMs,maxUses=$maxUses,ok=$ok)"
}

/** Request body for `/api/ws-ticket`. Channel selects the mux vs host socket (D09). */
@Serializable
data class WsTicketRequest(val channel: String)

/** Convenience: parse a raw response body into the batch envelope, tolerant of a bare array. */
internal fun decodeEventBatch(json: String): EventBatchDto =
    DshJson.spec.decodeFromString(EventBatchDto.serializer(), json)
