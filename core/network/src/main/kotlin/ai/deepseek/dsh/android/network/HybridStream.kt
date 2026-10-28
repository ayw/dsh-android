package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.TransportFailure
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.PollRequest
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.StreamHandle
import ai.deepseek.dsh.android.model.TransportFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * The read cursor of one logical channel. [sinceSeq] is the **outer** frame number of the last delivered
 * frame — the transport cursor (DECISIONS **D29**). The session-internal `payload.event.seq` a mux frame
 * carries is NEVER a cursor input, and [HybridStream] is the only place this cursor lives, so the two
 * seq spaces cannot be swapped by accident further up.
 */
data class ChannelCursor(val sinceSeq: Long, val epoch: String?)

/** Poll entry point: [GatewayV0Source.poll] satisfies this; tests substitute a scripted recorder. */
fun interface ChannelPoller {
    suspend fun poll(request: PollRequest): EventBatch
}

/**
 * Things the owning actor must learn about a channel that a [TransportFrame] cannot express: a ring-buffer
 * reset ([Reset]), the capability answer for long-poll ([PollMode]), and the moment the channel stopped
 * being carried by WebSocket and started polling ([Polling]). Auth expiry ([AuthExpired]) is derived ONLY
 * from an observed 401/403, never from a timeout/TLS/DNS/malformed payload (G18).
 */
sealed interface StreamNotice {
    data class Reset(val batch: EventBatch) : StreamNotice
    data class PollMode(val waitSupported: Boolean) : StreamNotice
    data object Polling : StreamNotice
    data object AuthExpired : StreamNotice
}

/** Counters the diagnostics page (G10/D23) reads; every field is derived from [SyncTuning], not a literal. */
data class StreamDiagnostics(
    val channel: ChannelKind,
    val pollRounds: Long,
    val deliveredFrames: Long,
    val consecutivePollFailures: Int,
    val wsDowngrades: Int,
    val degradedRounds: Long,
    val transportCursor: ChannelCursor,
)

/**
 * One logical channel (mux OR host) as WebSocket-primary transport with an HTTP long-poll fallback.
 *
 *  - **One owner, two logical channels (D09)**: a [HybridStream] instance is per channel and holds its own
 *    [ChannelCursor], its own failure counters, its own socket and its own delivery continuation. Two
 *    instances never share mutable state, so a mux outage can neither stall the host channel nor borrow its
 *    cursor; the `*StreamFallback*` test proves exactly that with two instances over one fake backend.
 *  - **WS first, poll on downgrade**: the socket is opened when the gateway advertises the ticket
 *    capability; after [SyncTuning.wsFailureDowngradeThreshold] consecutive socket failures the
 *    [WsChannel] reports [WsState.DowngradedToPoll] and the poll loop takes over, re-probing the socket
 *    every [SyncTuning.wsProbeIntervalMs] (D12).
 *  - **Poll pacing**: a round asks for [SyncTuning.pollWaitMs] of server-side hold. If the response answers
 *    `waitSupported=false` while we DID request a hold, that round degrades to
 *    [SyncTuning.pollWaitUnsupportedDelayMs] so we never busy-loop (D29 semantics of `waitSupported`).
 *  - **Failure budget**: connection failures back off through [SyncTuning.nextBackoffMs]; a hung round is
 *    cut by [SyncTuning.workerRoundBudgetMs].
 *  - **Cursor discipline (D29)**: the cursor advances by `frame.transportSeq` only, and only AFTER the frame
 *    was handed to [onFrame]; a cancelled or failed delivery therefore re-reads the same frame instead of
 *    skipping it.
 */
class HybridStream(
    private val channel: ChannelKind,
    private val tuning: SyncTuning,
    private val poller: ChannelPoller,
    private val wsOpener: suspend (WsChannel) -> WebSocket?,
    private val onFrame: suspend (TransportFrame) -> Unit,
    private val stateListener: WsStateListener = WsStateListener { _, _ -> },
    private val onNotice: suspend (StreamNotice) -> Unit = {},
    private val defaultSessionId: SessionId = SessionId(""),
    seed: ChannelCursor = ChannelCursor(0L, null),
    private val clock: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Double = { DEFAULT_JITTER },
) {
    private val cursorRef = AtomicReference(seed)
    private val polling = AtomicBoolean(false)
    private val socketAlive = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val pollRounds = AtomicLong(0L)
    private val deliveredFrames = AtomicLong(0L)
    private val degradedRounds = AtomicLong(0L)
    private val downgrades = AtomicInteger(0)
    private val consecutiveFailures = AtomicInteger(0)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var loopJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var lastPollWaitSupported: Boolean? = null

    /** The current transport cursor (outer seq). Read by the actor to persist `sync_state`. */
    val cursor: ChannelCursor get() = cursorRef.get()

    /** True while this channel is being carried by HTTP long-poll instead of WebSocket. */
    val isPolling: Boolean get() = polling.get()

    fun diagnostics(): StreamDiagnostics = StreamDiagnostics(
        channel = channel,
        pollRounds = pollRounds.get(),
        deliveredFrames = deliveredFrames.get(),
        consecutivePollFailures = consecutiveFailures.get(),
        wsDowngrades = downgrades.get(),
        degradedRounds = degradedRounds.get(),
        transportCursor = cursorRef.get(),
    )

    /**
     * Start the channel loop on [scope]. The returned handle tears the loop AND the socket down; the
     * cursor is left where the last successfully delivered frame put it (nothing is advanced on close).
     */
    fun start(scope: CoroutineScope): StreamHandle {
        val job = scope.launch { loop() }
        loopJob = job
        return StreamHandle(close = { stop() })
    }

    /**
     * Stop THIS channel only: its loop, its socket and its fallback timer. Idempotent, and it never touches
     * another channel's instance — that structural isolation is why mux and host are two objects at all
     * (D09), and it is what [GatewayV0Source.openStream] hands back as the port's close callback.
     */
    fun stop() {
        closed.set(true)
        loopJob?.cancel()
        socket?.cancel()
        socket = null
        socketAlive.set(false)
        polling.set(false)
    }

    private suspend fun loop() {
        while (coroutineContext.isActive && !closed.get()) {
            if (!socketAlive.get()) connectSocket()
            if (socketAlive.get()) {
                // WS is carrying this channel: nothing to poll. The probe tick is how we notice a socket
                // that died without a callback (a dropped 1006 cannot be observed from OkHttp alone).
                delay(tuning.wsProbeIntervalMs)
                continue
            }
            if (!polling.get()) {
                // No WS available and the downgrade has not been reported yet: start polling immediately.
                enterPolling()
            }
            pollRound()
        }
    }

    /** Open the WS surface for THIS channel only; failure keeps the instance on the poll path. */
    private suspend fun connectSocket(): Boolean {
        val wsChannel = WsChannel(channel, tuning, defaultSessionId, ::deliver, ::onWsState)
        val opened = runCatching { wsOpener(wsChannel) }
            .onFailure { e -> if (e is CancellationException) throw e else DshLog.w("ws open failed: ${e.javaClass.simpleName}") }
            .getOrNull()
        if (opened == null) {
            socketAlive.set(false)
            enterPolling()
            return false
        }
        socket = opened
        socketAlive.set(true)
        polling.set(false)
        consecutiveFailures.set(0)
        return true
    }

    /** Bridges [WsChannel]'s per-channel state into this stream's fallback decision. */
    private fun onWsState(changed: ChannelKind, state: WsState) {
        stateListener.onState(changed, state)
        if (changed != channel) return // structural independence: another channel can never move us
        when (state) {
            WsState.Open -> {
                socketAlive.set(true)
                polling.set(false)
            }
            WsState.DowngradedToPoll, WsState.Failed, WsState.Closed -> {
                socketAlive.set(false)
                enterPolling()
            }
        }
    }

    private fun enterPolling() {
        if (polling.compareAndSet(false, true)) {
            downgrades.incrementAndGet()
            stateListener.onState(channel, WsState.DowngradedToPoll)
        }
    }

    /** One long-poll round: request, deliver, advance the OUTER cursor. Never throws for a transport failure. */
    private suspend fun pollRound() {
        val before = cursorRef.get()
        val waitRequested = tuning.pollWaitMs
        val request = PollRequest(channel = channel, since = before.sinceSeq, epoch = before.epoch, waitMs = waitRequested)
        pollRounds.incrementAndGet()
        val outcome = runCatching { withTimeout(tuning.workerRoundBudgetMs) { poller.poll(request) } }
        val batch = outcome.getOrElse { e ->
            when (e) {
                is CancellationException -> throw e
                is RemoteFailure -> {
                    handleFailure(e)
                    backoffDelay()
                }
                else -> {
                    DshLog.e("unexpected poll failure ${e.javaClass.simpleName}")
                    consecutiveFailures.incrementAndGet()
                    backoffDelay()
                }
            }
            return
        }

        consecutiveFailures.set(0)
        if (batch.reset || batch.truncated) {
            // The buffer rolled over: the actor must tombstone→baseline→events→cursor in that order (G06).
            // Nothing is advanced past the reset here; the cursor stays at [before] until it re-reads.
            withContext(NonCancellable) { onNotice(StreamNotice.Reset(batch)) }
            return
        }
        if (batch.epoch != null) cursorRef.set(ChannelCursor(before.sinceSeq, batch.epoch))

        val supported = batch.waitSupported
        lastPollWaitSupported = supported
        // D29: waitSupported=false only means "this request mode was not held open". Only a round that asked
        // for a hold (waitRequested > 0) and was refused counts as degraded.
        val degraded = supported == false && waitRequested > 0L
        if (degraded) degradedRounds.incrementAndGet()
        withContext(NonCancellable) { onNotice(StreamNotice.PollMode(supported ?: true)) }

        for (frame in batch.frames) deliver(frame)
        // The cursor is the highest OUTER seq seen, never an inner session seq (D29).
        batch.frames.maxOfOrNull { it.transportSeq.value }?.let { highest ->
            cursorRef.updateAndGet { if (highest > it.sinceSeq) ChannelCursor(highest, it.epoch) else it }
        }
        if (degraded) {
            // The server refused to hold this round open, so pacing is ours: sleep the degraded tick instead
            // of returning immediately (that would be the busy-poll D12 forbids). A held round already spent
            // pollWaitMs server-side and returns straight away.
            delay(tuning.pollWaitUnsupportedDelayMs)
        }
    }

    /** Deliver one frame and advance the cursor ONLY after the consumer accepted it. */
    private suspend fun deliver(frame: TransportFrame) {
        onFrame(frame)
        if (frame.transportSeq.value > cursorRef.get().sinceSeq) {
            cursorRef.set(ChannelCursor(frame.transportSeq.value, cursorRef.get().epoch))
        }
        deliveredFrames.incrementAndGet()
    }

    private suspend fun handleFailure(failure: RemoteFailure) {
        consecutiveFailures.incrementAndGet()
        when (failure.failure) {
            // G18: only an observed 401/403 may end this channel's polling; the actor owns the auth state.
            TransportFailure.Unauthorized, is TransportFailure.Forbidden -> {
                DshLog.w("channel ${channel.value} auth rejection ${failure.failure.javaClass.simpleName}")
                withContext(NonCancellable) { onNotice(StreamNotice.AuthExpired) }
                closed.set(true)
            }
            else -> DshLog.w("channel ${channel.value} poll failure: ${failure.failure}")
        }
    }

    private suspend fun backoffDelay() {
        val attempt = (consecutiveFailures.get() - 1).coerceAtLeast(0)
        delay(tuning.nextBackoffMs(attempt, jitter()))
    }

    /** The wait actually used by the last round, for diagnostics (`lastPollWaitSupported`, G10). */
    fun lastWaitSupported(): Boolean? = lastPollWaitSupported

    /** Test/inspection seam: force the channel onto the poll path without waiting for socket failures. */
    fun forcePolling() = enterPolling()

    private companion object {
        /** The neutral point of the [SyncTuning.backoffJitterLow]..[SyncTuning.backoffJitterHigh] band. */
        const val DEFAULT_JITTER = 1.0
    }
}
