package ai.deepseek.dsh.android.domain.usecase

import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.PendingStore
import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.RemoteSource
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.ResolveConflictAction
import ai.deepseek.dsh.android.domain.repository.ResolvePendingResult
import ai.deepseek.dsh.android.domain.repository.SyncReason
import ai.deepseek.dsh.android.domain.repository.SyncRequestPort
import ai.deepseek.dsh.android.domain.repository.classifyResolveConflict
import ai.deepseek.dsh.android.domain.repository.toDomainError
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ResolveRequest
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.coroutines.flow.first

/**
 * 解决一条 pending 的写侧交互。分支严格对齐 DECISIONS（D13/F5）与 G07 状态机：
 * - `accepted:true` → 乐观 RESOLVED（落 [PendingStore]，等待确认）；
 * - `accepted:false` → STALE，且**不重试**；
 * - 超时/不可达 → UNKNOWN，并请求一次 baseline 刷新（UNKNOWN 之后由 baseline 重确认回 PENDING）；
 * - 409 三分支（保留给 BFF v1；v0 网关一律 200 accepted:false，[staleRevisionAllowlist] 默认空）：
 *   already-settled→tombstone、命中 allowlist 的 stale-revision→重读、其余→冲突标签保留。
 *
 * 幂等：[applyRemoteConfirmation] 对已 RESOLVED 的行为 no-op，迟到的 resolved 事件不会把它降级。
 */
class ResolvePendingUseCase(
    private val serverId: ServerId,
    private val remote: RemoteSource,
    private val pendingStore: PendingStore,
    private val syncRequestPort: SyncRequestPort,
    private val clock: Clock,
    private val staleRevisionAllowlist: Set<String> = emptySet(),
) {
    suspend operator fun invoke(
        sessionId: SessionId,
        kind: PendingKind,
        requestId: RequestId,
        valueJson: String,
        @Suppress("UNUSED_PARAMETER") idempotencyKey: String,
    ): ResolvePendingResult {
        val result = try {
            remote.resolve(ResolveRequest(requestId, kind, valueJson))
        } catch (remoteFailure: RemoteFailure) {
            return onTransportFailure(sessionId, kind, requestId, toDomainError(remoteFailure))
        }
        return onWriteResult(sessionId, kind, requestId, result)
    }

    private suspend fun onTransportFailure(
        sessionId: SessionId,
        kind: PendingKind,
        requestId: RequestId,
        error: DomainError,
    ): ResolvePendingResult = when (error) {
        DomainError.Timeout, DomainError.Unreachable -> {
            requestBaselineRefresh()
            ResolvePendingResult.Unknown(requestId, error)
        }
        DomainError.Conflict ->
            // v0 端口不带 409 子原因，reason=null：除 already-settled 外一律落 Keep→Conflict 分支。
            applyConflict(sessionId, kind, requestId, reason = null)
        else -> ResolvePendingResult.Unknown(requestId, error)
    }

    private suspend fun onWriteResult(
        sessionId: SessionId,
        kind: PendingKind,
        requestId: RequestId,
        result: WriteResult,
    ): ResolvePendingResult = when {
        result.accepted == true -> confirmResolvedOptimistically(sessionId, kind, requestId)
        result.accepted == false -> markStale(sessionId, kind, requestId)
        result.accepted == null -> {
            requestBaselineRefresh()
            ResolvePendingResult.Unknown(requestId, DomainError.Timeout)
        }
        else -> ResolvePendingResult.Unknown(requestId, DomainError.Rejected("unexpected"))
    }

    /** 供 SyncActor/reducer 在 durable `resolved` 事件到达时调用：只在需要时抬到 RESOLVED，绝不降级。 */
    suspend fun applyRemoteConfirmation(sessionId: SessionId, kind: PendingKind, requestId: RequestId): Boolean {
        val row = findRow(sessionId, kind, requestId) ?: return false
        if (row.status == PendingStatus.Resolved) return true
        pendingStore.upsert(row.copy(status = PendingStatus.Resolved, resolvedAtMs = row.resolvedAtMs ?: clock.wallClockMs()))
        return true
    }

    /**
     * 落地 409 三分支之一。`:core:data` 在 v1 拿到机器可读 [reason] 时调用本方法；
     * v0 因无 409 且 allowlist 为空，[applyConflict] 只会走 Tombstone 或 Keep→Conflict。
     */
    suspend fun applyConflict(
        sessionId: SessionId,
        kind: PendingKind,
        requestId: RequestId,
        reason: String?,
    ): ResolvePendingResult = when (classifyResolveConflict(reason, staleRevisionAllowlist)) {
        ResolveConflictAction.Tombstone -> {
            pendingStore.tombstone(serverId, kind, requestId, clock.wallClockMs())
            ResolvePendingResult.Tombstoned(requestId)
        }
        ResolveConflictAction.ReReadRevision ->
            ResolvePendingResult.ReReadRequired(requestId, reason.orEmpty())
        ResolveConflictAction.KeepWithConflict ->
            ResolvePendingResult.Conflict(requestId, DomainError.Conflict)
    }

    private suspend fun confirmResolvedOptimistically(
        sessionId: SessionId,
        kind: PendingKind,
        requestId: RequestId,
    ): ResolvePendingResult {
        val row = findRow(sessionId, kind, requestId)
        if (row != null && row.status != PendingStatus.Resolved) {
            pendingStore.upsert(row.copy(status = PendingStatus.Resolved, resolvedAtMs = clock.wallClockMs()))
        }
        return ResolvePendingResult.Confirmed(requestId)
    }

    private suspend fun markStale(sessionId: SessionId, kind: PendingKind, requestId: RequestId): ResolvePendingResult {
        val row = findRow(sessionId, kind, requestId)
        if (row != null && row.status != PendingStatus.Resolved) {
            // accepted:false → STALE，无 resolvedAt、不重试。
            pendingStore.upsert(row.copy(status = PendingStatus.Stale, resolvedAtMs = null))
        }
        return ResolvePendingResult.Stale(requestId)
    }

    private suspend fun requestBaselineRefresh() {
        syncRequestPort.requestImmediate(serverId, SyncReason.BaselineRefresh)
    }

    private suspend fun findRow(sessionId: SessionId, kind: PendingKind, requestId: RequestId): PendingRequest? =
        pendingStore.observeForSession(serverId, sessionId).first().firstOrNull {
            it.kind == kind && it.requestId == requestId
        }
}
