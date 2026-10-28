package ai.deepseek.dsh.android.domain.repository

import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.model.DiagnosticsSnapshot
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.PendingCounts
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TimelineItem
import ai.deepseek.dsh.android.model.TimelineSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * 读侧契约。每个仓储都是**面向单个 server**的实例（由 `:core:data` 按当前连接构造），
 * 因此方法签名以 `SessionId` 为主键、不带 `serverId`；跨服务器聚合是上层职责。
 *
 * 纯度红线（G17）：这里的入参与返回类型都不含传输字段（epoch/since/latestSeq/replay/waitSupported），
 * 也不含 Room/OkHttp/DTO 类型。feature 只依赖本文件的类型。
 */

// ---- 会话分页：自建模 keyset，无 offset、无 androidx.paging ----

/**
 * 会话 keyset 游标：显式 `(updatedAt, sessionId)`，无 offset。`:core:data` 的实现按 `rosterRank` 稳定排序
 * （D19/G12：不能用会被后台同步改写的 updatedAt 决定顺序），此游标只是 feature 侧从上一页尾部取到的续跑键。
 */
data class SessionKeyset(
    val updatedAtMs: Long,
    val sessionId: SessionId,
)

/** 一页会话：[items] 为视图模型，[nextKey] 为 null 表示没有下一页。 */
data class SessionPage(
    val items: List<SessionSummary>,
    val nextKey: SessionKeyset?,
)

/** 会话列表项视图模型：不含传输字段、不含原始 payload。 */
data class SessionSummary(
    val sessionId: SessionId,
    val title: String,
    val updatedAtMs: Long,
    val running: Boolean,
    val archived: Boolean,
    /** D08：存在不可安全重建的未知事件时为 true。 */
    val historyUnsafe: Boolean,
    val workspaceId: String?,
)

/** 名册同步新鲜度（供列表头部/空态文案，非传输态）。 */
enum class RosterSyncState { Unknown, Syncing, Fresh, Stale, Failed }

/**
 * 会话名册读侧 + 强制刷新写侧。分页用 keyset，不用 offset；刷新失败以 [DomainError] 呈现。
 */
interface SessionRepository {
    /** 首页传 null；后续页传上一页的 nextKey。[pageSize] 默认取 `SyncTuning.rosterPageSize`。 */
    fun paged(startAfter: SessionKeyset?, pageSize: Int): Flow<SessionPage>

    fun byId(sessionId: SessionId): Flow<SessionSummary?>

    fun title(sessionId: SessionId): Flow<String?>

    fun running(sessionId: SessionId): Flow<Boolean>

    fun archived(sessionId: SessionId): Flow<Boolean>

    fun historyUnsafe(sessionId: SessionId): Flow<Boolean>

    fun rosterSyncState(): Flow<RosterSyncState>

    /** force=true 无视新鲜度阈值立即重拉；失败返回携带 [DomainError] 的 Result，不抛。 */
    suspend fun refreshRoster(force: Boolean): Result<Unit>
}

// ---- 时间线 ----

/** 向上滚动加载的历史窗口：只含投影后的条目，不外泄原始帧。 */
data class HistoryWindow(
    val sessionId: SessionId,
    val items: List<TimelineItem>,
    /** 下一页的 exclusive 边界；null 表示已到顶。 */
    val nextBeforeSeq: EventSeq?,
    val hasMore: Boolean,
)

/** 时间线连续性状态（G06/G10 的 UI 表达，不含传输游标）。 */
sealed interface TimelineGapState {
    data object FullyCovered : TimelineGapState

    /** seq 空洞：[expectedSeq] 缺失，最早到达的是 [arrivedSeq]。 */
    data class Missing(val expectedSeq: EventSeq, val arrivedSeq: EventSeq) : TimelineGapState

    /** needsResync 未修复/降级：历史可能不完整。 */
    data object HistoryIncomplete : TimelineGapState

    /** D08 硬边界：未知非可忽略事件，历史不可重建。 */
    data class HistoryUnsafe(val unknownType: String) : TimelineGapState
}

interface TimelineRepository {
    /** Room 单一真相：投影快照的连续流。 */
    fun snapshot(sessionId: SessionId): Flow<TimelineSnapshot>

    /** [beforeSeq] exclusive；到顶或失败经 Result 表达。 */
    suspend fun historyPage(sessionId: SessionId, beforeSeq: EventSeq?, maxMessages: Int): Result<HistoryWindow>

    fun gapState(sessionId: SessionId): Flow<TimelineGapState>
}

// ---- 待处理（pending）----

/**
 * pending 读侧与本地划除写侧。resolve（远端写）委派给 `ResolvePendingUseCase`。
 * 完整性可显式为 `unknown`：计数 [PendingCounts.badge] 为 null 或 completeness 为 Unknown 时
 * UI 必须显示“未知”，绝不显示假的 0（D13/G07）。
 */
interface PendingRepository {
    /** 全局，按会话分组（收件箱）。 */
    fun groupedBySession(): Flow<Map<SessionId, List<PendingRequest>>>

    fun forSession(sessionId: SessionId): Flow<List<PendingRequest>>

    fun counts(): Flow<PendingCounts>

    /** 手动划掉：不减 resolvedAt 为空集合、不等于 tombstone。 */
    suspend fun dismiss(kind: PendingKind, requestId: RequestId): Result<Unit>

    suspend fun undismiss(kind: PendingKind, requestId: RequestId): Result<Unit>

    /** 远端解决，委派用例；返回用例的类型化结果。 */
    suspend fun resolve(
        sessionId: SessionId,
        kind: PendingKind,
        requestId: RequestId,
        valueJson: String,
        idempotencyKey: String,
    ): Result<ResolvePendingResult>
}

// ---- 后端/连接 ----

/**
 * 服务器与连接状态的可安全外泄视图。**不含 token/ticket 等任何凭据**，不含传输字段。
 * [storedBase] 是持久化的 base URL（配对时可能已升级为 https）。
 */
data class ServerDescriptor(
    val serverId: ServerId,
    val label: String,
    val storedBase: String,
    val lastKnownAuthState: AuthState,
    val capabilities: BackendCapabilities?,
)

interface BackendRepository {
    fun servers(): Flow<List<ServerDescriptor>>

    fun authState(serverId: ServerId): Flow<AuthState>

    fun capabilities(serverId: ServerId): Flow<BackendCapabilities?>

    /** 立即探活（D11 hostResyncPending 触发）；返回健康快照或映射后的 [DomainError]。 */
    suspend fun probeNow(serverId: ServerId): Result<BackendHealth?>
}

// ---- 诊断 ----

/** 诊断页数据源：直接暴露类型化的 [DiagnosticsSnapshot]（G10/D23），UI 不得自造临时字符串。 */
interface DiagnosticsRepository {
    fun snapshot(): Flow<DiagnosticsSnapshot>
}
