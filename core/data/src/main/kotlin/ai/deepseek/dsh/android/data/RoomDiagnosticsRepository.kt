package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DiagnosticsCounters
import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.database.toCursor
import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.SystemClock
import ai.deepseek.dsh.android.domain.repository.DiagnosticsRepository
import ai.deepseek.dsh.android.domain.repository.PendingRepository
import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.DiagnosticsSnapshot
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TransportCursor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 持久化诊断里的“健康度”计数，语义与 `DiagnosticsAggregateRow` 的对应列一致。 */
data class DiagnosticsAggregate(
    val needsResyncCount: Int,
    val historyIncompleteSessions: Int,
    val historyUnsafeSessions: Int,
)

/** 累计诊断计数器；`baselineSweepSkipped` 是覆盖式布尔，其余是累计值。 */
data class DiagnosticsCountersSnapshot(
    val badJsonCount: Int,
    val wsDowngradeCount: Int,
    val retentionTrimmedCount: Int,
    val baselineSweepSkipped: Boolean,
)

/**
 * 诊断快照的 Room 侧数据源。拆成窄接口是为了让仓储测试保持纯 JVM：
 * 生产实现才接触 Room/DAO，测试只需内存 Flow。
 */
interface DiagnosticsSource {
    fun observeAggregate(): Flow<DiagnosticsAggregate>

    fun observeCounters(): Flow<DiagnosticsCountersSnapshot>

    fun observeCursors(): Flow<Map<ChannelKind, TransportCursor>>

    /** 每会话 `appliedMaxSeq - contiguousSeq`（只含有洞的会话）。 */
    fun observeContiguousLag(): Flow<Map<SessionId, Long>>

    fun observeRosterAgeMs(): Flow<Long?>

    fun observeIdentityRetiredPendingPurge(): Flow<Int>
}

/**
 * [DiagnosticsRepository] 的组合实现。**每个字段都来自真实来源**：
 *  - 持久计数/健康计数 ← [DiagnosticsSource]（`diagnostics_counters` 与跨表聚合）；
 *  - 传输游标 ← `sync_state`（按当前身份的摘要）；
 *  - pending 完整性 ← [PendingRepository.counts]（Unknown 原样透传）；
 *  - 认证态、能力 ← 注入的连接层 Flow；
 *  - `lastPollWaitSupported` 没有持久来源，默认 null（诚实未知），由装配点按连接层覆盖。
 *
 * 模型没有 sync owner 字段，因此这里不把 owner 角色硬塞进任何一格；`degraded` 也只由
 * `DiagnosticsSnapshot` 自己的谓词计算，本类不复制规则。
 */
class RoomDiagnosticsRepository(
    private val source: DiagnosticsSource,
    private val pending: PendingRepository,
    private val authState: Flow<AuthState>,
    private val capabilities: Flow<BackendCapabilities?>,
    private val lastPollWaitSupported: Flow<Boolean?> = flowOf(null),
) : DiagnosticsRepository {

    override fun snapshot(): Flow<DiagnosticsSnapshot> {
        val storage = combine(
            source.observeAggregate(),
            source.observeCursors(),
            source.observeContiguousLag(),
        ) { aggregate, cursors, lag -> Storage(aggregate, cursors, lag) }
        val runtime = combine(
            pending.counts(),
            authState,
            capabilities,
            lastPollWaitSupported,
        ) { counts, auth, caps, wait -> Runtime(counts.completeness, auth, caps, wait) }

        return combine(
            storage,
            source.observeCounters(),
            source.observeRosterAgeMs(),
            source.observeIdentityRetiredPendingPurge(),
            runtime,
        ) { persist, counters, rosterAgeMs, retiredPending, live ->
            DiagnosticsSnapshot(
                transportCursorPerChannel = persist.cursors,
                contiguousSeqLag = persist.lag,
                needsResyncCount = persist.aggregate.needsResyncCount,
                historyIncompleteSessions = persist.aggregate.historyIncompleteSessions,
                historyUnsafeSessions = persist.aggregate.historyUnsafeSessions,
                pendingCompleteness = live.completeness,
                baselineSweepSkipped = counters.baselineSweepSkipped,
                authState = live.auth,
                lastPollWaitSupported = live.waitSupported,
                retentionTrimmedCount = counters.retentionTrimmedCount,
                badJsonCount = counters.badJsonCount,
                wsDowngradeCount = counters.wsDowngradeCount,
                identityRetiredPendingPurge = retiredPending,
                rosterAgeMs = rosterAgeMs,
                capabilities = live.capabilities,
            )
        }
    }

    private data class Storage(
        val aggregate: DiagnosticsAggregate,
        val cursors: Map<ChannelKind, TransportCursor>,
        val lag: Map<SessionId, Long>,
    )

    private data class Runtime(
        val completeness: PendingCompleteness,
        val auth: AuthState,
        val capabilities: BackendCapabilities?,
        val waitSupported: Boolean?,
    )
}

/** [DiagnosticsSource] 的 Room 实现：把多条 DAO 语句收敛成诊断快照所需的窄 Flow。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomDiagnosticsSource(
    db: DshDatabase,
    serverId: ServerId,
    private val clock: Clock = SystemClock(),
    private val identityGraceMs: Long = SyncTuning.DEFAULT.identityPurgeAfterMs,
) : DiagnosticsSource {

    private val diagnostics = db.diagnosticsDao()
    private val servers = db.serverDao()
    private val cursors = db.syncStateDao()
    private val sessions = db.sessionDao()
    private val heads = db.sessionHeadDao()
    private val identities = db.identityDao()
    private val serverIdValue = serverId.value

    override fun observeAggregate(): Flow<DiagnosticsAggregate> =
        diagnostics.observeDiagnostics().map { row ->
            DiagnosticsAggregate(
                needsResyncCount = row.needsResyncCount,
                historyIncompleteSessions = row.historyIncompleteSessions,
                historyUnsafeSessions = row.historyUnsafeSessions,
            )
        }

    override fun observeCounters(): Flow<DiagnosticsCountersSnapshot> =
        diagnostics.observeCounters().map { rows ->
            val byName = rows.associate { it.name to it.value }
            DiagnosticsCountersSnapshot(
                badJsonCount = (byName[DiagnosticsCounters.BAD_JSON] ?: 0L).toCount(),
                wsDowngradeCount = (byName[DiagnosticsCounters.WS_DOWNGRADE] ?: 0L).toCount(),
                retentionTrimmedCount = (byName[DiagnosticsCounters.RETENTION_TRIMMED] ?: 0L).toCount(),
                baselineSweepSkipped = (byName[DiagnosticsCounters.BASELINE_SWEEP_SKIPPED] ?: 0L) > 0L,
            )
        }

    /**
     * 游标按“当前身份的摘要”读；身份轮换后旧摘要的游标不再属于本 server，因此不展示。
     * 这也符合 D14：旧身份的传输态只留在退役账本窗口里归因。
     */
    override fun observeCursors(): Flow<Map<ChannelKind, TransportCursor>> =
        servers.observeServer(serverIdValue).flatMapLatest { server ->
            val identityHash = server?.identityHash
            if (identityHash == null) {
                flowOf(emptyMap())
            } else {
                cursors.observeCursors(identityHash).map { rows ->
                    rows.mapNotNull { it.toCursor() }.associateBy { it.channel }
                }
            }
        }

    /**
     * `session_head` 没有“全表有洞会话”的 Flow，只有按 id 批读；先观察活跃会话再批量读 head。
     * 空列表必须短路：Room 不会为空的 `IN ()` 生成有意义的查询。
     */
    override fun observeContiguousLag(): Flow<Map<SessionId, Long>> =
        sessions.observeAllSessions(serverIdValue).flatMapLatest { rows ->
            val ids = rows.map { it.sessionId.value }
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                heads.observeHeadsFor(serverIdValue, ids).map { headRows ->
                    headRows.mapNotNull { head ->
                        val contiguous = head.contiguousSeq
                        val applied = head.appliedMaxSeq
                        if (contiguous != null && applied != null && applied.value > contiguous.value) {
                            head.sessionId to (applied.value - contiguous.value)
                        } else {
                            null
                        }
                    }.toMap()
                }
            }
        }

    override fun observeRosterAgeMs(): Flow<Long?> =
        servers.observeServer(serverIdValue).map { server ->
            val lastSeenAtMs = server?.lastSeenAtMs?.takeIf { it > 0L } ?: return@map null
            (clock.wallClockMs() - lastSeenAtMs).coerceAtLeast(0L)
        }

    /** 仍在保留窗口内、还没被清理的退役身份数（A→B→A 保护由清理语句负责，这里只报窗口内的行数）。 */
    override fun observeIdentityRetiredPendingPurge(): Flow<Int> =
        identities.observeRetired().map { rows ->
            val nowMs = clock.wallClockMs()
            rows.count { row -> row.retiredAtMs + identityGraceMs > nowMs }
        }

    private fun Long.toCount(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
