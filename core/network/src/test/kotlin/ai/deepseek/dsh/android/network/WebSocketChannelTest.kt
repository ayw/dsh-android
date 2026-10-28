package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TransportFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-channel WebSocket behaviour: the two D12 thresholds (bad-JSON close, socket-failure downgrade) and
 * byte backpressure are enforced here and verified to fail INDEPENDENTLY per channel (mux vs host), as
 * the data-layer SyncActor requires (D09 / G18). Every threshold is read from the injected [SyncTuning].
 */
class WebSocketChannelTest {

    private val recorded = CopyOnWriteArrayList<Pair<String, WsState>>()

    private val listener = WsStateListener { channel, state -> recorded += channel.value to state }

    @Before
    fun setUp() {
        DshLog.sink = RecordingSink()
        DshLog.clearRedactions()
        recorded.clear()
    }

    private fun channel(
        kind: ChannelKind = ChannelKind.Mux,
        tuning: SyncTuning = ContractFixture.testTuning(),
        onFrame: suspend (TransportFrame) -> Unit = {},
    ): WsChannel = WsChannel(kind, tuning, SessionId(""), onFrame, listener)

    @Test
    fun malformedFramesCloseChannelAfterThreshold() {
        val tuning = ContractFixture.testTuning()
        val ch = channel(tuning = tuning)
        repeat(tuning.badJsonCloseThreshold - 1) { ch.onMessage("this-is-not-json") }
        // Not yet at the threshold: still open, no Failed reported.
        assertTrue(recorded.none { it.second == WsState.Failed })

        ch.onMessage("still-not-json")
        // badJsonCloseThreshold consecutive malformed frames -> channel torn down and reported Failed.
        assertTrue(recorded.any { it.first == "mux" && it.second == WsState.Failed })
    }

    @Test
    fun socketFailuresDowngradeToPollAtThreshold() {
        val tuning = ContractFixture.testTuning()
        val ch = channel(tuning = tuning)
        repeat(tuning.wsFailureDowngradeThreshold - 1) { ch.onSocketFailure() }
        assertTrue(recorded.none { it.second == WsState.DowngradedToPoll })

        ch.onSocketFailure()
        assertTrue(recorded.any { it.first == "mux" && it.second == WsState.DowngradedToPoll })
    }

    @Test
    fun byteBackpressureFailsLoudlyInsteadOfSilentlyDropping() {
        // A tiny cap so one oversized frame trips it; the cap is measured in BYTES, not batch count.
        val tuning = ContractFixture.testTuning().copy(channelMaxBytes = 32L)
        val ch = channel(tuning = tuning)
        val big = "x".repeat(64)
        ch.onMessage(big)
        // A batch that could advance a cursor is NEVER silently discarded: the channel reports Failed.
        assertTrue(recorded.any { it.first == "mux" && it.second == WsState.Failed })
    }

    @Test
    fun muxFailureDoesNotTearDownHostChannel() {
        val muxTuning = ContractFixture.testTuning().copy(channelMaxBytes = 16L)
        val mux = channel(ChannelKind.Mux, muxTuning)
        val host = channel(ChannelKind.Host, ContractFixture.testTuning())

        mux.onMessage("y".repeat(64)) // trip mux backpressure

        assertTrue(recorded.any { it.first == "mux" && it.second == WsState.Failed })
        // Independence: host never observes mux's failure (D09 — one channel tearing down is not the other's).
        assertTrue(recorded.none { it.first == "host" })
        // host is still usable: a valid host frame is delivered.
        val latch = CompletableDeferred<Unit>()
        val liveHost = channel(ChannelKind.Host, ContractFixture.testTuning(), onFrame = { latch.complete(Unit) })
        liveHost.onMessage("""{"epoch":"e","since":1,"latestSeq":2,"events":[{"seq":2,"event":{"payload":{"sessionId":"s","type":"host/status"}}}]}""")
        runBlocking { withTimeout(2000) { latch.await() } }
    }

    @Test
    fun validBatchFrameIsDeliveredToConsumer() = runBlocking {
        val received = CompletableDeferred<TransportFrame>()
        val ch = channel(onFrame = { received.complete(it) })
        ch.onMessage("""{"epoch":"e","since":1,"latestSeq":7,"events":[{"seq":7,"event":{"payload":{"sessionId":"sess","type":"session/event","event":{"type":"assistant/message","seq":42,"data":{}}}}}]}""")
        val frame = withTimeout(2000) { received.await() }
        // mux double envelope: transport cursor is the outer 7, the durable seq is the inner 42.
        assertEquals(7L, frame.transportSeq.value)
        assertEquals(42L, frame.seq.value)
        assertEquals("assistant/message", frame.type)
    }
}
