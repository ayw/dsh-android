package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.HistoryPage
import ai.deepseek.dsh.android.model.HistoryRequest
import ai.deepseek.dsh.android.model.PollRequest
import ai.deepseek.dsh.android.model.PromptRequest
import ai.deepseek.dsh.android.model.ResolveRequest
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.StreamHandle
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.model.WriteResult

/**
 * 后端替换边界。协议变化只影响实现类，UI、数据库、投影不依赖网关 DTO。
 * 每个方法都可能抛 TransportFailure；调用方按 §5.8 的认证与错误规则处理。
 */
interface RemoteSource {
    suspend fun health(): BackendHealth

    /** 全量可见会话名册：唯一的增删权威。 */
    suspend fun roster(): List<RosterEntry>

    suspend fun history(request: HistoryRequest): HistoryPage

    suspend fun poll(request: PollRequest): EventBatch

    suspend fun openStream(
        channel: ChannelKind,
        onFrame: suspend (TransportFrame) -> Unit,
    ): StreamHandle

    suspend fun prompt(request: PromptRequest): WriteResult

    suspend fun resolve(request: ResolveRequest): WriteResult

    suspend fun cancel(sessionId: SessionId): WriteResult

    suspend fun selectModel(sessionId: SessionId, modelId: String): WriteResult

    suspend fun models(sessionId: SessionId): List<String>
}

/** 传输层失败的分类，认证判定不得从超时/TLS/DNS/坏 JSON 得出。 */
sealed interface TransportFailure {
    data object Unauthorized : TransportFailure
    data class Forbidden(val path: String) : TransportFailure
    data object AuthProbeUnavailable : TransportFailure
    data object Unreachable : TransportFailure
    data object Timeout : TransportFailure
    data object TlsFailure : TransportFailure
    data class MalformedPayload(val reason: String) : TransportFailure
    data class UnexpectedStatus(val status: Int, val path: String) : TransportFailure
}

class RemoteFailure(val failure: TransportFailure, cause: Throwable? = null) :
    Exception("remote call failed: $failure", cause)
