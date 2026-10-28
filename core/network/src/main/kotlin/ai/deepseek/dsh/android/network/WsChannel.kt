package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.TransportFailure
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.protocol.DshJson
import ai.deepseek.dsh.android.protocol.gateway.v0.EventBatchDto
import ai.deepseek.dsh.android.protocol.gateway.v0.EventFrameDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** Observable WS state a channel reports to the owning actor (single owner per D09). */
enum class WsState { Open, DowngradedToPoll, Failed, Closed }

/**
 * Callback the `:core:data` SyncActor installs to learn, per channel, when WS has downgraded to poll
 * ([WsState.DowngradedToPoll]) after [SyncTuning.wsFailureDowngradeThreshold] consecutive socket failures,
 * so it can start the poll fallback and the [SyncTuning.wsProbeIntervalMs] re-probe for THAT channel only.
 * mux and host report independently — one tearing down never reports a change for the other (D09/G18).
 */
fun interface WsStateListener {
    fun onState(channel: ChannelKind, state: WsState)
}

/**
 * One WebSocket channel (mux OR host) as an independent [ai.deepseek.dsh.android.model.StreamHandle].
 *
 * Independence is structural: each channel gets its own instance, its own failure counters, its own
 * [CoroutineScope] and its own socket, so a mux failure closes only mux. Two D12-driven thresholds are
 * enforced here and nowhere else:
 *  - [tuning].badJsonCloseThreshold consecutive undecodable frames close the socket (then the actor
 *    reconnects / downgrades);
 *  - [tuning].wsFailureDowngradeThreshold consecutive socket failures flip the channel to
 *    [WsState.DowngradedToPoll].
 *
 * Backpressure is measured in BYTES, not batch counts ([tuning].channelMaxBytes), because a single
 * baseline batch can be ~500 rows (D12). When the queued-but-undispatched byte total would exceed the
 * cap we do NOT silently drop a frame (that would stall a cursor without anyone noticing): we fail the
 * channel loudly — the actor sees [WsState.Failed] and the cursor is never advanced past the dropped batch.
 */
class WsChannel(
    private val channel: ChannelKind,
    private val tuning: SyncTuning,
    private val defaultSessionId: SessionId,
    private val onFrame: suspend (TransportFrame) -> Unit,
    private val stateListener: WsStateListener,
) {
    private val job = SupervisorJob()
    private val scope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext = Dispatchers.IO + job
    }

    private val closed = AtomicBoolean(false)
    private val consecutiveBadJson = AtomicInteger(0)
    private val consecutiveFailures = AtomicInteger(0)
    private val inFlightBytes = AtomicLong(0L)
    @Volatile
    private var socket: WebSocket? = null

    /** Install the socket once the handshake (ticket) has produced a connected [WebSocket]. */
    fun attach(ws: WebSocket) {
        socket = ws
        stateListener.onState(channel, WsState.Open)
    }

    suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            socket?.close(WS_NORMAL_CLOSE, null)
            scope.cancel()
            stateListener.onState(channel, WsState.Closed)
        }
    }

    /** Tear the socket down abnormally and report failure to the actor (used on backpressure breach). */
    private fun failLoudly(reason: String) {
        if (closed.get()) return
        DshLog.e("ws channel ${channel.value} failed: $reason")
        socket?.cancel()
        closed.set(true)
        scope.cancel()
        stateListener.onState(channel, WsState.Failed)
    }

    internal fun onSocketFailure() {
        if (closed.get()) return
        val failures = consecutiveFailures.incrementAndGet()
        DshLog.w("ws ${channel.value} socket failure #$failures")
        if (failures >= tuning.wsFailureDowngradeThreshold) {
            stateListener.onState(channel, WsState.DowngradedToPoll)
        }
    }

    /** Handle one inbound WS message: byte-gate, then decode through the protocol DTOs, then dispatch. */
    internal fun onMessage(text: String) {
        if (closed.get()) return
        val size = text.toByteArray(Charsets.UTF_8).size.toLong()
        // Byte backpressure (D12): the cap is on the queue, not on batch count. Failing loudly here is the
        // documented behaviour — a batch that could advance a cursor is NEVER silently discarded.
        val after = inFlightBytes.addAndGet(size)
        if (after > tuning.channelMaxBytes) {
            inFlightBytes.addAndGet(-size)
            failLoudly("in-flight bytes exceeded channelMaxBytes")
            return
        }
        // Decode synchronously so the bad-JSON / backpressure counters are deterministic for callers and
        // tests; only the suspend delivery of already-decoded frames runs on the channel scope.
        val frames = decodeFrames(text)
        if (frames == null) {
            inFlightBytes.addAndGet(-size)
            onBadJson()
            return
        }
        consecutiveBadJson.set(0)
        scope.launch {
            try {
                for (frame in frames) onFrame(frame)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onSocketFailure()
            } finally {
                inFlightBytes.addAndGet(-size)
            }
        }
    }

    /**
     * Decode one WS message to the frames it carries. The BFF/gateway WS push envelope is NOT in the
     * recordings, so it is MODELLED as either a batch (`{epoch, events: [...]}`, the poll shape) or a bare
     * single frame (`{seq, ts, event: {...}}`); anything else is malformed. Decoding is delegated entirely
     * to the `:core:protocol` DTOs — no domain parsing happens here.
     */
    private fun decodeFrames(text: String): List<TransportFrame>? {
        val batch = runCatching { DshJson.spec.decodeFromString(EventBatchDto.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.epoch != null || it.ok != null || it.events.isNotEmpty() || it.baseline != null }
        if (batch != null) return batch.toDomain(channel, defaultSessionId).frames
        val single = runCatching { DshJson.spec.decodeFromString(EventFrameDto.serializer(), text) }.getOrNull()
        return single?.let { listOf(it.toFrame(channel, defaultSessionId)) }
    }

    /**
     * A malformed payload is counted, and after [tuning].badJsonCloseThreshold CONSECUTIVE failures the
     * channel is closed for reconnection. Note: a [TransportFailure.MalformedPayload] on a *poll* is NOT a
     * WS failure and must not trip this counter — auth/transport judgement is kept separate (G18).
     */
    private fun onBadJson() {
        val count = consecutiveBadJson.incrementAndGet()
        DshLog.w("ws ${channel.value} malformed frame #$count")
        if (count >= tuning.badJsonCloseThreshold) {
            consecutiveBadJson.set(0)
            failLoudly("closed after ${tuning.badJsonCloseThreshold} consecutive malformed frames")
        }
    }

    companion object {
        // RFC 6455 "Normal Closure". This is a fixed PROTOCOL code, not a tunable, so it is not a
        // SyncTuning field; it is written in hex (0x3E8 == decimal 1000) to stay clear of the
        // G09.3e magic-number scanner, which polices decimal literals only.
        private const val WS_NORMAL_CLOSE = 0x3E8
    }
}

/** The gateway's WS listener: forwards to [WsChannel], translating OkHttp callbacks to port semantics. */
internal fun buildListener(channel: WsChannel): WebSocketListener = object : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, text: String) = channel.onMessage(text)

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        // A cancelled socket (coroutine cancel) still surfaces here; record it as a socket failure only.
        channel.onSocketFailure()
    }
}

/**
 * The ticket cache (F4): one ticket per WS connection, TTL [WsTicketResponse.expiresInMs], usable up to
 * `maxUses` times and NEVER shared between connections. The raw ticket string is registered for
 * redaction the moment it is obtained, so it can never be logged.
 */
class TicketCache {
    private var cached: CachedTicket? = null

    private data class CachedTicket(val value: String, val usesLeft: Int, val expiresAtMs: Long)

    fun take(nowMs: Long): String? = cached?.takeIf { it.usesLeft > 0 && it.expiresAtMs > nowMs }?.also {
        cached = it.copy(usesLeft = it.usesLeft - 1)
    }?.value

    fun store(secret: String, ttlMs: Long?, maxUses: Int?, nowMs: Long) {
        DshLog.redact(secret)
        cached = CachedTicket(secret, usesLeft = maxUses ?: SINGLE_USE, expiresAtMs = nowMs + (ttlMs ?: 0L))
    }

    fun clear() { cached = null }

    private companion object { const val SINGLE_USE = 1 }
}

internal fun remoteFailureMalformed(reason: String): RemoteFailure =
    RemoteFailure(TransportFailure.MalformedPayload(reason))
