package ai.deepseek.dsh.android.protocol.mobile.v1

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The BFF error envelope from ACCEPTANCE §13: `{error:{code,message,retryable,details?}}`.
 *
 * Unlike v0 (which collapses everything to `accepted:true|false`, F5), v1 keeps the 409 three-way split
 * expressible via stable [code]s, so [retryable] and a machine-readable [code] drive the caller's retry
 * decision. `details` is opaque and forwarded verbatim (D06 byte-comparability).
 */
@Serializable
data class MobileErrorBody(
    val code: String = "unknown",
    val message: String = "",
    val retryable: Boolean = false,
    val details: JsonElement? = null,
)

@Serializable
data class MobileErrorEnvelope(
    val error: MobileErrorBody,
) {
    companion object {
        /** Well-known v1 codes the client branches on. STALE covers the v0 `accepted:false` case. */
        const val CODE_STALE: String = "stale"
        const val CODE_UNAUTHORIZED: String = "unauthorized"
        const val CODE_FORBIDDEN: String = "forbidden"
        const val CODE_RETRYABLE: String = "retryable"
    }
}

/** Convenience serial-name alias used across v1 request DTOs for the replay/idempotency key header value. */
internal const val IDEMPOTENCY_KEY_FIELD: String = "idempotencyKey"

@Serializable
internal data class IdempotencyCarrier(
    @SerialName(IDEMPOTENCY_KEY_FIELD) val idempotencyKey: String? = null,
)
