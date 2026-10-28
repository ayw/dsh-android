package ai.deepseek.dsh.android.protocol

import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.ContentPart
import ai.deepseek.dsh.android.model.DurableEvent
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.protocol.gateway.v0.EventBatchDto
import ai.deepseek.dsh.android.protocol.gateway.v0.EventFrameDto
import ai.deepseek.dsh.android.protocol.gateway.v0.SessionPageResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * G04 contract replay (DECISIONS.md D29). These tests decode the ACTUAL recorded gateway 0.8.1
 * artifacts under `contract/gateway/v0/` — they never retype a fixture by hand — so a wrong envelope
 * shape fails in CI instead of on a device. They pin:
 *
 *  - the mux **double** envelope unwraps to the INNER durable type + INNER session seq, and the outer
 *    `session/event` marker never leaks as the event type;
 *  - host rows get `seq == transportSeq` (no inner seq);
 *  - baseline rows carry **no** outer `seq`, so `lastSeq` is null (or the inner `payload.event.seq`);
 *  - the approval baseline row yields an approval `pendingRef` and the `ask_user_question` row a
 *    question `pendingRef` (D13/F8/F9);
 *  - a `wait=0` response with `waitSupported=false` and the `truncated`/`reset` flags survive;
 *  - a DTO `toString()` leaks no prompt text, session id, or id.
 */
class PollContractReplayTest {

    /** The outer frame number is the transport cursor; look the decoded frame up by it. */
    private fun List<TransportFrame>.byTransportSeq(want: Long) =
        first { it.transportSeq.value == want }

    private data class Step(val channel: ChannelKind, val batch: EventBatchDto) {
        fun domain(defaultSession: SessionId = SessionId("")) = batch.toDomain(channel, defaultSession)
    }

    private fun stepsOf(scenario: String): List<Step> {
        val root = DshJson.spec.parseToJsonElement(readContract(scenario)) as JsonObject
        val steps = root["steps"] as JsonArray
        return steps.map { step ->
            val response = (step as JsonObject)["response"] as JsonObject
            val batch = DshJson.spec.decodeFromJsonElement(EventBatchDto.serializer(), response)
            Step(if (batch.kind == "host") ChannelKind.Host else ChannelKind.Mux, batch)
        }
    }

    // --- mux double envelope ---

    @Test
    fun `mux double envelope unwraps to the inner durable type and inner session seq`() {
        val mux = stepsOf("poll-baseline-since-zero.json").first { it.channel == ChannelKind.Mux }
        val domain = mux.domain()

        val userMessage = domain.frames.byTransportSeq(2L)
        assertEquals("user/message", userMessage.type)
        assertEquals(EventSeq(11L), userMessage.seq) // INNER seq, not the outer 2
        assertEquals(EventSeq(2L), userMessage.transportSeq) // OUTER transport cursor

        val questionCall = domain.frames.byTransportSeq(7L)
        assertEquals("tool/call", questionCall.type)
        assertEquals(EventSeq(501L), questionCall.seq)
        assertEquals(EventSeq(7L), questionCall.transportSeq)

        val result = domain.frames.byTransportSeq(8L)
        assertEquals(EventSeq(502L), result.seq)
    }

    @Test
    fun `the outer session-event marker never leaks as a frame type`() {
        val mux = stepsOf("poll-baseline-since-zero.json").first { it.channel == ChannelKind.Mux }
        assertTrue(
            "no durable frame may carry the transport marker 'session/event' as its type",
            mux.domain().frames.none { it.type == "session/event" },
        )
    }

    // --- host rows ---

    @Test
    fun `host rows get seq equal to transportSeq`() {
        val host = stepsOf("poll-baseline-since-zero.json").first { it.channel == ChannelKind.Host }
        val frame = host.domain().frames.single()
        assertEquals("host/session-status", frame.type)
        assertEquals(ChannelKind.Host, frame.channel)
        assertEquals(EventSeq(1L), frame.seq)
        assertEquals(EventSeq(1L), frame.transportSeq)
        assertEquals(frame.seq, frame.transportSeq)
        assertEquals(SessionId(SESSION_1), frame.sessionId)
    }

    // --- baseline: no outer seq ---

    @Test
    fun `baseline rows with no seq produce lastSeq null`() {
        val mux = stepsOf("poll-baseline-since-zero.json").first { it.channel == ChannelKind.Mux }
        val baseline = mux.domain().baseline
        assertNotNull("this response carried a baseline family", baseline)
        assertEquals(2, baseline!!.size)
        assertTrue("no baseline row has a seq, so every lastSeq is null", baseline.all { it.lastSeq == null })
    }

    @Test
    fun `approval baseline row produces the approval pendingRef from its identity`() {
        val mux = stepsOf("poll-baseline-since-zero.json").first { it.channel == ChannelKind.Mux }
        val baseline = mux.domain().baseline!!
        // baseline[0] is session/subscribed -> not a pending row.
        assertNull(baseline[0].pendingRef)
        // baseline[1] is the approval asked row.
        val approval = baseline[1].pendingRef
        assertNotNull("the approval baseline row must surface a pendingRef", approval)
        assertEquals(PendingKind.Approval, approval!!.kind)
        // F8: identity is `data.id`; the recorded legacy dialect stores it under `approvalId`.
        assertEquals(REQUEST_ID_APPROVAL, approval.requestId.value)
    }

    @Test
    fun `ask_user_question row produces the question pendingRef from its callId`() {
        val question = stepsOf("question-toolcall-and-result.json").first { it.channel == ChannelKind.Mux }
        val frame = question.domain().frames.single()
        assertEquals("tool/call", frame.type)
        assertEquals(EventSeq(501L), frame.seq)
        val ref = BaselinePendingRef.of(frame)
        assertNotNull(ref)
        assertEquals(PendingKind.Question, ref!!.kind)
        assertEquals(CALL_ID_QUESTION, ref.requestId.value)
    }

    @Test
    fun `approval wire row decodes to a real ApprovalAsked durable`() {
        val mux = stepsOf("approval-asked-and-respond-accepted.json").first { it.channel == ChannelKind.Mux }
        val approvalFrame = mux.domain().frames.byTransportSeq(6L)
        val durable = DurableEventCodec.decode(approvalFrame)
        assertTrue(durable is DurableEvent.ApprovalAsked)
        assertEquals(REQUEST_ID_APPROVAL, (durable as DurableEvent.ApprovalAsked).requestId.value)
    }

    // --- waitSupported=false with wait=0 ---

    @Test
    fun `wait=0 response with waitSupported false decodes faithfully`() {
        val mux = stepsOf("poll-baseline-since-zero.json").first { it.channel == ChannelKind.Mux }
        val batch = mux.batch
        assertFalse("wait=0 means this mode was not held open (D29), so waitSupported is false", batch.waitSupported == true)
        assertEquals(0L, batch.since)
        assertEquals(8L, batch.latestSeq)
        assertEquals(EPOCH, batch.epoch)
        assertEquals("mux", batch.kind)
        assertEquals(true, batch.ok)
        assertFalse(batch.reset)
        assertFalse(batch.truncated)
    }

    // --- truncated / reset flags ---

    @Test
    fun `truncated and reset flags survive`() {
        val reset = stepsOf("poll-truncated-and-reset.json").first { it.channel == ChannelKind.Mux }
        assertTrue("ring buffer overran => truncated", reset.batch.truncated)
        assertTrue("buffer overran past the cursor => reset", reset.batch.reset)
        assertEquals(139L, reset.batch.latestSeq)
        assertEquals(0L, reset.batch.since)
        // The recorded baseline rows still carry no seq.
        assertTrue(reset.domain().baseline!!.all { it.lastSeq == null })
    }

    // --- incremental ---

    @Test
    fun `incremental mux frame keeps the inner session seq distinct from the transport cursor`() {
        val inc = stepsOf("poll-incremental.json").first { it.channel == ChannelKind.Mux }
        val frame = inc.domain().frames.single()
        assertEquals(8L, inc.batch.since)
        assertEquals(9L, inc.batch.latestSeq)
        assertEquals("assistant/message", frame.type)
        assertEquals(EventSeq(601L), frame.seq) // INNER
        assertEquals(EventSeq(9L), frame.transportSeq) // OUTER
    }

    @Test
    fun `assistant body decodes from data content and survives into parts`() {
        val inc = stepsOf("poll-incremental.json").first { it.channel == ChannelKind.Mux }
        val frame = inc.domain().frames.single()
        val assistant = DurableEventCodec.decode(frame)
        assertEquals(
            "the recorded body sits at `data.content`; a `data.message.content` path yields an empty bubble",
            DurableEvent.AssistantMessage(
                seq = EventSeq(601L),
                wallClockMs = frame.wallClockMs,
                parts = listOf(ContentPart.Text("recorder incremental push")),
                usage = null,
                interrupted = false,
                modelId = null,
                finishReason = null,
            ),
            assistant,
        )
    }

    @Test
    fun `history page rows decode through the D30 row shape and keep every content block`() {
        // A `session.page` response is `result.value` = {events:[{event:{type,seq,data}}], hasMore} —
        // no outer transport seq and no rpcId/payload wrapper, so it cannot reuse EventBatchDto (D30).
        val root = DshJson.spec.parseToJsonElement(readContract("session-history-page.json")) as JsonObject
        val steps = root["steps"] as JsonArray
        val value = ((((steps[0] as JsonObject)["response"] as JsonObject)["result"] as JsonObject)["value"] as JsonObject)
        val page = DshJson.spec.decodeFromJsonElement(SessionPageResponse.serializer(), value).toDomain(SessionId("s"))

        assertEquals(false, page.hasMore)
        assertEquals(listOf(1L, 2L), page.frames.map { it.seq.value })
        assertEquals(page.frames.map { it.seq }, page.frames.map { it.transportSeq })

        val user = DurableEventCodec.decode(page.frames[0])
        assertEquals(DurableEvent.UserMessage(EventSeq(1L), 0L, text = "历史用户文本", sourceKind = "user", surfaceOp = null), user)

        val assistant = DurableEventCodec.decode(page.frames[1]) as DurableEvent.AssistantMessage
        assertEquals(2, assistant.parts.size)
        assertEquals(ContentPart.Text("历史回复文本"), assistant.parts[0])
        // `image` has no renderer until the M5/M6 attachment path: it stays an Unsupported block so
        // the timeline shows "未支持的内容块" instead of dropping the message content.
        assertEquals(ContentPart.Unsupported("image"), assistant.parts[1])
    }

    // --- transport-only replay + no durable seq swap on host ---

    @Test
    fun `replay stays transport-only and never lands on a durable event`() {
        val mux = stepsOf("poll-baseline-since-zero.json").first { it.channel == ChannelKind.Mux }
        // No recorded poll frame is a buffer replay, so every frame decodes replay=false.
        assertTrue(mux.domain().frames.all { !it.replay })
        val durableFieldNames = DurableEventCodec
            .decode(mux.domain().frames.byTransportSeq(2L))
            .javaClass.declaredFields.map { it.name }
        assertFalse("durable model must not carry transport metadata `replay`", durableFieldNames.contains("replay"))
    }

    // --- ts wall clock ---

    @Test
    fun `placeholder ts degrades to zero but numeric ts becomes wallClockMs`() {
        val mux = stepsOf("poll-baseline-since-zero.json").first { it.channel == ChannelKind.Mux }
        // In the recordings every ts is normalized to a {{TS_n}} placeholder string (README), so the
        // tolerant reader yields 0 rather than throwing.
        assertTrue(mux.domain().frames.all { it.wallClockMs == 0L })

        // A real numeric ts maps straight through.
        val frame = DshJson.spec.decodeFromString(
            EventFrameDto.serializer(),
            """{"seq":5,"ts":1700000123456,"event":{"rpcId":"r","payload":{"sessionId":"$SESSION_1","type":"host/session-status"}}}""",
        ).toFrame(ChannelKind.Host, SessionId(""))
        assertEquals(1700000123456L, frame.wallClockMs)
        assertEquals(EventSeq(5L), frame.seq)
        assertEquals(EventSeq(5L), frame.transportSeq)
    }

    // --- DTO toString redaction (requirement: no prompt/session/id leakage) ---

    @Test
    fun `DTO toString leaks no prompt text, session id, or frame id`() {
        val root = DshJson.spec.parseToJsonElement(readContract("poll-baseline-since-zero.json")) as JsonObject
        val muxResponse = (((root["steps"] as JsonArray)[0]) as JsonObject)["response"] as JsonObject
        val frameJson = (muxResponse["events"] as JsonArray)[1] as JsonObject
        val frameDto = DshJson.spec.decodeFromJsonElement(EventFrameDto.serializer(), frameJson)

        val printed = frameDto.toString()
        assertFalse("toString must not print prompt text", printed.contains("recorder prompt message one"))
        assertFalse("toString must not print the session id", printed.contains(SESSION_1))
        assertFalse("toString must not print an rpc/call id", printed.contains("{{ID_6}}"))
        assertFalse("toString must not print the payload body", printed.contains("user/message"))
    }

    // --- helpers ---

    private fun readContract(name: String): String = File(contractDir(), name).readText()

    private fun contractDir(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "contract/gateway/v0")
            if (File(candidate, "manifest.json").isFile) return candidate
            dir = dir.parentFile
        }
        error("contract/gateway/v0 not found walking up from ${System.getProperty("user.dir")}")
    }

    private companion object {
        // The stable placeholder identities the recorder froze into every artifact (see
        // contract/gateway/v0/README.md normalization rules).
        const val SESSION_1 = "{{SESSION_1}}"
        const val EPOCH = "{{EPOCH}}"
        const val REQUEST_ID_APPROVAL = "{{ID_4}}"
        const val CALL_ID_QUESTION = "{{ID_11}}"
    }
}
