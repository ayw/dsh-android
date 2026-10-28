package ai.deepseek.dsh.android.protocol.gateway.v0

import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `session.models` — the model ids selectable for one session (RemoteSource.models → List<String>). */
@Serializable
data class SessionModelsRequest(
    @SerialName("sessionId") val sessionIdStr: String,
)

@Serializable
data class ModelOptionDto(
    val id: String,
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("reasoningEffort") val reasoningEffort: String? = null,
)

@Serializable
data class SessionModelsResponse(
    val models: List<ModelOptionDto> = emptyList(),
    @SerialName("currentModel") val currentModel: String? = null,
    val ok: Boolean? = null,
) {
    fun toDomain(): List<String> = models.map { it.id }
}

/** `session.selectModel` — persist the chosen model; resolved by the following `model/selection` event. */
@Serializable
data class SessionSelectModelRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    @SerialName("modelId") val modelId: String,
)

/** `llm.providers.describe` — provider catalog (no `:core:model` target; consumed by the settings UI). */
@Serializable
data class LlmProviderDto(
    val id: String,
    val name: String = "",
    val baseUrl: String? = null,
    val models: List<String> = emptyList(),
)

@Serializable
data class LlmProvidersDescribeResponse(
    val providers: List<LlmProviderDto> = emptyList(),
    val ok: Boolean? = null,
)
