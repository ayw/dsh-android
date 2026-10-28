package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.TransportFailure
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.HistoryRequest
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PollRequest
import ai.deepseek.dsh.android.model.PromptRequest
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ResolveRequest
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.WriteResult
import ai.deepseek.dsh.android.protocol.DshJson
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionCreateResponse
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionMutationResponse
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Gate G04 (deviation: this module's slice of it) — replay EVERY `contract/gateway/v0` artifact through the
 * production pipeline ([GatewayV0Source] + [GatewayTransport] + OkHttp against a [MockWebServer]).
 *
 * Two directions are proved:
 *  - **request contract**: the method, path, query and header surface the client sends must match what the
 *    gateway actually received (`{{BASE}}` is replaced by the MockWebServer origin, `{{TOKEN}}` by the test
 *    canary, since a recorder never stores a credential);
 *  - **response contract**: the recorded bytes are handed to the source UNCHANGED and the resulting port
 *    objects must carry the recording's own numbers — frame counts, the OUTER seq list (the transport cursor,
 *    D29) and the INNER `payload.event.seq` list (the durable seq), the envelope flags, the baseline size.
 *    Every expected number is read back out of the artifact, so a re-record moves the assertion with it
 *    instead of leaving a stale literal behind.
 *
 * Where the frozen `:core:protocol` DTO cannot express a recorded shape, the test pins the recording's value
 * NEXT TO the DTO's (smaller) reading and names the case `...ProtocolShapeGap...`: the drift then fails
 * loudly the moment the DTO is fixed, instead of being quietly accepted. Those drifts are listed in the
 * module report; none of them is fixable inside `:core:network` without editing another module.
 */
class GatewayV0FullContractReplayTest {

    private lateinit var server: MockWebServer
    private lateinit var sink: RecordingSink
    private lateinit var src: GatewayV0Source
    private lateinit var transport: GatewayTransport
    private lateinit var config: GatewayConfig
    private lateinit var tuning: SyncTuning

    @Before
    fun setUp() {
        sink = RecordingSink()
        DshLog.sink = sink
        DshLog.clearRedactions()
        server = MockWebServer()
        server.start()
        config = GatewayConfig(base = server.url("/").toString(), credential = GatewayCredential { TOKEN })
        tuning = ContractFixture.testTuning()
        transport = GatewayTransport(config, tuning)
        src = GatewayV0Source(config = config, tuning = tuning, transport = transport)
    }

    @After
    fun tearDown() {
        server.shutdown()
        DshLog.clearRedactions()
    }

    // ---- request direction ----

    @Test
    fun everyRecordedStepIsSentWithTheRecordedMethodPathQueryAndHeaders() = runBlocking {
        var steps = 0
        for (scenario in ContractFixture.scenarios()) {
            // Each scenario was recorded against its own gateway state; a capability negotiated by an
            // earlier scenario (e.g. `operations`) must not reroute a later recorded request.
            val scenarioSource = GatewayV0Source(config = config, tuning = tuning, transport = transport)
            for (step in scenario.steps) {
                server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))
                // The response side is this test's deliberate blind spot (it is covered per-scenario below):
                // a protocol-shape drift must not hide a request-contract regression.
                runCatching { send(scenarioSource, step) }
                assertRequestMatches(step, "[${scenario.name} ${step.method} ${step.path}] ")
                steps++
            }
        }
        // 15 recorded artifacts, 24 request/response pairs — every one of them replayed.
        assertEquals(24, steps)
        // Nothing may have leaked to the log while the whole contract was replayed.
        assertFalse("token leaked into a log line during replay", sink.text().contains(TOKEN))
    }

    @Test
    fun everyPollStepReplaysItsRecordedFrameCountOuterSeqsAndEnvelopeFlags() = runBlocking {
        var checked = 0
        for (scenario in ContractFixture.scenarios()) {
            scenario.steps.forEachIndexed { index, step ->
                if (step.barePath != POLL_PATH || step.status != 200) return@forEachIndexed
                server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))
                val batch = src.poll(pollRequestOf(step))
                val tag = "[${scenario.name}#$index] "
                val rows = ContractFixture.recordedEventRows(scenario.name, index)

                assertEquals(tag + "frame count", rows.size, batch.frames.size)
                // D29: the OUTER seq list is the transport cursor's only input…
                assertEquals(tag + "outer seqs", rows.map { it.first }, batch.frames.map { it.transportSeq.value })
                // …while the durable seq is the INNER payload.event.seq, falling back to the outer seq for
                // flat rows (host rows, and mux rows that are not a session/event envelope).
                assertEquals(tag + "inner seqs", rows.map { it.second ?: it.first }, batch.frames.map { it.seq.value })

                assertEquals(tag + "epoch", ContractFixture.recordedField(scenario.name, index, "epoch"), batch.epoch)
                assertEquals(
                    tag + "since",
                    ContractFixture.recordedField(scenario.name, index, "since")?.toLong(),
                    batch.since,
                )
                assertEquals(
                    tag + "latestSeq",
                    ContractFixture.recordedField(scenario.name, index, "latestSeq")?.toLong(),
                    batch.latestSeq,
                )
                assertEquals(
                    tag + "reset",
                    ContractFixture.recordedField(scenario.name, index, "reset")?.toBoolean(),
                    batch.reset,
                )
                assertEquals(
                    tag + "truncated",
                    ContractFixture.recordedField(scenario.name, index, "truncated")?.toBoolean(),
                    batch.truncated,
                )
                assertEquals(
                    tag + "waitSupported",
                    ContractFixture.recordedField(scenario.name, index, "waitSupported")?.toBoolean(),
                    batch.waitSupported,
                )
                assertEquals(tag + "channel", step.decodedQuery.getValue("kind"), batch.channel.value)

                val recordedBaseline = ContractFixture.recordedBaselineCount(scenario.name, index)
                if (recordedBaseline < 0) {
                    // No baseline family at all: the actor must NOT sweep on it (do-not-sweep rule).
                    assertNull(tag + "absent baseline stays null", batch.baseline)
                } else {
                    assertEquals(tag + "baseline size", recordedBaseline, batch.baseline?.size)
                }
                checked++
            }
        }
        // poll-baseline-since-zero(2) + approval(1) + question(2) + incremental(1) + truncated-reset(1).
        assertEquals("every recorded poll step must be replayed", 7, checked)
    }

    @Test
    fun approvalPollReplayDerivesThePendingRefFromTheBaselineRow() = runBlocking {
        val scenario = "approval-asked-and-respond-accepted"
        val step = ContractFixture.steps(scenario).first()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val batch = src.poll(pollRequestOf(step))

        // D29: a baseline row is `{rpcId, payload}` with NO outer seq, so its watermark can only come from
        // the inner session seq — a flat row (approval/requested) has none and must read as null.
        assertEquals(2, batch.baseline?.size)
        assertEquals(listOf(null, null), batch.baseline!!.map { it.lastSeq })
        val pending = batch.baseline!!.mapNotNull { it.pendingRef }
        assertEquals(1, pending.size)
        assertEquals(PendingKind.Approval, pending.single().kind)
        assertEquals("{{ID_4}}", pending.single().requestId.value)
        // The approval row is a FLAT mux row: its durable type is the payload type, not session/event.
        val approvalFrame = batch.frames.single { it.type == "approval/requested" }
        assertEquals("{{SESSION_1}}", approvalFrame.sessionId.value)
        // Frames 2..5 are session/event envelopes: outer seq 2/3/4/5 vs inner 11/12/13/14 (D29).
        assertEquals(listOf(11L, 12L, 13L, 14L), batch.frames.filter { it.type.contains("message") }.map { it.seq.value })
    }

    @Test
    fun truncatedResetPollReplayCarriesTheWholeRecordedBuffer() = runBlocking {
        val scenario = "poll-truncated-and-reset"
        val step = ContractFixture.steps(scenario).single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val batch = src.poll(pollRequestOf(step))

        val rows = ContractFixture.recordedEventRows(scenario, 0)
        assertEquals("ring-buffer overran at the recorded cap", 100, rows.size)
        assertEquals(rows.map { it.first }, batch.frames.map { it.transportSeq.value })
        // The buffer rolled: BOTH flags are set and the actor must tombstone→baseline→events→cursor in order
        // (G06). The cursor itself is HybridStream's job and is asserted NOT to advance there.
        assertTrue(batch.reset)
        assertTrue(batch.truncated)
        assertEquals(139L, batch.latestSeq)
        assertEquals(40L, batch.frames.first().transportSeq.value)
    }

    @Test
    fun historyPageReplayKeepsEveryFrameAndContentBlockInOrder() = runBlocking {
        val step = ContractFixture.steps("session-history-page").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val page = src.history(HistoryRequest(SessionId(SESSION_A), beforeSeq = null, maxMessages = 50))

        val value = ContractFixture.responseValue("session-history-page", 0)
        assertEquals("frame count", recordedArraySize(value["events"]), page.frames.size)
        // The recording says hasMore:false; the page must read exactly that, not infer more from a full page.
        assertEquals("false", value["hasMore"].raw())
        assertFalse(page.hasMore)
        // D30: a history row has no outer transport seq — seq == transportSeq == event.seq, so applying a
        // page can never move the poll cursor.
        assertEquals(listOf(1L, 2L), page.frames.map { it.seq.value })
        assertEquals(listOf(1L, 2L), page.frames.map { it.transportSeq.value })
        assertEquals(listOf("user/message", "assistant/message"), page.frames.map { it.type })
        assertEquals(SessionId(SESSION_A), page.sessionId)
        // The assistant row carries TWO content blocks (text + image) in the recorded order; the payload is
        // kept verbatim, never re-encoded (D06).
        val assistant = page.frames[1].payloadJson
        assertTrue("text block dropped: $assistant", assistant.contains("历史回复文本"))
        assertTrue("image block dropped: $assistant", assistant.contains("image/png"))
        assertTrue(
            "content block order changed",
            assistant.indexOf("历史回复文本") < assistant.indexOf("image/png"),
        )
    }

    @Test
    fun dotRpcUnwrapReplaysSessionCreateAndRenameReceipts() = runBlocking {
        val steps = ContractFixture.steps("session-create-rename")

        server.enqueue(MockResponse().setResponseCode(steps[0].status).setBody(steps[0].body))
        val created = transport.rpc("session.create", "{}")
        val createDto = DshJson.spec.decodeFromString(SessionCreateResponse.serializer(), created)
        // The recording's `value.sessionId` must survive the dot-RPC unwrap and the DTO mapping.
        assertEquals(SESSION_C, createDto.createdSessionId()?.value)

        server.enqueue(MockResponse().setResponseCode(steps[1].status).setBody(steps[1].body))
        val renamed = transport.rpc("session.rename", "{}")
        val renameDto = DshJson.spec.decodeFromString(SessionMutationResponse.serializer(), renamed)
        // PROTOCOL SHAPE GAP (reported): the rename answer carries `accepted:true` + `echo`, while
        // SessionMutationResponse models only `ok`/`sessionId`, so a successful rename reads as "no verdict"
        // (Unknown) instead of Accepted. Both sides pinned, so fixing the DTO turns this red on purpose.
        assertTrue("recording says accepted:true", renamed.contains("\"accepted\":true"))
        assertEquals(WriteResult.WriteOutcome.Unknown, renameDto.toDomain().outcome)
    }

    @Test
    fun promptAcceptedReplayIsAnAcceptedWriteResultWithoutReceipt() = runBlocking {
        val step = ContractFixture.steps("prompt-accepted").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val result = src.prompt(PromptRequest(SessionId(SESSION_A), "replay prompt", emptyList()))

        // F5/D13: accepted is not executed; and with no operations capability there is no receipt.
        assertEquals(WriteResult.WriteOutcome.Accepted, result.outcome)
        assertEquals(true, result.accepted)
        assertNull(result.receiptId)
    }

    @Test
    fun respondReplayCollapsesToAcceptedAndCarriesNoReceipt() = runBlocking {
        val step = ContractFixture.steps("approval-asked-and-respond-accepted")[1]
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val result = src.resolve(ResolveRequest(RequestId("{{ID_4}}"), PendingKind.Approval, "allowed-once"))

        assertEquals(WriteResult.WriteOutcome.Accepted, result.outcome)
        assertNull(result.receiptId)
    }

    @Test
    fun operationsReceiptReplayCarriesTheReceiptIdAndSettles() = runBlocking {
        val steps = ContractFixture.steps("prompt-operations-receipt")
        server.enqueue(MockResponse().setResponseCode(steps[0].status).setBody(steps[0].body))

        val submitted = src.submitOperation("session.prompt", "{}")
        val post = server.takeRequest()
        assertEquals("/api/operations", post.requestUrl?.encodedPath)
        assertEquals("POST", post.method)
        assertNotNull("F15: the write must be keyed by an idempotency header", post.getHeader(IDEMPOTENCY_HEADER))
        // The 202 snapshot is merely `running`: a receipt id, no verdict yet (D13).
        assertEquals("{{ID_2}}", submitted.receiptId)
        assertNull(submitted.accepted)
        assertEquals(WriteResult.WriteOutcome.ReceiptPending, submitted.outcome)

        server.enqueue(MockResponse().setResponseCode(steps[1].status).setBody(steps[1].body))
        val receipt = src.operationReceipt(steps[1].decodedQuery.getValue("id"))
        // The settled snapshot nests a dot-RPC envelope under `result.result`; the inner
        // `value.accepted` is the verdict and `id` is the durable receipt.
        assertEquals("{{ID_2}}", receipt.id)
        assertEquals("complete", receipt.phase)
        val settled = receipt.toDomain()
        assertEquals("{{ID_2}}", settled.receiptId)
        assertEquals(true, settled.accepted)
        assertEquals(WriteResult.WriteOutcome.Accepted, settled.outcome)
    }

    @Test
    fun unauthorizedReplayIsTheOnlyAuthClaim_AndNeverFromTransportNoise() = runBlocking {
        val steps = ContractFixture.steps("unauthorized-401")
        steps.forEach { step ->
            server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))
            val failure = runCatching { src.poll(pollRequestOf(step)) }.exceptionOrNull()
            assertTrue("401 must be a RemoteFailure, got $failure", failure is RemoteFailure)
            // G18: 401 is THE device-auth rejection, asserted for BOTH recorded steps (no token, wrong token).
            assertEquals(TransportFailure.Unauthorized, (failure as RemoteFailure).failure)
        }

        // The other half of G18: connection noise must NOT be classified as auth.
        val dead = GatewayConfig(base = "http://127.0.0.1:1", credential = GatewayCredential { TOKEN })
        val deadSource = GatewayV0Source(config = dead, tuning = ContractFixture.testTuning())
        val noise = runCatching { deadSource.poll(PollRequest(ChannelKind.Mux, 0L, "e", 0L)) }.exceptionOrNull()
        val classified = (noise as RemoteFailure).failure
        assertTrue("expected an unreachable failure, got $classified", classified is TransportFailure.Unreachable)
        assertFalse("timeout/TLS/DNS may never claim auth expiry", classified is TransportFailure.Unauthorized)
    }

    @Test
    fun fsListReplayKeepsTheRecordedEntryCountButDropsTypeAndSize_ProtocolShapeGap() = runBlocking {
        val step = ContractFixture.steps("fs-list").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val response = src.fsList(step.decodedQuery.getValue("path"))

        assertEquals("entry count", 6, response.entries.size)
        assertEquals("first name", "nested", response.entries.first().name)
        // PROTOCOL SHAPE GAP (reported): rows are `{name, path, type, size, mtimeMs}` while FsEntryDto reads
        // `directory`/`bytes`/`modifiedAt`, so a listing loses the dir flag, the size and the mtime. The
        // recording carries all three:
        val firstRecorded = ContractFixture.responseJson("fs-list", 0).getValue("entries")
            .jsonArray()[0].jsonObject()
        assertEquals("dir", firstRecorded["type"].raw())
        assertEquals("0", firstRecorded["size"].raw())
        assertFalse("the DTO cannot see the recorded type field", response.entries.first().directory)
    }

    @Test
    fun fsPreviewTooLargeReplayIsAnOutcomeNotATransportFailure() = runBlocking {
        val step = ContractFixture.steps("fs-preview-too-large").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val outcome = src.fsPreview(step.decodedQuery.getValue("path"))

        // F14: 413 is a first-class preview outcome the caller answers by falling back to /fs/file.
        assertTrue(outcome is FsPreviewOutcome.TooLarge)
        // The query carried an absolute path; nothing of it may reach a log line (D16 / G09.2).
        assertFalse(sink.text().contains("large-preview.md"))
    }

    @Test
    fun uploadScenarioReplaysChunkProbeMismatchAndControlInOrder() = runBlocking {
        val steps = ContractFixture.steps("upload-probe-and-control-offset-mismatch")
        val path = steps[0].decodedQuery.getValue("path")
        val name = steps[0].decodedQuery.getValue("name")
        val session = steps[0].decodedQuery.getValue("session")

        // 1. POST /fs/upload at offset 0 — 8 bytes on the octet channel.
        server.enqueue(MockResponse().setResponseCode(steps[0].status).setBody(steps[0].body))
        val chunk = src.uploadChunk(path, name, session, offset = 0L, bytes = ByteArray(8))
        assertEquals(200, chunk.status)
        val chunkRequest = server.takeRequest()
        assertEquals("application/octet-stream", chunkRequest.getHeader("Content-Type"))
        assertEquals(8L, chunkRequest.bodySize)
        assertEquals("0", chunkRequest.requestUrl?.queryParameter("offset"))

        // 2. GET /fs/upload-probe — the resume cursor. PROTOCOL SHAPE GAP (reported): the response carries
        // `partialSize` while UploadProbeResponse reads `uploadedBytes`, so the resume offset reads as 0.
        server.enqueue(MockResponse().setResponseCode(steps[1].status).setBody(steps[1].body))
        val probe = src.uploadProbe(path, name, session)
        val probeRecorded = ContractFixture.responseJson("upload-probe-and-control-offset-mismatch", 1)
        assertEquals("recording says 8 bytes already stored", "8", probeRecorded["partialSize"].raw())
        assertEquals("dto reads uploadedBytes", 0L, probe.uploadedBytes)

        // 3. POST /fs/upload at a stale offset — 409 offset-mismatch must NOT throw: it is resumable (F14).
        server.enqueue(MockResponse().setResponseCode(steps[2].status).setBody(steps[2].body))
        val mismatch = src.uploadChunk(path, name, session, offset = 99L, bytes = ByteArray(8))
        assertEquals(409, mismatch.status)
        assertTrue(mismatch.body.contains("offset-mismatch"))

        // 4. POST /fs/upload-control?action=cancel.
        server.enqueue(MockResponse().setResponseCode(steps[3].status).setBody(steps[3].body))
        val control = src.uploadControl(path, name, session, action = "cancel")
        assertEquals(200, control.status)
        assertEquals("cancel", steps[3].decodedQuery.getValue("action"))
    }

    @Test
    fun healthReplayIsUnauthenticatedAndReadsStatusReadinessAndUpstream() = runBlocking {
        val step = ContractFixture.steps("health-and-capabilities").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val health = src.health()

        // F6: the recorded step carried NO authorization header, and neither may we.
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/health", request.requestUrl?.encodedPath)
        assertNull("health must stay unauthenticated", request.getHeader("Authorization"))
        // The modern body splits liveness (`ok`) from the real verdict (`readiness`), and `upstream`
        // is a URL string rather than the legacy boolean.
        assertTrue(health.ok)
        assertTrue(health.upstreamOk)
        assertEquals("0.8.1", health.gatewayVersion)
        assertEquals(1, src.capabilities.wsTicket)
        assertEquals(1, src.capabilities.operations)
        assertEquals("the recording advertises sixteen capabilities", 16, capabilitiesInRecording())
    }

    @Test
    fun rosterReplayReadsTheModernItemsShapeAndFiltersSubagents() = runBlocking {
        val step = ContractFixture.steps("session-list-roster").single()
        server.enqueue(MockResponse().setResponseCode(step.status).setBody(step.body))

        val roster = src.roster()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/session.list", request.requestUrl?.encodedPath)
        assertEquals("app", request.getHeader("x-dsh-remote-client"))
        // The modern roster is `value.items[]` keyed by `sessionId`, with the title nested at
        // `projections.values.title` and an ISO `updatedAt` placeholder.
        assertEquals("recording carries two items", 2, itemsInRecording())
        assertEquals(listOf("{{SESSION_1}}"), roster.map { it.sessionId.value })
        assertEquals("真实功能测试会话", roster.single().title)
        assertFalse(roster.single().running)
        assertEquals(0L, roster.single().updatedAtMs)
    }

    @Test
    fun muxAndHostSocketPathsAndTicketHandshakeMatchTheChannelSplit() = runBlocking {
        // NOT IN THE RECORDINGS (contract/gateway/v0/README: "v0 scenarios do not request one"): the shape
        // below is the gateway's own /api/ws-ticket + /api/{mux,host} contract, replayed as a documented
        // unrecorded deviation so the socket half of the transport is not a blind spot. A socket is a
        // capability-gated feature, so negotiate a health that advertises wsTicket first (G08/D27).
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"ok":true,"version":"0.8.1","capabilities":{"wsTicket":1,"eventPolling":1}}"""),
        )
        src.health()
        server.takeRequest()

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"ticket":"$TICKET","expiresInMs":90000,"maxUses":4,"ok":true}"""),
        )
        val ticket = src.requestTicket(ChannelKind.Mux)
        val ticketRequest = server.takeRequest()
        assertEquals("POST", ticketRequest.method)
        assertEquals("/api/ws-ticket", ticketRequest.requestUrl?.encodedPath)
        assertEquals(TICKET, ticket.secret)
        assertTrue("ticket request names the channel", ticketRequest.body.readUtf8().contains("\"mux\""))
        // D16: the ticket travels as a handshake header, never as a URL component or a log line.
        assertFalse(ticketRequest.path!!.contains(TICKET))
        assertFalse("the ticket must be registered for redaction", sink.text().contains(TICKET))

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        val socket = transport.openWebSocket("/api/mux", TICKET, object : WebSocketListener() {})
        val handshake = server.takeRequest()
        assertEquals("GET", handshake.method)
        assertEquals("/api/mux", handshake.path)
        assertEquals(TICKET, handshake.getHeader("Sec-WebSocket-Protocol"))
        assertFalse("ticket must not be in the handshake URL", handshake.path!!.contains(TICKET))
        socket.cancel()

        // The two logical channels are two paths, never one shared socket (D09).
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"ticket":"$TICKET-2","expiresInMs":90000,"maxUses":4,"ok":true}"""),
        )
        src.requestTicket(ChannelKind.Host)
        val hostTicketRequest = server.takeRequest()
        assertTrue("ticket is per channel", hostTicketRequest.body.readUtf8().contains("host"))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        val hostSocket = transport.openWebSocket("/api/host", "$TICKET-2", object : WebSocketListener() {})
        assertEquals("/api/host", server.takeRequest().path)
        hostSocket.cancel()
    }

    // ---- helpers ----

    private suspend fun send(src: GatewayV0Source, step: RecordedStep) {
        when (step.barePath) {
            "/health" -> src.health()
            POLL_PATH -> src.poll(pollRequestOf(step))
            "/api/session.list" -> src.roster()
            "/api/session.history" -> src.history(HistoryRequest(SessionId(SESSION_A), null, 50))
            "/api/session.prompt" -> src.prompt(PromptRequest(SessionId(SESSION_A), "replay prompt", emptyList()))
            "/api/respond" -> src.resolve(ResolveRequest(RequestId("{{ID_4}}"), PendingKind.Approval, "allowed-once"))
            "/api/operations" -> if (step.method == "POST") {
                // The receipt id is client-chosen and travels in the query; reuse the recorded one.
                src.submitOperation("session.prompt", "{}", operationId = step.decodedQuery.getValue("id"))
            } else {
                src.operationReceipt(step.decodedQuery.getValue("id"))
            }
            "/fs/list" -> src.fsList(step.decodedQuery.getValue("path"))
            "/fs/preview" -> src.fsPreview(step.decodedQuery.getValue("path"))
            "/fs/upload" -> src.uploadChunk(
                path = step.decodedQuery.getValue("path"),
                name = step.decodedQuery.getValue("name"),
                session = step.decodedQuery.getValue("session"),
                offset = step.decodedQuery.getValue("offset").toLong(),
                bytes = ByteArray(8),
            )
            "/fs/upload-probe" -> src.uploadProbe(
                step.decodedQuery.getValue("path"),
                step.decodedQuery.getValue("name"),
                step.decodedQuery.getValue("session"),
            )
            "/fs/upload-control" -> src.uploadControl(
                step.decodedQuery.getValue("path"),
                step.decodedQuery.getValue("name"),
                step.decodedQuery.getValue("session"),
                step.decodedQuery.getValue("action"),
            )
            else -> transport.rpc(step.barePath.removePrefix("/api/"), "{}")
        }
    }

    private fun pollRequestOf(step: RecordedStep): PollRequest = PollRequest(
        channel = ChannelKind(step.decodedQuery.getValue("kind")),
        since = step.decodedQuery.getValue("since").toLong(),
        epoch = step.decodedQuery["epoch"],
        waitMs = step.decodedQuery.getValue("wait").toLong(),
    )

    private fun assertRequestMatches(step: RecordedStep, tag: String) {
        val sent: RecordedRequest = server.takeRequest()
        assertEquals(tag + "method", step.method, sent.method)
        assertEquals(tag + "path", step.barePath, sent.requestUrl?.encodedPath)

        val expected = step.decodedQuery
        val actual = LinkedHashMap<String, String>()
        (expected.keys + (sent.requestUrl?.queryParameterNames ?: setOf())).forEach { key ->
            actual[key] = sent.requestUrl?.queryParameter(key) ?: "<absent>"
        }
        assertEquals(tag + "query", expected, actual)

        val sentAuth = sent.getHeader("Authorization")
        if (step.carriedAuthorization) {
            assertEquals(tag + "bearer shape", "Bearer $TOKEN", sentAuth)
        } else if (step.barePath == "/health") {
            // F6: the contract says health is unauthenticated.
            assertNull(tag + "health must be unauthenticated", sentAuth)
        } else {
            // The `unauthorized-401` artifact deliberately records a request with NO credential (and one with
            // a wrong one). This client always authenticates, so those negative probes are not reproducible.
            assertNotNull(tag + "authorized probe", sentAuth)
        }
        step.requestHeaders["x-dsh-remote-client"]?.let {
            assertEquals(tag + "client id header", it, sent.getHeader("x-dsh-remote-client"))
        }
        step.requestHeaders["content-type"]?.let {
            // `; charset=utf-8` is a parameter of the media type, not part of it; the recording pins the type.
            assertEquals(tag + "content type", mediaTypeOf(it), mediaTypeOf(sent.getHeader("Content-Type").orEmpty()))
        }
        // D16: the credential is a header, never a URL or body component.
        assertFalse(tag + "token in URL", sent.path!!.contains(TOKEN))
        assertFalse(tag + "token in body", sent.body.readUtf8().contains(TOKEN))
    }

    private fun mediaTypeOf(header: String): String = header.substringBefore(';').trim().lowercase()

    private fun itemsInRecording(): Int =
        ContractFixture.responseValue("session-list-roster", 0).getValue("items").jsonArray().size

    private fun capabilitiesInRecording(): Int =
        ContractFixture.responseJson("health-and-capabilities", 0).getValue("capabilities").jsonObject().size

    private fun recordedArraySize(element: kotlinx.serialization.json.JsonElement?): Int =
        element?.jsonArray()?.size ?: 0

    private fun kotlinx.serialization.json.JsonElement?.jsonArray(): kotlinx.serialization.json.JsonArray =
        this as kotlinx.serialization.json.JsonArray

    private fun kotlinx.serialization.json.JsonElement?.jsonObject(): kotlinx.serialization.json.JsonObject =
        this as kotlinx.serialization.json.JsonObject

    /** The recorded scalar as written, quotes stripped — test-side navigation of the contract only. */
    private fun kotlinx.serialization.json.JsonElement?.raw(): String = this.toString().trim('"')

    private companion object {
        const val POLL_PATH = "/api/events.poll"
        const val IDEMPOTENCY_HEADER = GatewayV0Source.IDEMPOTENCY_HEADER
        const val TOKEN = "replay-token-canary"
        const val TICKET = "replay-ticket-canary"
        const val SESSION_A = "{{SESSION_1}}"
        const val SESSION_C = "{{SESSION_3}}"
    }
}
