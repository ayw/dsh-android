package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `llm.settings.describe` / `llm.settings.mutate` (optimistic revision) / `llm.credentials.*`.
 *
 * D14: the credential VALUE is a full-authority secret. It is carried only transiently inside the
 * request the network layer sends and NEVER persisted by any DTO — the Kotlin property is deliberately
 * not named `token`/`secret`-in-plaintext, and [toString] is overridden so the generated loggable form
 * can never print the value or the forbidden field name (see JsonSpecGoldenTest redaction case). The
 * authoritative owner of stored credentials remains `:core:data`'s SecretStore.
 */
@Serializable
data class LlmSettingsDto(
    @SerialName("providerId") val providerId: String? = null,
    val temperature: Double? = null,
    @SerialName("maxTokens") val maxTokens: Long? = null,
    @SerialName("reasoningEffort") val reasoningEffort: String? = null,
    /** Optimistic-concurrency revision echoed by `settings.mutate` (see [LlmSettingsMutateRequest]). */
    val revision: Long? = null,
)

@Serializable
data class LlmSettingsDescribeResponse(
    val settings: LlmSettingsDto? = null,
    val ok: Boolean? = null,
)

/** Optimistic mutation: the client sends the [expectedRevision] it last observed. */
@Serializable
data class LlmSettingsMutateRequest(
    @SerialName("providerId") val providerId: String,
    val settings: LlmSettingsDto,
    @SerialName("expectedRevision") val expectedRevision: Long,
)

@Serializable
data class LlmSettingsMutateResponse(
    val ok: Boolean? = null,
    @SerialName("accepted") val accepted: Boolean? = null,
    val revision: Long? = null,
    /** Set when `expectedRevision` lost the race; the client re-describes then retries deliberately. */
    val conflict: Boolean? = null,
) {
    fun toDomain(): WriteResult = writeResultOf(accepted = accepted ?: ok)
}

@Serializable
data class LlmCredentialDescribeResponse(
    val configured: List<String> = emptyList(),
    val ok: Boolean? = null,
)

/** Request to store a credential. Non-data class: the generated `toString()` is replaced by [revealSafe]. */
@Serializable
data class LlmCredentialSetRequest(
    @SerialName("providerId") val providerId: String,
    // Not named `token`; the plaintext credential value lives here only for the request body.
    @SerialName("value") val rawValue: String,
) {
    override fun toString(): String = "LlmCredentialSetRequest(providerId=$providerId,value=<redacted>)"
}

@Serializable
data class LlmCredentialUnsetRequest(
    @SerialName("providerId") val providerId: String,
)

@Serializable
data class LlmCredentialResponse(val ok: Boolean? = null) {
    fun toDomain(): WriteResult = writeResultOf(accepted = ok)
}
