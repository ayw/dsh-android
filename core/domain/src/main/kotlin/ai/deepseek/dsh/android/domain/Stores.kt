package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.NeedsResync
import ai.deepseek.dsh.android.model.NotificationTag
import ai.deepseek.dsh.android.model.OutboxState
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.ProjectionFamily
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.SyncOwnerRole
import ai.deepseek.dsh.android.model.TransportCursor
import ai.deepseek.dsh.android.model.TransportFrame
import kotlinx.coroutines.flow.Flow

/**
 * EventApplier 的唯一写入面：一次调用就是一个事务，顺序固定为
 * raw frame → 投影 → outbox → cursor（cursor 最后）。
 * 纯 JVM 测试用 RecordingDurableStore 断言这个顺序。
 */
interface DurableStore {
    suspend fun commit(batch: ApplyBatch)

    data class ApplyBatch(
        val identity: ConnectionIdentity,
        val serverId: ServerId,
        val frames: List<TransportFrame>,
        val upserts: List<ProjectionUpsert>,
        val pendingChanges: List<PendingRequest>,
        val outboxEntries: List<OutboxEntry>,
        val cursorAfter: TransportCursor?,
        val positionsAfter: Map<SessionId, SessionPosition>,
        val islands: Map<SessionId, List<AppliedIsland>>,
        val needsResync: Map<SessionId, NeedsResync>,
        /** 仅在响应真的携带 baseline 时非空，用于 tombstone absent-from-baseline 集合。 */
        val baselineSweep: BaselineSweep? = null,
        val tombstones: List<PendingKey> = emptyList(),
        val diagnosticsDelta: DiagnosticsDelta = DiagnosticsDelta(),
    )

    data class BaselineSweep(val sessionId: SessionId, val presentKeys: Set<PendingKey>)

    data class ProjectionUpsert(
        val sessionId: SessionId,
        val family: ProjectionFamily,
        val recordKey: String,
        val asOfSeq: EventSeq,
        val payloadJson: String,
    )

    /** pending 主键的领域表示；kind 是枚举而不是网关字符串，协议名留在 `:core:protocol`。 */
    data class PendingKey(val kind: PendingKind, val requestId: RequestId)

    data class OutboxEntry(
        val kind: PendingKind,
        val requestId: RequestId,
        val tag: NotificationTag,
        val state: OutboxState,
    )

    data class DiagnosticsDelta(
        val badJsonCount: Int = 0,
        val wsDowngradeCount: Int = 0,
        val retentionTrimmedCount: Int = 0,
    )
}

interface SyncStateStore {
    suspend fun cursor(identity: ConnectionIdentity, channel: ChannelKind): TransportCursor?

    suspend fun putCursor(cursor: TransportCursor)

    suspend fun clearTransportCursors(identity: ConnectionIdentity)

    suspend fun owner(): SyncOwnerRecord

    /**
     * 跨进程唯一真相：抢不到所有者就不进临界区。`holderId` 是进程/actor 的本地标识，
     * 不是协议字段；CAS 失败返回 false 而不是抛异常，调用方据此映射 Busy。
     */
    suspend fun casOwner(expected: SyncOwnerRole, next: SyncOwnerRole, holderId: String, nowMs: Long): Boolean

    suspend fun setForegroundRequestedAt(nowMs: Long?)

    /** 配对配置变更后登记旧身份；旧 digest 不再出现在配置里才会被 purge。 */
    suspend fun markIdentityRetired(identity: ConnectionIdentity, retiredAtMs: Long)

    /**
     * 清理 `retiredAt + graceMs` 已过期的身份：transport cursor、通知 outbox/tag、auth scope 失败计数。
     * 不删会话投影。必须幂等且可续跑（崩溃后重跑不报错、不重复计），返回被清理的身份。
     */
    suspend fun purgeEligibleIdentities(nowMs: Long, graceMs: Long): List<ConnectionIdentity>
}

data class SyncOwnerRecord(
    val role: SyncOwnerRole,
    val holderId: String?,
    val claimedAtMs: Long,
    /** 前台请求接管的时刻；`null` 表示没有请求。Worker 据此在回合边界交还所有权（G18/D10）。 */
    val foregroundRequestedAtMs: Long? = null,
)

interface SessionStore {
    fun rosterPage(limit: Int, offsetRank: Long): Flow<List<RosterEntry>>

    suspend fun replaceRoster(serverId: ServerId, entries: List<RosterEntry>, syncedAtMs: Long)

    suspend fun tombstoneAbsent(serverId: ServerId, absentSessionIds: Set<SessionId>): Int

    suspend fun position(serverId: ServerId, sessionId: SessionId): SessionPosition?

    suspend fun applyPosition(serverId: ServerId, sessionId: SessionId, position: SessionPosition)

    fun observe(serverId: ServerId, sessionId: SessionId): Flow<RosterEntry?>
}

interface PendingStore {
    fun observePending(serverId: ServerId): Flow<List<PendingRequest>>

    fun observeForSession(serverId: ServerId, sessionId: SessionId): Flow<List<PendingRequest>>

    suspend fun upsert(request: PendingRequest)

    suspend fun dismiss(serverId: ServerId, kind: PendingKind, requestId: RequestId, nowMs: Long)

    suspend fun tombstone(serverId: ServerId, kind: PendingKind, requestId: RequestId, nowMs: Long)

    suspend fun sweepAbsent(serverId: ServerId, presentKeys: Set<DurableStore.PendingKey>, nowMs: Long): Int
}
