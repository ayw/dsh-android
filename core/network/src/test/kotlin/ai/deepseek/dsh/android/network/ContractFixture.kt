package ai.deepseek.dsh.android.network

import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.protocol.DshJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File

/**
 * One recorded request/response pair from `contract/gateway/v0/<scenario>.json`, plus the request headers
 * the gateway actually received (so a replay can assert the `Authorization`/client-id surface, not just body).
 */
data class RecordedStep(
    val method: String,
    val path: String,
    val status: Int,
    val body: String,
    val requestHeaders: Map<String, String>,
) {
    /** The path with its query stripped — what the transport puts into a [TransportFailure] (D16). */
    val barePath: String get() = path.substringBefore('?')

    /** The recorded query as `key=value` pairs, in the order the recorder serialized them. */
    val queryPairs: List<Pair<String, String>>
        get() = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map {
            it.substringBefore('=') to it.substringAfter('=', "")
        }

    /** The recorded query, URL-decoded, order-insensitive: what a replay must reproduce exactly. */
    val decodedQuery: Map<String, String>
        get() = queryPairs.associate { (k, v) -> k to java.net.URLDecoder.decode(v, "UTF-8") }

    /** True when the gateway received a bearer on this step (F6: `/health` and the 401 probes do not). */
    val carriedAuthorization: Boolean get() = requestHeaders.keys.any { it.equals("authorization", true) }
}

/** A scenario name == its artifact basename, excluding the manifest. */
data class RecordedScenario(val name: String, val steps: List<RecordedStep>)

/**
 * Loads the REAL gateway 0.8.1 recordings as MockWebServer fixtures. Test-source only, so it may navigate
 * JSON directly (the "no JSON parsing" rule guards the production transport, not the harness that replays
 * recorded bytes). Placeholders (`{{TS_n}}`/`{{EPOCH}}`/`{{SESSION_n}}`) survive verbatim into the response
 * body — the protocol DTOs are tolerant of exactly these (a string `ts` degrades to `0`, D29/D08).
 */
object ContractFixture {
    private val dir: File by lazy {
        var d: File? = File(System.getProperty("user.dir") ?: ".")
        while (d != null) {
            val candidate = File(d, "contract/gateway/v0")
            if (candidate.isDirectory) return@lazy candidate
            d = d.parentFile
        }
        error("contract/gateway/v0 not found starting from ${System.getProperty("user.dir")}")
    }

    /** Every recorded scenario, in the order `manifest.json` lists them (the recorder's SCENARIO_ORDER). */
    fun scenarios(): List<RecordedScenario> {
        val manifest = File(dir, "manifest.json").readText()
        val files = DshJson.spec.parseToJsonElement(manifest).jsonObject.getValue("files").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
            .filter { it.endsWith(".json") && it != "manifest.json" }
            .map { it.removeSuffix(".json") }
        return files.map { RecordedScenario(it, steps(it)) }
    }

    fun steps(scenario: String): List<RecordedStep> {
        val text = File(dir, "$scenario.json").readText()
        val root = DshJson.spec.parseToJsonElement(text).jsonObject
        val recorded = root.getValue("steps").jsonArray
        return recorded.map { step ->
            val o = step.jsonObject
            RecordedStep(
                method = o.getValue("method").jsonPrimitive.content,
                path = o.getValue("path").jsonPrimitive.content,
                status = o.getValue("status").jsonPrimitive.int,
                body = bodyOf(o["response"]),
                requestHeaders = (o["requestHeaders"] as? JsonObject)
                    ?.mapValues { (_, v) -> v.jsonPrimitive.content }
                    .orEmpty(),
            )
        }
    }

    /** The response body as recorded bytes; a JSON `null`/absent body means "no body". */
    private fun bodyOf(response: JsonElement?): String = when (response) {
        null, JsonNull -> ""
        else -> response.toString()
    }

    /** The whole artifact text — used by tests that compare against the recording itself. */
    fun rawText(scenario: String): String = File(dir, "$scenario.json").readText()

    /**
     * The recorded RESPONSE of one step as a navigable element. Production code must never do this —
     * decoding is the `:core:protocol` DTOs' job — but a replay test needs the recording's own numbers
     * (frame counts, outer/inner seqs, envelope flags) so its assertions are derived from the contract
     * rather than from a constant a re-recording would silently invalidate.
     */
    fun responseJson(scenario: String, step: Int): JsonObject =
        DshJson.spec.parseToJsonElement(steps(scenario)[step].body).jsonObject

    /** The dot-RPC `result.value` of a recorded step, or the whole body when it is not envelope-wrapped. */
    fun responseValue(scenario: String, step: Int): JsonObject =
        (responseJson(scenario, step)["result"] as? JsonObject)?.get("value") as? JsonObject
            ?: responseJson(scenario, step)

    /** `events[]` of a recorded poll response as `(outer seq, inner payload.event.seq?)` pairs (D29). */
    fun recordedEventRows(scenario: String, step: Int): List<Pair<Long, Long?>> {
        val events = responseJson(scenario, step)["events"]?.jsonArray ?: JsonArray(emptyList())
        return events.map { element ->
            val row = element.jsonObject
            val outer = row.getValue("seq").jsonPrimitive.long
            val payload = row["event"]?.jsonObject?.get("payload") as? JsonObject
            val inner = if (payload?.stringOrNull("type") == "session/event") {
                (payload["event"] as? JsonObject)?.get("seq")?.jsonPrimitiveOrNull()?.long
            } else {
                null
            }
            outer to inner
        }
    }

    /** `baseline[]` length of a recorded poll response (a baseline row has NO outer seq, D29). */
    fun recordedBaselineCount(scenario: String, step: Int): Int =
        responseJson(scenario, step)["baseline"]?.jsonArray?.size
            ?: -1 // absent baseline family: distinct from an empty one (do-not-sweep rule)

    /** A recorded envelope field read as a string, absent => null (test-side navigation only). */
    fun recordedField(scenario: String, step: Int, key: String): String? =
        (responseJson(scenario, step)[key] as? JsonPrimitive)?.content

    /** Every timing knob is still a [SyncTuning] field (D12); only the PERIODS are shortened. */
    fun fastTuning(): SyncTuning = FAST

    /** The shipped defaults, so an injected threshold always flows from the single tuning source. */
    fun testTuning(): SyncTuning = DEFAULT_PAGE_TUNING

    /** The bearer placeholder the recorder froze into every authorized artifact's request headers. */
    const val TOKEN_PLACEHOLDER: String = "{{TOKEN}}"

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonElement?.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    /**
     * Test sources are exempt from the magic-number gate (G09.3e), which is exactly why the accelerated
     * cadence lives here and never in main: the shipped 25s poll hold and 30s WS probe would make a
     * channel-loop test take minutes.
     */
    private val FAST: SyncTuning = SyncTuning(
        pollWaitMs = 0L,
        pollWaitMsWorker = 0L,
        pollWaitUnsupportedDelayMs = 5L,
        workerRoundBudgetMs = 5_000L,
        backoffInitialMs = 5L,
        backoffMaxMs = 25L,
        wsProbeIntervalMs = 20L,
    )

    private val DEFAULT_PAGE_TUNING = SyncTuning()
}
