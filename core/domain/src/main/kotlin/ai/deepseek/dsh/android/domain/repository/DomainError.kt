package ai.deepseek.dsh.android.domain.repository

import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.TransportFailure

/**
 * 领域侧唯一的失败类型。UI/feature 只见这个密封层次，不见 `TransportFailure`/`RemoteFailure`。
 * 它是 `Exception` 的子类，因此可以直接放进 `kotlin.Result.failure`，但语义上不是异常控制流，
 * 只是把失败装进 Result 的载体。
 *
 * `HistoryIncomplete`、`Conflict`、`UnavailableCapability` 是纯领域失败，不由传输映射产生：
 * 前者来自投影（D08），后两者来自写操作与能力协商。
 */
sealed class DomainError : Exception() {

    /** 认证过期：只能由观察到的 401/403（凭据被拒）得出，绝不从超时/TLS/DNS/坏 JSON 推出（G18）。 */
    data object AuthExpired : DomainError()

    /** 403：资源/范围被拒。[resource] 为被拒的资源标识（路径或会话 id 等）。 */
    data class Forbidden(val resource: String) : DomainError()

    /** 连不上：网络不可达或 TLS 失败。 */
    data object Unreachable : DomainError()

    /** 超时：一次请求的结局未知，绝不自动重试（D13/accepted!=executed）。 */
    data object Timeout : DomainError()

    /** 历史不可安全重建：存在未登记且 `ignorable!=true` 的事件（D08）。 */
    data object HistoryIncomplete : DomainError()

    /** 写冲突（如 v1 的 409）。 */
    data object Conflict : DomainError()

    /** 请求被明确拒绝（非认证、非超时）。 */
    data class Rejected(val reason: String) : DomainError()

    /** 后端未发布该能力（v0 的 rename/fork/archive 均属此类）。 */
    data class UnavailableCapability(val name: String) : DomainError()
}

/**
 * 纯函数：把传输失败映射为领域失败。这是 feature 层能拿到的唯一错误来源。
 * 认证判定只来自 [TransportFailure.Unauthorized]、401/403；其余失败不得伪装成认证问题（G18）。
 */
fun toDomainError(failure: TransportFailure): DomainError = when (failure) {
    TransportFailure.Unauthorized -> DomainError.AuthExpired
    is TransportFailure.Forbidden -> DomainError.Forbidden(failure.path)
    TransportFailure.Unreachable -> DomainError.Unreachable
    TransportFailure.TlsFailure -> DomainError.Unreachable
    TransportFailure.AuthProbeUnavailable -> DomainError.Unreachable
    TransportFailure.Timeout -> DomainError.Timeout
    is TransportFailure.MalformedPayload -> DomainError.Rejected("malformed_payload:${failure.reason}")
    is TransportFailure.UnexpectedStatus -> statusToDomainError(failure.status, failure.path)
}

/** 一次远程调用失败的顶层入口：解包 [RemoteFailure] 后走传输映射。 */
fun toDomainError(remote: RemoteFailure): DomainError = toDomainError(remote.failure)

/**
 * HTTP 状态码到领域失败的映射。408 视为超时；409 视为冲突；401/403 保留认证语义；
 * 5xx 视为不可达（服务端问题，结局未知）；其余明确拒绝。
 */
fun statusToDomainError(status: Int, resource: String): DomainError = when (status) {
    401 -> DomainError.AuthExpired
    403 -> DomainError.Forbidden(resource)
    408 -> DomainError.Timeout
    409 -> DomainError.Conflict
    in 500..599 -> DomainError.Unreachable
    else -> DomainError.Rejected("http_$status")
}

/** 把任意 Throwable 归一为 [DomainError]：远程失败走映射，已是领域失败则原样返回。 */
fun Throwable.toDomainError(): DomainError = when (this) {
    is DomainError -> this
    is RemoteFailure -> toDomainError(this)
    else -> DomainError.Rejected("internal")
}

/**
 * 把一次可能抛 [RemoteFailure] 的挂起调用收敛为携带 [DomainError] 的 Result。
 * 用例与仓储实现统一经此出口，保证 feature 永远只看到领域失败。
 */
suspend fun <T> domainCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (t: Throwable) {
        Result.failure(t.toDomainError())
    }
