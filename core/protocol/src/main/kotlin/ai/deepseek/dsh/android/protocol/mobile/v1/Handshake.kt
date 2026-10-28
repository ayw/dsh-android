package ai.deepseek.dsh.android.protocol.mobile.v1

import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `/mobile/v1/handshake` and `/mobile/v1/pair` (ACCEPTANCE §13).
 *
 * The handshake is where v1 differs from v0's `/health`: besides the integer [capabilities] it advertises
 * `apiVersion`, `minClientVersion` and a `projectionVersion`, which the parity gate (G15) uses to prove
 * v0 and v1 project the same snapshot. [toHealth] folds it back into the transport-neutral [BackendHealth].
 */
@Serializable
data class MobileHandshakeResponse(
    val ok: Boolean? = null,
    @SerialName("apiVersion") val apiVersion: String? = null,
    @SerialName("minClientVersion") val minClientVersion: String? = null,
    @SerialName("projectionVersion") val projectionVersion: String? = null,
    @SerialName("serverVersion") val serverVersion: String? = null,
    val capabilities: JsonObject? = null,
) {
    fun toCapabilities(): BackendCapabilities = this.capabilities.let { caps ->
        val obj = caps ?: JsonObject(emptyMap())
        val raw = LinkedHashMap<String, Int>()
        for (key in obj.keys) raw[key] = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
        BackendCapabilities(
            wsTicket = raw["wsTicket"] ?: 0,
            eventPolling = raw["eventPolling"] ?: 0,
            eventEpoch = raw["eventEpoch"] ?: 0,
            operations = raw["operations"] ?: 0,
            workspaceFiles = raw["workspaceFiles"] ?: 0,
            resumableUploads = raw["resumableUploads"] ?: 0,
            fileInbox = raw["fileInbox"] ?: 0,
            imagePromptTransport = raw["imagePromptTransport"] ?: 0,
            raw = raw,
        )
    }

    fun toHealth(): BackendHealth = BackendHealth(
        ok = ok ?: true,
        gatewayVersion = serverVersion ?: apiVersion ?: "",
        upstreamOk = ok ?: true,
        capabilities = toCapabilities(),
    )
}

/** `/mobile/v1/pair`: exchange a one-time pairing code for a session token. */
@Serializable
data class MobilePairRequest(
    @SerialName("pairingCode") val rawPairingCode: String,
    @SerialName("deviceName") val deviceName: String? = null,
) {
    // Deliberately not a token-named field; the generated form must never print the secret.
    override fun toString(): String = "MobilePairRequest(pairingCode=<redacted>,deviceName=$deviceName)"
}

@Serializable
data class MobilePairResponse(
    val ok: Boolean? = null,
    @SerialName("identityDigest") val identityDigest: String? = null,
) {
    fun toDomain(): WriteResult = if (ok == true) {
        WriteResult(accepted = true, receiptId = identityDigest, outcome = WriteResult.WriteOutcome.Accepted)
    } else {
        WriteResult(accepted = false, receiptId = identityDigest, outcome = WriteResult.WriteOutcome.Rejected)
    }
}
