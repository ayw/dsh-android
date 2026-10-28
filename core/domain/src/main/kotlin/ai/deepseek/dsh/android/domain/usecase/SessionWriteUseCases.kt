package ai.deepseek.dsh.android.domain.usecase

import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.RemoteSource
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.SessionWriteResult
import ai.deepseek.dsh.android.domain.repository.capabilityAllowed
import ai.deepseek.dsh.android.domain.repository.toDomainError
import ai.deepseek.dsh.android.domain.repository.toSessionWriteResult
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.WriteResult

/**
 * v0 `RemoteSource` **只有** prompt/resolve/cancel/selectModel/models 这些写面；
 * rename/fork/archive 在 `:core:domain` 现有端口里没有对应方法（见报告“端口冲突”）。
 * 因此为这三个会话级操作定义一个显式端口，由 `:core:network` 的 v1/BFF 实现；v0 用能力门挡住，
 * 命中不了就返回 [SessionWriteResult.Unavailable]（结构化“没有此功能”，绝不伪造成功）。
 * 注意：**没有 unarchive**——v0 不存在，缺席是结构性的（[SessionWriteSource] 里就没有该方法）。
 */
interface SessionWriteSource {
    suspend fun rename(sessionId: SessionId, title: String): WriteResult

    suspend fun fork(sessionId: SessionId): WriteResult

    /** 归档是工作区级操作：需要 [workspaceId]；v0 无逆操作。 */
    suspend fun archive(sessionId: SessionId, workspaceId: String): WriteResult
}

/** 取消当前 turn：走 `RemoteSource.cancel`；超时/不可达 → Unknown（不重试）。 */
class CancelTurnUseCase(
    private val remote: RemoteSource,
    private val capabilities: () -> BackendCapabilities?,
    @Suppress("UNUSED_PARAMETER") clock: Clock,
) {
    suspend operator fun invoke(sessionId: SessionId): SessionWriteResult =
        try {
            remote.cancel(sessionId)
                .toSessionWriteResult(receiptBacked = capabilities()?.hasReceipts == true)
        } catch (remoteFailure: RemoteFailure) {
            val error = toDomainError(remoteFailure)
            if (error == DomainError.Timeout || error == DomainError.Unreachable) {
                SessionWriteResult.Unknown(error)
            } else {
                SessionWriteResult.Rejected(error)
            }
        }
}

/** 选择模型：走 `RemoteSource.selectModel`；另暴露候选模型列表读取。 */
class SelectModelUseCase(
    private val remote: RemoteSource,
    private val capabilities: () -> BackendCapabilities?,
    @Suppress("UNUSED_PARAMETER") clock: Clock,
) {
    suspend operator fun invoke(sessionId: SessionId, modelId: String): SessionWriteResult =
        try {
            remote.selectModel(sessionId, modelId)
                .toSessionWriteResult(receiptBacked = capabilities()?.hasReceipts == true)
        } catch (remoteFailure: RemoteFailure) {
            val error = toDomainError(remoteFailure)
            if (error == DomainError.Timeout || error == DomainError.Unreachable) {
                SessionWriteResult.Unknown(error)
            } else {
                SessionWriteResult.Rejected(error)
            }
        }

    suspend fun availableModels(sessionId: SessionId): Result<List<String>> =
        try {
            Result.success(remote.models(sessionId))
        } catch (remoteFailure: RemoteFailure) {
            Result.failure(toDomainError(remoteFailure))
        }
}

/** 会话改名能力名。 */
const val CAPABILITY_RENAME: String = "rename"

/** 派生会话能力名。 */
const val CAPABILITY_FORK: String = "forkSession"

/** 归档会话能力名。 */
const val CAPABILITY_ARCHIVE: String = "archiveSession"

/**
 * 改名：能力门 + [SessionWriteSource]。v0 里 rename 未登记在整数 capability 表，且无 source 实现，
 * 直接返回 [SessionWriteResult.Unavailable]；feature 据此隐藏入口（G08：未知能力 ⇒ 隐藏且不崩溃）。
 */
class RenameSessionUseCase(
    private val capabilities: () -> BackendCapabilities?,
    private val writeSource: SessionWriteSource?,
) {
    suspend operator fun invoke(sessionId: SessionId, title: String): SessionWriteResult {
        if (writeSource == null || !capabilityAllowed(capabilities(), CAPABILITY_RENAME)) {
            return SessionWriteResult.Unavailable(CAPABILITY_RENAME)
        }
        return guard { writeSource.rename(sessionId, title) }
    }

    private suspend fun guard(call: suspend () -> WriteResult): SessionWriteResult =
        try {
            call().toSessionWriteResult(receiptBacked = capabilities()?.hasReceipts == true)
        } catch (remoteFailure: RemoteFailure) {
            val error = toDomainError(remoteFailure)
            if (error == DomainError.Timeout || error == DomainError.Unreachable) {
                SessionWriteResult.Unknown(error)
            } else {
                SessionWriteResult.Rejected(error)
            }
        }
}

/**
 * 派生会话：同 rename 的门控。冲突说明：`WriteResult` 不回传新会话 id，
 * v1 需扩展端口才能把新 [SessionId] 交给 UI；当前结果类型无法表达它（详见报告）。
 */
class ForkSessionUseCase(
    private val capabilities: () -> BackendCapabilities?,
    private val writeSource: SessionWriteSource?,
) {
    suspend operator fun invoke(sessionId: SessionId): SessionWriteResult {
        if (writeSource == null || !capabilityAllowed(capabilities(), CAPABILITY_FORK)) {
            return SessionWriteResult.Unavailable(CAPABILITY_FORK)
        }
        return guard { writeSource.fork(sessionId) }
    }

    private suspend fun guard(call: suspend () -> WriteResult): SessionWriteResult =
        try {
            call().toSessionWriteResult(receiptBacked = capabilities()?.hasReceipts == true)
        } catch (remoteFailure: RemoteFailure) {
            val error = toDomainError(remoteFailure)
            if (error == DomainError.Timeout || error == DomainError.Unreachable) {
                SessionWriteResult.Unknown(error)
            } else {
                SessionWriteResult.Rejected(error)
            }
        }
}

/**
 * 归档：工作区级（需要 [SessionId]+`workspaceId`）、能力门控，且 **v0 无 unarchive**
 * （端口里就没有该方法，缺席是结构性的）。
 */
class ArchiveSessionUseCase(
    private val capabilities: () -> BackendCapabilities?,
    private val writeSource: SessionWriteSource?,
) {
    suspend operator fun invoke(sessionId: SessionId, workspaceId: String): SessionWriteResult {
        if (writeSource == null || !capabilityAllowed(capabilities(), CAPABILITY_ARCHIVE)) {
            return SessionWriteResult.Unavailable(CAPABILITY_ARCHIVE)
        }
        return try {
            writeSource.archive(sessionId, workspaceId)
                .toSessionWriteResult(receiptBacked = capabilities()?.hasReceipts == true)
        } catch (remoteFailure: RemoteFailure) {
            val error = toDomainError(remoteFailure)
            if (error == DomainError.Timeout || error == DomainError.Unreachable) {
                SessionWriteResult.Unknown(error)
            } else {
                SessionWriteResult.Rejected(error)
            }
        }
    }
}
