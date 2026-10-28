package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.TransportFailure
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PollRequest
import ai.deepseek.dsh.android.model.PromptRequest
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ResolveRequest
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.WriteResult
import ai.deepseek.dsh.android.protocol.DshJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Replays the REAL gateway 0.8.1 recordings (JSON files under `contract/gateway/v0/`) through [GatewayV0Source]
 * backed by a MockWebServer. Every class named `*ContractReplay*` is collected by
 * `./gradlew :core:network:testDebugUnitTest --tests '*ContractReplay*'` (gate G13/G14).
 *
 * The recorded bytes are handed to the production source UNCHANGED; all domain decoding happens inside
 * `:core:protocol` (D25) — nothing here parses JSON to build domain objects, and nothing here asserts a
 * value the recording does not actually carry (field drift is asserted as "survives without crashing").
 */
class GatewayV0ContractReplayTest {

    private lateinit var server: MockWebServer
    private lateinit var sink: RecordingSink

    @Before
    fun setUp() {
        // Install an in-memory log sink so no android.util.Log is touched (library JVM tests have none).
        sink = RecordingSink()
        DshLog.sink = sink
        DshLog.clearRedactions()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        DshLog.sink = LogcatSinkReset
        DshLog.clearRedactions()
    }

    /** Restores the production default sink without naming android.util.Log outside the facade. */
    private object LogcatSinkReset : LogSink {
        override fun write(level: Level, tag: String, message: String, error: Throwable?) = Unit
    }

    private fun source() = GatewayV0Source(
        config = GatewayConfig(
            base = server.url("/").toString(),
            credential = GatewayCredential { "replay-token-canary" },
        ),
        tuning = ContractFixture.testTuning(),
    )

    @Test
    fun pollIncrementalReplayEchoesEpochAndDoubleEnvelope() = runBlocking {
        val step = ContractFixture.steps("poll-incremental").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val batch = source().poll(PollRequest(ChannelKind.Mux, since = 8L, epoch = "{{EPOCH}}", waitMs = 0L))

        assertEquals("mux", batch.channel.value)
        assertEquals(8L, batch.since)
        assertEquals(9L, batch.latestSeq)
        // F3: the epoch must round-trip so the gateway can detect a reset.
        assertEquals("{{EPOCH}}", batch.epoch)
        // wait=0 => waitSupported false means "not held open", NOT "unsupported" (D29).
        assertFalse(batch.waitSupported ?: true)
        // mux double envelope: outer seq 9 is the transport cursor, inner payload.event.seq 601 is durable.
        assertEquals(1, batch.frames.size)
        val frame = batch.frames.single()
        assertEquals(9L, frame.transportSeq.value)
        assertEquals(601L, frame.seq.value)
        assertEquals("assistant/message", frame.type)
    }

    @Test
    fun respondAcceptedReplayCollapsesToTwoBranchResult() = runBlocking {
        val respondStep = ContractFixture.steps("approval-asked-and-respond-accepted")[1]
        server.enqueue(MockResponse().setResponseCode(respondStep.status).setBody(respondStep.body))

        val result = source().resolve(
            ResolveRequest(RequestId("approval-id-1"), PendingKind.Approval, valueJson = "allowed-once"),
        )

        // F5: v0 answers only {accepted:true}; maps to Accepted.
        assertEquals(WriteResult.WriteOutcome.Accepted, result.outcome)
        assertEquals(true, result.accepted)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/respond", recorded.requestUrl?.encodedPath)
        // D16: the bearer token never appears in the URL.
        assertFalse(recorded.path!!.contains("replay-token-canary"))
        // The modern resolve envelope: `{type, rpcId, result:{ok, value:{approvalId, outcome}}}`.
        val body = DshJson.spec.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("client-response", body["type"]!!.jsonPrimitive.content)
        assertEquals("approval-id-1", body["rpcId"]!!.jsonPrimitive.content)
        assertEquals("true", body["result"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        val value = body["result"]!!.jsonObject["value"]!!.jsonObject
        assertEquals("approval-id-1", value["approvalId"]!!.jsonPrimitive.content)
        assertEquals("allowed-once", value["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun respondUsesTheFrameRpcIdAsTheModernResolveKey() = runBlocking {
        val respondStep = ContractFixture.steps("approval-asked-and-respond-accepted")[1]
        server.enqueue(MockResponse().setResponseCode(respondStep.status).setBody(respondStep.body))

        source().resolve(
            ResolveRequest(
                requestId = RequestId("approval-id-1"),
                kind = PendingKind.Approval,
                valueJson = "rejected",
                transportKey = "waterfall-event-9",
            ),
        )

        // The waterfall eventId is the ONLY key a modern upstream accepts; the payload identity stays
        // in `value.approvalId` where the upstream expects it.
        val body = DshJson.spec.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("waterfall-event-9", body["rpcId"]!!.jsonPrimitive.content)
        val value = body["result"]!!.jsonObject["value"]!!.jsonObject
        assertEquals("approval-id-1", value["approvalId"]!!.jsonPrimitive.content)
        assertEquals("rejected", value["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun promptAcceptedReplaySendsTheContentArrayShape() = runBlocking {
        val step = ContractFixture.steps("prompt-accepted").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        source().prompt(PromptRequest(SessionId("session-1"), text = "hello", attachments = emptyList()))

        val body = DshJson.spec.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("session-1", body["sessionId"]!!.jsonPrimitive.content)
        assertEquals("queue", body["mode"]!!.jsonPrimitive.content)
        val blocks = body["content"]!!.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hello", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(body["text"])
    }

    @Test
    fun promptAcceptedReplayUnwrapsDotRpcEnvelope() = runBlocking {
        val step = ContractFixture.steps("prompt-accepted").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val result = source().prompt(PromptRequest(SessionId("session-1"), text = "hello", attachments = emptyList()))

        assertEquals(WriteResult.WriteOutcome.Accepted, result.outcome)
        val recorded = server.takeRequest()
        assertEquals("/api/session.prompt", recorded.requestUrl?.encodedPath)
    }

    @Test
    fun unauthorizedReplayMapsToUnauthorizedNotTransportNoise() = runBlocking {
        val step = ContractFixture.steps("unauthorized-401").first()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val failure = runCatching {
            source().poll(PollRequest(ChannelKind.Mux, since = 0L, epoch = "{{EPOCH}}", waitMs = 0L))
        }.exceptionOrNull()

        assertTrue(failure is RemoteFailure)
        // 401 is the ONLY device-auth rejection; it is a claim, not transport noise (G18).
        assertEquals(TransportFailure.Unauthorized, (failure as RemoteFailure).failure)
    }

    @Test
    fun malformedPollPayloadFailsGracefullyNotCrash() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{not-json"))

        val failure = runCatching {
            source().poll(PollRequest(ChannelKind.Mux, since = 0L, epoch = "e", waitMs = 0L))
        }.exceptionOrNull()

        // Bad JSON is a transport failure, never an auth failure and never a crash (G18).
        assertTrue(failure is RemoteFailure)
        assertTrue((failure as RemoteFailure).failure is TransportFailure.MalformedPayload)
    }

    @Test
    fun fsListReplayKeepsEntriesDespiteKeyDrift() = runBlocking {
        val step = ContractFixture.steps("fs-list").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val response = source().fsList("/tmp")

        // The recording's rows carry name/path; type/size/mtimeMs key drift is dropped, never crashes.
        assertTrue(response.entries.isNotEmpty())
        assertEquals("nested", response.entries.first().name)
        assertNotNull(response.entries.first().path)
    }

    @Test
    fun fsPreviewTooLargeReplayYieldsDistinctSignal() = runBlocking {
        val step = ContractFixture.steps("fs-preview-too-large").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val outcome = source().fsPreview("/tmp/large-preview.md")

        // 413 preview-too-large is a first-class outcome (F14), not a thrown transport failure.
        assertTrue(outcome is FsPreviewOutcome.TooLarge)
    }

    @Test
    fun rosterReplaySurvivesItemsFieldDriftWithoutCrash() = runBlocking {
        val step = ContractFixture.steps("session-list-roster").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val roster = source().roster()

        // Modern shape: `value.items[]`, key at `sessionId`, title at `projections.values.title`; the
        // recorded subagent row is filtered out of the user-visible roster.
        assertEquals(listOf("{{SESSION_1}}"), roster.map { it.sessionId.value })
        assertEquals("真实功能测试会话", roster.single().title)
    }

    @Test
    fun promptRoutesThroughOperationsWhenCapabilityAdvertised() = runBlocking {
        // First negotiate operations>=1 from a synthetic (decodable) /health, then submit the prompt.
        val healthBody = """{"ok":true,"version":"0.8.1","capabilities":{"operations":1,"eventPolling":1}}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(healthBody))
        val src = source()
        val health = src.health()
        assertEquals(1, health.capabilities.operations)
        server.takeRequest() // consume the recorded /health request so the next one is the write

        // The gateway now advertises operations, so prompt() must hit /api/operations (F15).
        val acceptedStep = ContractFixture.steps("prompt-operations-receipt")[0]
        server.enqueue(MockResponse().setResponseCode(acceptedStep.status).setBody(acceptedStep.body))
        val result = src.prompt(PromptRequest(SessionId("session-1"), text = "hi", attachments = emptyList()))

        val recorded = server.takeRequest()
        // A lost-but-accepted prompt leaves a persistent receipt: the write is routed to operations.
        assertEquals("/api/operations", recorded.requestUrl?.encodedPath)
        assertEquals("POST", recorded.method)
        assertNotNull(recorded.getHeader("Idempotency-Key"))
        // The 202 snapshot is `running`: a durable receipt id exists but there is no verdict yet (D13).
        assertEquals(WriteResult.WriteOutcome.ReceiptPending, result.outcome)
        assertEquals("{{ID_2}}", result.receiptId)
        assertNull(result.accepted)
        // An advertised-but-unknown capability name must not crash and must be hidden, never defaulted.
        assertTrue(src.capabilities.unknown("someFutureCapability"))
    }
}
