package ai.deepseek.dsh.android.protocol

import ai.deepseek.dsh.android.model.DurableEvent
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.protocol.gateway.v0.EventBatchDto
import ai.deepseek.dsh.android.protocol.gateway.v0.LlmCredentialSetRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.WsTicketRequest
import ai.deepseek.dsh.android.protocol.gateway.v0.WsTicketResponse
import ai.deepseek.dsh.android.protocol.mobile.v1.MobilePairRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The D06 JSON-spec golden (gate G13 coverage floor; contract-recording feeds the real trace into the
 * :core:network replay suite, which is where MockWebServer lives). This test is pure JVM and touches no
 * transport: it pins the *encoding rules* the whole client depends on.
 *
 * Cases (from the task):
 *  (a) round-trip stability, (b) unknown-field tolerance, (c) missing-vs-null, (d) seq as number not
 *  string (both directions), (e) empty `content` assistant message still decodes, (f) `replay:true` is
 *  transport metadata retained on [ai.deepseek.dsh.android.model.TransportFrame] but NOT on the domain
 *  object, plus the secret-redaction assertion on loggable `toString()`.
 */
class JsonSpecGoldenTest {

    private val json: Json = DshJson.spec

    private fun load(name: String): String =
        javaClass.getResourceAsStream("/json-spec/$name")!!.bufferedReader().use { it.readText() }

    private fun <T> assertRoundTrip(name: String, serializer: KSerializer<T>): T {
        val original = load(name)
        val first = json.decodeFromString(serializer, original)
        val reEncoded = json.encodeToString(serializer, first)
        val second = json.decodeFromString(serializer, reEncoded)
        // (a) decode(encode(decode(x))) == decode(x): the object graph is stable across a byte round trip.
        assertEquals("round-trip unstable for $name", first, second)
        return first
    }

    @Test
    fun `canonical batch round-trips with numeric seq and carries approval identity`() {
        val batch = assertRoundTrip("event_batch.json", EventBatchDto.serializer())

        // (d) seq / since / latestSeq decode as JSON numbers, not strings.
        assertEquals(100L, batch.since)
        assertEquals(142L, batch.latestSeq)
        val domain = batch.toDomain(ai.deepseek.dsh.android.model.ChannelKind.Mux, SessionId("s-1"))
        assertEquals(2, domain.frames.size)
        val approvalFrame = domain.frames[1]
        // D29: the durable seq is the INNER payload.event.seq (142); the outer frame is a mux double
        // envelope, so the marker type "session/event" must NOT leak as the frame type.
        assertEquals(142L, approvalFrame.seq.value)
        assertEquals(142L, approvalFrame.transportSeq.value)
        assertEquals("approval/asked", approvalFrame.type)

        // re-encoded bytes keep seq unquoted (number), never "142".
        val reEncoded = json.encodeToString(EventBatchDto.serializer(), batch)
        assertTrue("seq must encode as a bare number", Regex("\"seq\":142\\b").containsMatchIn(reEncoded))
        assertTrue("since must encode as a bare number", Regex("\"since\":100\\b").containsMatchIn(reEncoded))

        // F8: approval identity is `data.id`, NOT the older `approvalId` — decoded to RequestId("ap-9").
        val durable = DurableEventCodec.decode(approvalFrame)
        assertTrue(durable is DurableEvent.ApprovalAsked)
        durable as DurableEvent.ApprovalAsked
        assertEquals(RequestId("ap-9"), durable.requestId)
        assertEquals("bash", durable.toolName)
        assertEquals("rm", durable.reason)
    }

    @Test
    fun `unknown fields are tolerated and never crash the decode`() {
        val batch = assertRoundTrip("unknown_field.json", EventBatchDto.serializer())
        // (b) the extra top-level and per-frame keys were ignored; the batch still parsed.
        assertEquals(1, batch.events.size)
        val frame = batch.toDomain(ai.deepseek.dsh.android.model.ChannelKind.Mux).frames.single()
        // An unrecognized type does not throw: it becomes Unknown with the wire ignorable marker (here absent → false).
        val durable = DurableEventCodec.decode(frame)
        assertTrue(durable is DurableEvent.Unknown)
        durable as DurableEvent.Unknown
        assertEquals("brand/new-event-kind", durable.type)
        assertFalse(durable.ignorable)
        // ...and the classification table says an unknown, non-ignorable type MUST surface (F16/D08 SURFACE tier).
        assertEquals(IgnoreTier.SURFACE, EventClassification.tierOf(durable.type, durable.ignorable))
    }

    @Test
    fun `missing baseline is distinct from an empty baseline`() {
        val absent = assertRoundTrip("baseline_absent.json", EventBatchDto.serializer())
        val empty = assertRoundTrip("baseline_empty.json", EventBatchDto.serializer())
        // (c) absent ⇒ null (this response carried no baseline family, do NOT sweep); [] ⇒ present-but-empty.
        assertNull("absent baseline must decode to null", absent.baseline)
        assertNotNull("empty baseline must decode to a non-null list", empty.baseline)
        assertEquals(emptyList<Any>(), empty.baseline)

        // An explicit JSON null must also read as null (absent-vs-explicit-null collapse is the D06 intent).
        val explicitNull = json.decodeFromString(EventBatchDto.serializer(), load("baseline_absent.json").let {
            it.replace("\"events\":", "\"baseline\":null,\"events\":")
        })
        assertNull(explicitNull.baseline)
    }

    @Test
    fun `assistant message with empty content still decodes`() {
        val batch = assertRoundTrip("empty_assistant_content.json", EventBatchDto.serializer())
        val frame = batch.toDomain(ai.deepseek.dsh.android.model.ChannelKind.Mux).frames.single()
        val durable = DurableEventCodec.decode(frame)
        assertTrue("empty content must still be a real assistant message", durable is DurableEvent.AssistantMessage)
        durable as DurableEvent.AssistantMessage
        // (e) the empty content array is preserved as an empty parts list — not dropped, not a crash.
        assertTrue(durable.parts.isEmpty())
        assertTrue(durable.interrupted)
    }

    @Test
    fun `a null where a number seq is required fails to decode rather than coercing`() {
        // (d) D06 forbids `coerceInputValues = true`, so a JSON `null` in place of the required numeric
        // `seq` is a contract break — it must throw, not be silently turned into 0.
        val thrown: Throwable? = try {
            json.decodeFromString(EventBatchDto.serializer(), load("seq_as_string.json"))
            null
        } catch (e: Throwable) {
            e
        }
        assertNotNull("a null seq must not silently coerce", thrown)
        assertTrue(
            "expected a SerializationException, got ${thrown!!::class.qualifiedName}: ${thrown.message}",
            thrown is kotlinx.serialization.SerializationException,
        )
    }

    @Test
    fun `replay is transport metadata and never reaches the durable domain object`() {
        val batch = assertRoundTrip("replay_frames.json", EventBatchDto.serializer())
        val frame = batch.toDomain(ai.deepseek.dsh.android.model.ChannelKind.Mux).frames.single()
        // (f) replay:true is retained on the TransportFrame...
        assertTrue("replay must survive onto the transport frame", frame.replay)
        // ...and decoding a copy with replay:false yields an IDENTICAL durable event.
        val flipped = frame.copy(replay = false)
        val a = DurableEventCodec.decode(frame)
        val b = DurableEventCodec.decode(flipped)
        assertEquals("replay must not influence the durable event", a, b)
        // A structural guarantee: the concrete durable variant declares no `replay` field at all.
        val durableFieldNames = a.javaClass.declaredFields.map { it.name }
        assertFalse("durable model must not carry transport metadata `replay`", durableFieldNames.contains("replay"))
    }

    @Test
    fun `known-case table size and the three ignorable tiers`() {
        // 21 durable row types plus the gateway-synthesized approval/question frames the client projects.
        assertEquals(25, EventClassification.PROJECTED_TYPES.size)
        // tool/call and tool/result are the derived sources for QuestionAsked/Resolved.
        assertEquals(IgnoreTier.PROJECTED, EventClassification.tierOf("tool/call", false))
        // A recorded catalog type marked silent is dropped without a counter.
        assertEquals(IgnoreTier.WITHOUT_LOG, EventClassification.tierOf("request/header", true))
        // Unknown but ignorable => count it, do not fail.
        assertEquals(IgnoreTier.WITH_COUNTER, EventClassification.tierOf("plugin:some/thing", true))
        // Unknown and NOT ignorable => unsafe history => surface in diagnostics.
        assertEquals(IgnoreTier.SURFACE, EventClassification.tierOf("plugin:some/thing", false))
    }

    @Test
    fun `secrets never appear in a loggable toString`() {
        val canary = "CANARY-SECRET-DO-NOT-LOG-918273"

        val ticket = WsTicketResponse(secret = canary, expiresInMs = 90_000, maxUses = 4, ok = true)
        val cred = LlmCredentialSetRequest(providerId = "openai", rawValue = canary)
        val pair = MobilePairRequest(rawPairingCode = canary, deviceName = "pixel")
        val ticketReq = WsTicketRequest(channel = "mux")

        for (dto in listOf<Any>(ticket, cred, pair, ticketReq)) {
            val printed = dto.toString()
            // (real assertion) the canary value must not be printed by any generated toString.
            assertFalse("${dto::class.simpleName}.toString leaked the canary secret", printed.contains(canary))
            // nor must any of the four forbidden field names be emitted as an assignment.
            FORBIDDEN_FIELD_ASSIGNMENTS.forEach { pattern ->
                assertFalse(
                    "${dto::class.simpleName}.toString emits forbidden field name: $pattern",
                    pattern.containsMatchIn(printed),
                )
            }
        }

        // The wire still names the ticket field `ticket` (that is required); the redaction is toString-only.
        val encoded = json.encodeToString(WsTicketResponse.serializer(), ticket)
        assertTrue("ticket must still serialize on the wire", encoded.contains("\"ticket\""))
        assertFalse("but the generated toString must redact it", ticket.toString().contains(canary))
    }

    @Test
    fun `no DTO declares a forbidden secret field name as a Kotlin property`() {
        val offenders: List<Any> = listOf(
            WsTicketResponse("x"), LlmCredentialSetRequest("p", "v"), MobilePairRequest("code"),
            WsTicketRequest("mux"),
        )
        offenders.forEach { dto ->
            dto.javaClass.declaredFields.map { it.name }.forEach { name ->
                assertFalse(
                    "${dto.javaClass.simpleName} declares forbidden property name '$name'",
                    FORBIDDEN_PROPERTY_NAMES.any { it.equals(name, ignoreCase = true) },
                )
            }
        }
    }

    private companion object {
        val FORBIDDEN_PROPERTY_NAMES = listOf("token", "authorization", "ticket", "questionText")

        // A field "emitted into toString" looks like `name=value` — that is what we forbid, not the word
        // appearing inside a class name such as `WsTicketResponse`.
        val FORBIDDEN_FIELD_ASSIGNMENTS = FORBIDDEN_PROPERTY_NAMES.map {
            Regex("(?i)(?<![A-Za-z0-9])$it\\s*=")
        }
    }
}
