package ai.deepseek.dsh.android.domain.usecase

import ai.deepseek.dsh.android.domain.DurableStore
import ai.deepseek.dsh.android.domain.PendingStore
import ai.deepseek.dsh.android.domain.RemoteSource
import ai.deepseek.dsh.android.domain.repository.SyncReason
import ai.deepseek.dsh.android.domain.repository.SyncRequestPort
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.HistoryPage
import ai.deepseek.dsh.android.model.HistoryRequest
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PollRequest
import ai.deepseek.dsh.android.model.PromptRequest
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ResolveRequest
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.StreamHandle
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.model.WriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf

private val NO_CAPS = BackendCapabilities(0, 0, 0, 0, 0, 0, 0, 0, emptyMap())

fun caps(raw: Map<String, Int> = emptyMap(), operations: Int = 0): BackendCapabilities =
    BackendCapabilities(0, 0, 0, operations, 0, 0, 0, 0, raw)

fun accepted(receipt: String? = null): WriteResult =
    WriteResult(
        accepted = true,
        receiptId = receipt,
        outcome = if (receipt != null) WriteResult.WriteOutcome.ReceiptPending else WriteResult.WriteOutcome.Accepted,
    )

fun rejected(): WriteResult =
    WriteResult(accepted = false, receiptId = null, outcome = WriteResult.WriteOutcome.Rejected)

/** 可脚本化的后端端口：lambda 决定返回体或直接抛；记录调用次数与入参。 */
class FakeRemote(
    val healthFn: () -> BackendHealth = { BackendHealth(true, "0.0.0", true, NO_CAPS) },
    val promptFn: (PromptRequest) -> WriteResult = { error("prompt not stubbed") },
    val resolveFn: (ResolveRequest) -> WriteResult = { error("resolve not stubbed") },
    val cancelFn: (SessionId) -> WriteResult = { _ -> error("cancel not stubbed") },
    val selectModelFn: (SessionId, String) -> WriteResult = { _, _ -> error("selectModel not stubbed") },
    val modelsFn: (SessionId) -> List<String> = { _ -> emptyList() },
) : RemoteSource {
    var promptCount = 0
    var resolveCount = 0
    var cancelCount = 0
    var selectModelCount = 0
    val promptRequests = mutableListOf<PromptRequest>()
    val resolveRequests = mutableListOf<ResolveRequest>()

    override suspend fun health(): BackendHealth = healthFn()
    override suspend fun roster(): List<RosterEntry> = error("roster unused")
    override suspend fun history(request: HistoryRequest): HistoryPage = error("history unused")
    override suspend fun poll(request: PollRequest): EventBatch = error("poll unused")
    override suspend fun openStream(
        channel: ChannelKind,
        onFrame: suspend (TransportFrame) -> Unit,
    ): StreamHandle = error("openStream unused")

    override suspend fun prompt(request: PromptRequest): WriteResult {
        promptCount++
        promptRequests += request
        return promptFn(request)
    }

    override suspend fun resolve(request: ResolveRequest): WriteResult {
        resolveCount++
        resolveRequests += request
        return resolveFn(request)
    }

    override suspend fun cancel(sessionId: SessionId): WriteResult {
        cancelCount++
        return cancelFn(sessionId)
    }

    override suspend fun selectModel(sessionId: SessionId, modelId: String): WriteResult {
        selectModelCount++
        return selectModelFn(sessionId, modelId)
    }

    override suspend fun models(sessionId: SessionId): List<String> = modelsFn(sessionId)
}

/** 记录每次 commit 的批次，用于断言 outbox/tombstone 落盘与顺序。 */
class RecordingDurableStore : DurableStore {
    val batches = mutableListOf<DurableStore.ApplyBatch>()
    override suspend fun commit(batch: DurableStore.ApplyBatch) {
        batches += batch
    }
}

/** 内存 pending 存储：observeForSession 反映当前行；记录 upsert/dismiss/tombstone。 */
class InMemoryPendingStore(initial: List<PendingRequest> = emptyList()) : PendingStore {
    private val state = MutableStateFlow(initial)
    val upserts = mutableListOf<PendingRequest>()
    val dismissCalls = mutableListOf<Triple<PendingKind, RequestId, Long>>()
    val tombstoneCalls = mutableListOf<Triple<PendingKind, RequestId, Long>>()

    fun rows(): List<PendingRequest> = state.value

    override fun observePending(serverId: ServerId): Flow<List<PendingRequest>> =
        state.map { list -> list.filter { it.serverId == serverId } }

    override fun observeForSession(serverId: ServerId, sessionId: SessionId): Flow<List<PendingRequest>> =
        state.map { list -> list.filter { it.serverId == serverId && it.sessionId == sessionId } }

    override suspend fun upsert(request: PendingRequest) {
        upserts += request
        state.value = state.value.filterNot { sameKey(it, request) } + request
    }

    override suspend fun dismiss(serverId: ServerId, kind: PendingKind, requestId: RequestId, nowMs: Long) {
        dismissCalls += Triple(kind, requestId, nowMs)
        state.value = state.value.map {
            if (it.serverId == serverId && it.kind == kind && it.requestId == requestId) {
                it.copy(dismissedAtMs = nowMs)
            } else {
                it
            }
        }
    }

    override suspend fun tombstone(serverId: ServerId, kind: PendingKind, requestId: RequestId, nowMs: Long) {
        tombstoneCalls += Triple(kind, requestId, nowMs)
    }

    override suspend fun sweepAbsent(
        serverId: ServerId,
        presentKeys: Set<DurableStore.PendingKey>,
        nowMs: Long,
    ): Int = 0

    private fun sameKey(a: PendingRequest, b: PendingRequest): Boolean =
        a.serverId == b.serverId && a.kind == b.kind && a.requestId == b.requestId
}

class FakeSyncRequestPort : SyncRequestPort {
    val requests = mutableListOf<Pair<ServerId, SyncReason>>()
    var foregroundActive: Boolean? = null
    override suspend fun requestImmediate(serverId: ServerId, reason: SyncReason): Result<Unit> {
        requests += serverId to reason
        return Result.success(Unit)
    }

    override suspend fun setForeground(active: Boolean): Result<Unit> {
        foregroundActive = active
        return Result.success(Unit)
    }

    override fun isBackgroundSyncEnabled(): Flow<Boolean> = flowOf(true)
}

class RecordingSecretWriter : SecretWriter {
    val writes = mutableListOf<Pair<ConnectionIdentity, String>>()
    override suspend fun write(identity: ConnectionIdentity, token: String) {
        writes += identity to token
    }
}

/** 可脚本化的会话写端口（rename/fork/archive）。 */
class FakeSessionWriteSource(
    val renameFn: (SessionId, String) -> WriteResult = { _, _ -> accepted() },
    val forkFn: (SessionId) -> WriteResult = { accepted() },
    val archiveFn: (SessionId, String) -> WriteResult = { _, _ -> accepted() },
) : SessionWriteSource {
    var renameCount = 0
    var archiveCount = 0
    override suspend fun rename(sessionId: SessionId, title: String): WriteResult {
        renameCount++
        return renameFn(sessionId, title)
    }

    override suspend fun fork(sessionId: SessionId): WriteResult = forkFn(sessionId)

    override suspend fun archive(sessionId: SessionId, workspaceId: String): WriteResult {
        archiveCount++
        return archiveFn(sessionId, workspaceId)
    }
}
