package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.protocol.DshJson
import ai.deepseek.dsh.android.protocol.gateway.v0.EventBatchDto
import ai.deepseek.dsh.android.protocol.mobile.v1.MobileEventsResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * Gate G15 — v0 <-> v1 projection parity. The recorded gateway v0 poll body is decoded twice: through the
 * v0 [EventBatchDto] and through the BFF [MobileEventsResponse]. Both reuse the identical D29 frame
 * envelope, so the durable projection they produce must be byte-for-byte equal after normalization.
 *
 * Normalization (ACCEPTANCE §G15): drop transport-only fields (epoch / since / latestSeq / reset /
 * truncated / replay / ok / kind / waitSupported / transportSeq / wallClock), sort object keys, preserve
 * array order, then compare the sha256 of the canonical projection.
 */
class V0V1ProjectionParityTest {

    @Before
    fun setUp() {
        DshLog.sink = RecordingSink()
        DshLog.clearRedactions()
    }

    private fun decodeV0(body: String) =
        DshJson.spec.decodeFromString(EventBatchDto.serializer(), body).toDomain(ChannelKind.Mux)

    private fun decodeV1(body: String) =
        DshJson.spec.decodeFromString(MobileEventsResponse.serializer(), body).toDomain(ChannelKind.Mux)

    @Test
    fun recordedPollProjectsIdenticallyUnderV0AndV1() {
        val body = ContractFixture.steps("poll-incremental").single().body

        val v0 = decodeV0(body)
        val v1 = decodeV1(body)

        assertEquals(digest(v0), digest(v1))
        assertEquals(v0.frames.map { it.type }, v1.frames.map { it.type })
        assertEquals(v0.frames.map { it.seq.value }, v1.frames.map { it.seq.value })
        // Sanity: the projection is non-empty, so a false "equal" on empty inputs cannot pass.
        assertTrue(v0.frames.isNotEmpty())
    }

    @Test
    fun baselineOnlyPollAlsoProjectsIdentically() {
        val body = ContractFixture.steps("approval-asked-and-respond-accepted").first().body

        assertEquals(digest(decodeV0(body)), digest(decodeV1(body)))
    }

    /** Canonical projection string: transport fields dropped, object keys sorted, array order preserved. */
    private fun canonical(batch: EventBatch): String {
        val sb = StringBuilder()
        for (frame in batch.frames) {
            sb.append(frame.channel.value).append('|')
                .append(frame.sessionId.value).append('|')
                .append(frame.seq.value).append('|')
                .append(frame.type).append('|')
                .append(canonicalJson(parsePayload(frame))).append('\n')
        }
        for (baseline in batch.baseline.orEmpty()) {
            sb.append("B|")
                .append(baseline.sessionId.value).append('|')
                .append(baseline.lastSeq?.value ?: -1L).append('|')
                .append(canonicalJson(parse(baseline.payloadJson))).append('\n')
        }
        return sb.toString()
    }

    private fun parsePayload(frame: TransportFrame): JsonElement = parse(frame.payloadJson)

    private fun parse(text: String): JsonElement =
        runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(",", "{", "}") { (k, v) -> "$k=${canonicalJson(v)}" }
        is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }
        is JsonPrimitive -> element.content
    }

    private fun digest(batch: EventBatch): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonical(batch).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
