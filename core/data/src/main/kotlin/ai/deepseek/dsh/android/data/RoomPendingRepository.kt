package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.database.PendingBadgeRow
import ai.deepseek.dsh.android.database.toModel
import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.PendingStore
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.SystemClock
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.PendingRepository
import ai.deepseek.dsh.android.domain.repository.ResolvePendingResult
import ai.deepseek.dsh.android.domain.repository.domainCall
import ai.deepseek.dsh.android.domain.usecase.ResolvePendingUseCase
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingCounts
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** 计数聚合的可测试投影：不把 Room 的 `PendingBadgeRow` 带进仓储契约。 */
data class PendingBadgeSnapshot(
    val badge: Int,
    val dismissed: Int,
    val pendingKeyCount: Int,
    val lastSweptAtMs: Long?,
)

/**
 * pending 的读侧聚合 + 本地划除原语。`undismiss` 在 [PendingDao] 里没有对应 SQL，
 * 生产适配器用“读整行 → 清 `dismissedAtMs` → putPending”实现，语义仍是本地状态修正，不触碰 status。
 */
interface PendingRoomDao {
    fun observeAll(): Flow<List<PendingRequest>>

    fun observeBadge(): Flow<PendingBadgeSnapshot>

    suspend fun dismiss(kind: PendingKind, requestId: RequestId, nowMs: Long): Int

    suspend fun undismiss(kind: PendingKind, requestId: RequestId): Int

    suspend fun exists(kind: PendingKind, requestId: RequestId): Boolean
}

/**
 * [PendingRepository] 的 Room 实现。
 *
 * 计数完整性只有一个来源：baseline sweep 是否真的执行过。从未 sweep、或待处理 key 数超过
 * `baselineKeySweepSkipThreshold`（D12）时，完整性记 Unknown 且徽标置 null —— 绝不把未知写成 0。
 * 逐行 completeness 只做“取最差”：任何一行 Unknown 都会把整体拉成 Unknown。
 */
class RoomPendingRepository(
    private val serverId: ServerId,
    private val store: PendingStore,
    private val dao: PendingRoomDao,
    /** null 表示当前装配点没有连接层（M4 只读）：resolve 明确报告能力缺失，而不是假装成功。 */
    private val resolveUseCase: ResolvePendingUseCase?,
    private val tuning: SyncTuning = SyncTuning.DEFAULT,
    private val clock: Clock = SystemClock(),
) : PendingRepository {

    override fun groupedBySession(): Flow<Map<SessionId, List<PendingRequest>>> =
        dao.observeAll().map { rows -> rows.groupBy { it.sessionId } }

    override fun forSession(sessionId: SessionId): Flow<List<PendingRequest>> =
        store.observeForSession(serverId, sessionId)

    override fun counts(): Flow<PendingCounts> =
        combine(dao.observeAll(), dao.observeBadge()) { rows, badge ->
            val completeness = completenessOf(rows, badge)
            PendingCounts(
                // Unknown 时徽标必须是 null（D13/G07）：数字会鼓励用户相信一个未确认的集合。
                badge = if (completeness == PendingCompleteness.Unknown) null else badge.badge,
                dismissed = badge.dismissed,
                completeness = completeness,
            )
        }

    override suspend fun dismiss(kind: PendingKind, requestId: RequestId): Result<Unit> = domainCall {
        val changed = dao.dismiss(kind, requestId, clock.wallClockMs())
        // 0 行有两种含义：已经划掉（幂等成功）或根本没有这一行（明确失败），靠存在性区分。
        if (changed == 0 && !dao.exists(kind, requestId)) {
            throw DomainError.Rejected("pending_not_found")
        }
    }

    override suspend fun undismiss(kind: PendingKind, requestId: RequestId): Result<Unit> = domainCall {
        val changed = dao.undismiss(kind, requestId)
        if (changed == 0 && !dao.exists(kind, requestId)) {
            throw DomainError.Rejected("pending_not_found")
        }
    }

    override suspend fun resolve(
        sessionId: SessionId,
        kind: PendingKind,
        requestId: RequestId,
        valueJson: String,
        idempotencyKey: String,
    ): Result<ResolvePendingResult> = domainCall {
        // 远端分支（accepted 三态、超时→baseline 刷新、409 分类）全部由用例决定，仓储不复制。
        val useCase = resolveUseCase
            ?: throw DomainError.UnavailableCapability("pending_resolve")
        useCase(sessionId, kind, requestId, valueJson, idempotencyKey)
    }

    private fun completenessOf(rows: List<PendingRequest>, badge: PendingBadgeSnapshot): PendingCompleteness {
        // 没有 sweep 水位 = 这个集合从未被基线确认过，无论行数多少都只能显示未知。
        if (badge.lastSweptAtMs == null) return PendingCompleteness.Unknown
        // D12：sweep 被跳过时集合不完整，数字不可信。
        if (badge.pendingKeyCount > tuning.baselineKeySweepSkipThreshold) return PendingCompleteness.Unknown
        val perRow = rows.map { it.completeness }
        return when {
            PendingCompleteness.Unknown in perRow -> PendingCompleteness.Unknown
            PendingCompleteness.Stale in perRow -> PendingCompleteness.Stale
            perRow.isNotEmpty() && perRow.all { it == PendingCompleteness.CompleteByServer } ->
                PendingCompleteness.CompleteByServer
            else -> PendingCompleteness.Complete
        }
    }
}

/** [PendingRoomDao] 的 Room 实现。 */
class RoomPendingRoomDao(
    db: DshDatabase,
    serverId: ServerId,
) : PendingRoomDao {

    private val pending = db.pendingDao()
    private val serverIdValue = serverId.value

    override fun observeAll(): Flow<List<PendingRequest>> =
        pending.observePending(serverIdValue).map { rows -> rows.map { it.toModel() } }

    override fun observeBadge(): Flow<PendingBadgeSnapshot> =
        pending.observePendingBadge(serverIdValue).map { row -> row.toSnapshot() }

    override suspend fun dismiss(kind: PendingKind, requestId: RequestId, nowMs: Long): Int =
        pending.dismiss(serverIdValue, kind, requestId, nowMs)

    /**
     * 没有 `undismiss` SQL：读整行后把 `dismissedAtMs` 清空再整行覆盖。
     * 不用 `applyPendingChanges`，因为它的 `COALESCE(excluded, 现有)` 会把 NULL 保留成原值。
     */
    override suspend fun undismiss(kind: PendingKind, requestId: RequestId): Int {
        val row = pending.pending(serverIdValue, kind.name, requestId.value) ?: return 0
        if (row.dismissedAtMs != null) {
            pending.putPending(listOf(row.copy(dismissedAtMs = null)))
        }
        return 1
    }

    override suspend fun exists(kind: PendingKind, requestId: RequestId): Boolean =
        pending.pending(serverIdValue, kind.name, requestId.value) != null

    private fun PendingBadgeRow.toSnapshot(): PendingBadgeSnapshot = PendingBadgeSnapshot(
        badge = badge,
        dismissed = dismissed,
        pendingKeyCount = pendingKeyCount,
        lastSweptAtMs = lastSweptAtMs,
    )
}
