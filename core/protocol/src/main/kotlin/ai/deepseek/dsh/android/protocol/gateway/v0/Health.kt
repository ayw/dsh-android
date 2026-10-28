package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.protocol.DshJson
import ai.deepseek.dsh.android.protocol.int
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `GET /health` (unauthenticated, F6). Publishes the integer capability table used for per-feature
 * negotiation (G08) — never a single version branch. Unknown capability names are preserved in
 * [BackendCapabilities.raw] and surface via `unknown(name)` so the UI hides rather than crashes.
 *
 * Gateway 0.8.1 answers `upstream` as a **URL string** and splits health into `status` +
 * `readiness{ok,upstreamOk,eventsOk}`; the older dialect used a boolean `upstream` and only `ok`.
 * Both are read here, with `readiness` preferred.
 *
 * Capability keys are the fixed names the gateway emits; each defaults to `0` when the field is absent,
 * so an older gateway that omits a capability is read as "not supported" (the safe direction).
 */
@Serializable
data class HealthResponse(
    val ok: Boolean? = null,
    /** `ready | degraded | offline` on current gateways. */
    val status: String? = null,
    @SerialName("version") val gatewayVersion: String? = null,
    /** Current gateways send a URL string; older ones sent a boolean. Kept raw and read tolerantly. */
    @SerialName("upstream") val upstream: JsonElement? = null,
    @SerialName("upstreamOk") val upstreamOk: Boolean? = null,
    @SerialName("upstreamReachable") val upstreamReachable: Boolean? = null,
    @SerialName("readiness") val readiness: HealthReadiness? = null,
    @SerialName("capabilities") val capabilitiesRaw: JsonObject? = null,
) {
    fun toDomain(): BackendHealth = BackendHealth(
        // Current gateways always carry `ok:true` (process liveness); readiness is the real verdict.
        ok = readiness?.ok ?: status?.let { it.equals("ready", ignoreCase = true) } ?: (ok ?: true),
        gatewayVersion = gatewayVersion ?: "",
        upstreamOk = readiness?.upstreamOk ?: upstreamOk ?: legacyUpstreamFlag() ?: (ok ?: false),
        capabilities = capabilitiesRaw.toCapabilities(),
    )

    /** Alternate entry when the network layer holds the capabilities as an un-parsed JSON string. */
    fun toDomain(capsJson: String?): BackendHealth {
        val parsed = capsJson?.let {
            runCatching { DshJson.spec.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }
        return toDomain().copy(capabilities = (capabilitiesRaw ?: parsed).toCapabilities())
    }

    private fun legacyUpstreamFlag(): Boolean? = (upstream as? JsonPrimitive)?.booleanOrNull
}

@Serializable
data class HealthReadiness(
    val ok: Boolean? = null,
    val upstreamOk: Boolean? = null,
    val eventsOk: Boolean? = null,
)

internal fun JsonObject?.toCapabilities(): BackendCapabilities {
    val obj = this ?: EMPTY_JSON
    val raw = LinkedHashMap<String, Int>()
    for (key in obj.keys) raw[key] = obj.int(key) ?: 0
    return BackendCapabilities(
        wsTicket = obj.int("wsTicket") ?: 0,
        eventPolling = obj.int("eventPolling") ?: 0,
        eventEpoch = obj.int("eventEpoch") ?: 0,
        operations = obj.int("operations") ?: 0,
        workspaceFiles = obj.int("workspaceFiles") ?: 0,
        resumableUploads = obj.int("resumableUploads") ?: 0,
        fileInbox = obj.int("fileInbox") ?: 0,
        imagePromptTransport = obj.int("imagePromptTransport") ?: 0,
        raw = raw,
    )
}

private val EMPTY_JSON: JsonObject = JsonObject(emptyMap())
