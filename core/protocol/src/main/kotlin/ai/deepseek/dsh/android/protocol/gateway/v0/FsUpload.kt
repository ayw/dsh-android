package ai.deepseek.dsh.android.protocol.gateway.v0

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resumable upload control plane (F14): the real endpoints are `GET /fs/upload-probe`,
 * `POST /fs/upload-control`, `POST /fs/upload-finish` — NOT both-POST as the older plan said. The 4 MiB
 * chunk is a *client* policy (default lives in `SyncTuning`), not a gateway constraint; the gateway only
 * caps the JSON-RPC body (4 MiB) and total (2 GiB).
 */
@Serializable
data class UploadProbeRequest(
    @SerialName("sessionId") val sessionIdStr: String,
    val path: String,
)

@Serializable
data class UploadProbeResponse(
    @SerialName("uploadId") val uploadId: String? = null,
    @SerialName("uploadedBytes") val uploadedBytes: Long = 0L,
    @SerialName("chunkSize") val chunkSize: Long? = null,
    val ok: Boolean? = null,
)

@Serializable
data class UploadControlRequest(
    @SerialName("uploadId") val uploadId: String,
    /** Byte offset the client is writing from; chunk payloads themselves travel on the binary channel. */
    val offset: Long,
    val size: Long,
)

@Serializable
data class UploadControlResponse(
    @SerialName("uploadId") val uploadId: String? = null,
    @SerialName("receivedBytes") val receivedBytes: Long = 0L,
    val ok: Boolean? = null,
)

@Serializable
data class UploadFinishRequest(
    @SerialName("uploadId") val uploadId: String,
    val path: String,
    @SerialName("mediaType") val mediaType: String? = null,
)

@Serializable
data class UploadFinishResponse(
    val ok: Boolean? = null,
    val path: String? = null,
)
