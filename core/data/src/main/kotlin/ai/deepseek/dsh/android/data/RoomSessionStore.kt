package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.database.SessionEntity
import ai.deepseek.dsh.android.database.toEntity
import ai.deepseek.dsh.android.database.toHeadEntity
import ai.deepseek.dsh.android.database.toPosition
import ai.deepseek.dsh.android.database.toRosterEntry
import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.SessionStore
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `SessionStore` 的 Room 实现：名册（D19 的 keyset 权威）与会话位置。
 *
 * 端口两处形状不足，这里做最小本地补偿（都不改契约、不外溢到 UI）：
 *  1. `rosterPage(limit, offsetRank)` / `observe` 没有 `serverId`，因此 store 携带一个 [defaultServerId]；
 *     多服务器场景由装配点按 server 构造实例（D07：所有表都以 `(serverId, sessionId)` 为键）。
 *  2. `replaceRoster(..., syncedAtMs)` 没有“同步水位”，而 absent 判定需要它：这里按 [GenerationClock]
 *     的规则给每次同步分配严格递增的 rank（rank 是“首次确认到的那一代”，写入后不可变，正是 D19 的要求）。
 */
class RoomSessionStore(
    private val db: DshDatabase,
    @Suppress("unused_parameter") private val clock: Clock,
    private val defaultServerId: ServerId,
) : SessionStore {

    private val sessions get() = db.sessionDao()
    private val heads get() = db.sessionHeadDao()
    private val generationLock = Mutex()
    private val generations = mutableMapOf<ServerId, Long>()

    /** 端口形状的首屏/续页：只有 rank，同 rank 的行会被跳过（DAO 已注明），PagingSource 用完整 keyset。 */
    override fun rosterPage(limit: Int, offsetRank: Long): Flow<List<RosterEntry>> =
        sessions.observeRosterPageAfterRank(defaultServerId.value, offsetRank, limit)
            .map { rows -> rows.map { it.toRosterEntry() } }

    override suspend fun replaceRoster(serverId: ServerId, entries: List<RosterEntry>, syncedAtMs: Long) {
        val watermark = nextRank(serverId)
        val rows = entries.map { entry ->
            entry.toEntity(serverId).copy(rosterRank = if (entry.rosterRank > 0L) entry.rosterRank else watermark)
        }
        val maxRank = rows.maxOfOrNull { it.rosterRank } ?: watermark
        sessions.replaceRoster(serverId.value, rows, syncedAtRank = maxOf(watermark, maxRank), syncedAtMs = syncedAtMs)
        // D11 的名册新鲜度只读 `server.lastSeenAtMs`：同步成功就把水位写下（端口没有这个参数，属于本地必要副作用）。
        db.serverDao().markSyncedAt(serverId.value, syncedAtMs)
    }

    override suspend fun tombstoneAbsent(serverId: ServerId, absentSessionIds: Set<SessionId>): Int =
        sessions.tombstoneAbsent(serverId.value, absentSessionIds.map { it.value }, clock.wallClockMs())

    override suspend fun position(serverId: ServerId, sessionId: SessionId): SessionPosition? =
        heads.head(serverId.value, sessionId.value)?.toPosition()

    /** 前进式写入：端口没有“重置位置回退”的形状，回退只发生在 [RoomDurableStore] 的 reset 批次里。 */
    override suspend fun applyPosition(serverId: ServerId, sessionId: SessionId, position: SessionPosition) {
        heads.upsertSessionHeads(
            listOf(position.toHeadEntity(serverId, sessionId, lastAppliedAtMs = clock.wallClockMs())),
        )
    }

    override fun observe(serverId: ServerId, sessionId: SessionId): Flow<RosterEntry?> =
        sessions.observeSession(serverId.value, sessionId.value).map { it?.toRosterEntry() }

    /** 完整 keyset 的一页（`rosterRank DESC, sessionId DESC`）：PagingSource 与保留策略用。 */
    fun rosterKeysetPage(rank: Long, sessionId: String, limit: Int): Flow<List<RosterEntry>> =
        sessions.observeRosterPage(defaultServerId.value, rank, sessionId, limit).map { rows -> rows.map { it.toRosterEntry() } }

    fun rosterTop(limit: Int): Flow<List<RosterEntry>> =
        sessions.observeRosterTop(defaultServerId.value, limit).map { rows -> rows.map { it.toRosterEntry() } }

    suspend fun rosterRow(serverId: ServerId, sessionId: SessionId): SessionEntity? =
        sessions.session(serverId.value, sessionId.value)

    /**
     * 每次同步一代：`max(已存的 rosterRank / lastSeenRank, 上一次分配) + 1`，进程内记忆、冷启从库里播种。
     * 只有 rank 严格递增，`lastSeenRank < 水位` 才能表达“本次全量没确认到”。
     */
    private suspend fun nextRank(serverId: ServerId): Long = generationLock.withLock {
        generations[serverId]?.let { previous ->
            generations[serverId] = previous + 1L
            return@withLock previous + 1L
        }
        val stored = sessions.observeAllSessions(serverId.value).first()
        val seed = stored.maxOfOrNull { maxOf(it.rosterRank, it.lastSeenRank) } ?: 0L
        val first = seed + 1L
        generations[serverId] = first
        first
    }
}
