package ai.deepseek.dsh.android.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 会话名册（`session`）：写入、keyset 分页、absent/tombstone 与过期清理。
 *
 * 分页只有 keyset，没有 OFFSET 分页；`rosterRank` 由引擎侧按 `SyncTuning` 生成，本模块只比较它。
 * 所有查询都带 `serverId`（D07：每张表都以 (serverId, sessionId) 为键）。
 */
@Dao
interface SessionDao {

    // ---- 写 ----

    /**
     * 名册行的幂等 upsert。`ON CONFLICT` 目标就是复合主键 `(serverId, sessionId)`，并且
     * **故意不更新 rosterRank**：它是分页的稳定锚点（D19），改写它会让“不跳项、不重复”失效 ——
     * 新行写入 [rosterRank]（首次出现时的 rank），已有行永远保持它首次插入的那个 rank。
     * `lastSeenRank` 记录“最近一次全量同步确认到它”，用本次同步的水位 [lastSeenRank] 覆盖（取 MAX，
     * 因此更旧的同步不会把它拉回去）。更低的 [rosterRank] 视为陈旧写入。
     *
     * 更低的 [rosterRank] 被 SQL 守卫拒绝成 0 行 no-op，不是 Kotlin 侧判断。
     */
    @Query(
        """
        INSERT INTO session (serverId, sessionId, title, updatedAtMs, running, archived, workspaceId,
                             rosterRank, lastSeenRank, tombstonedAtMs)
        VALUES (:serverId, :sessionId, :title, :updatedAtMs, :running, :archived, :workspaceId,
                :rosterRank, :lastSeenRank, NULL)
        ON CONFLICT(serverId, sessionId) DO UPDATE SET
            title = excluded.title,
            updatedAtMs = excluded.updatedAtMs,
            running = excluded.running,
            archived = excluded.archived,
            workspaceId = excluded.workspaceId,
            lastSeenRank = MAX(excluded.lastSeenRank, session.lastSeenRank),
            tombstonedAtMs = NULL
        WHERE excluded.rosterRank >= session.rosterRank
        """,
    )
    suspend fun upsertRosterRow(
        serverId: String,
        sessionId: String,
        title: String,
        updatedAtMs: Long,
        running: Boolean,
        archived: Boolean,
        workspaceId: String?,
        rosterRank: Long,
        lastSeenRank: Long,
    )

    /**
     * 全量名册替换（一次同步 = 一个事务）。三类行由 SQL 区分：
     *  - insert：冲突目标未命中，`rosterRank` 首次写入（用入参 [SessionEntity.rosterRank]）；
     *  - update：冲突命中且 rank 守卫放行，`rosterRank` 保持**首次插入时的值**不变（不在 SET 列表里）；
     *  - absent：`lastSeenRank < syncedAtRank`，本次全量没确认到的存量行 → tombstone 候选。
     *
     * [syncedAtRank] 是**本次同步的水位**（引擎给的单调序号，通常等于本批 entry rank 的最大值）：
     * 它只写进 `lastSeenRank` 与 absent 判定，不参与 `rosterRank` 的比较，因此
     * “引擎按会话给 rank”与“引擎按批次给同一个 rank”两种约定都能正确工作。
     * 空名册也必须调用（[syncedAtRank] 照给），否则无法把“全部消失”表达为 absent。
     *
     * [RosterSyncResult.inserted] 用事务前后的行数差算，不猜 rank 的分配约定。
     */
    @Transaction
    suspend fun replaceRoster(
        serverId: String,
        entries: List<SessionEntity>,
        syncedAtRank: Long,
        syncedAtMs: Long,
    ): RosterSyncResult {
        val before = countRows(serverId).total
        entries.forEach { entry ->
            upsertRosterRow(
                serverId = serverId,
                sessionId = entry.sessionId.value,
                title = entry.title,
                updatedAtMs = entry.updatedAtMs,
                running = entry.running,
                archived = entry.archived,
                workspaceId = entry.workspaceId,
                rosterRank = entry.rosterRank,
                lastSeenRank = syncedAtRank,
            )
        }
        val inserted = countRows(serverId).total - before
        val tombstoned = markAbsent(serverId, syncedAtRank, syncedAtMs)
        return RosterSyncResult(incoming = entries.size, inserted = inserted, tombstoned = tombstoned)
    }

    /** absent 判定：比较 `lastSeenRank` 与本次同步 rank（D19 的 rosterRank 驱动），不依赖 updatedAt。 */
    @Query(
        """
        UPDATE session SET tombstonedAtMs = :nowMs
        WHERE serverId = :serverId AND lastSeenRank < :syncedAtRank AND tombstonedAtMs IS NULL
        """,
    )
    suspend fun markAbsent(serverId: String, syncedAtRank: Long, nowMs: Long): Int

    /** baseline / `host/session-removed` 明确点名的 absent。 */
    @Query(
        """
        UPDATE session SET tombstonedAtMs = :nowMs
        WHERE serverId = :serverId AND tombstonedAtMs IS NULL AND sessionId IN (:sessionIds)
        """,
    )
    suspend fun tombstoneAbsent(serverId: String, sessionIds: List<String>, nowMs: Long): Int

    @Query("SELECT sessionId FROM session WHERE serverId = :serverId AND lastSeenRank < :syncedAtRank")
    suspend fun absentSessionIds(serverId: String, syncedAtRank: Long): List<String>

    /** 无条件整行替换：状态行（host/session-status）只能更新已存在会话，不新建名册行时用它。 */
    @Upsert
    suspend fun upsertSessions(sessions: List<SessionEntity>)

    // ---- 过期清理（tombstone TTL） ----

    /**
     * 删除已过 TTL 的 tombstone 会话及其投影行（events / head / islands）。
     * 先删子表、后删名册行：中途崩溃时名册行仍在，下一次运行重新执行同一组语句即可续跑（幂等）。
     */
    @Transaction
    suspend fun purgeTombstonedSessions(nowMs: Long, ttlMs: Long): Int {
        if (selectTombstonedKeys(nowMs, ttlMs).isEmpty()) return 0
        purgeSessionEvents(nowMs, ttlMs)
        purgeSessionHeads(nowMs, ttlMs)
        purgeSessionIslands(nowMs, ttlMs)
        return deleteTombstonedSessions(nowMs, ttlMs)
    }

    @Query(
        """
        SELECT serverId, sessionId FROM session
        WHERE tombstonedAtMs IS NOT NULL AND tombstonedAtMs + :ttlMs <= :nowMs
        """,
    )
    suspend fun selectTombstonedKeys(nowMs: Long, ttlMs: Long): List<SessionKeyRow>

    @Query(
        """
        DELETE FROM session_event WHERE EXISTS (
            SELECT 1 FROM session AS s WHERE s.serverId = session_event.serverId
                AND s.sessionId = session_event.sessionId
                AND s.tombstonedAtMs IS NOT NULL AND s.tombstonedAtMs + :ttlMs <= :nowMs
        )
        """,
    )
    suspend fun purgeSessionEvents(nowMs: Long, ttlMs: Long): Int

    @Query(
        """
        DELETE FROM session_head WHERE EXISTS (
            SELECT 1 FROM session AS s WHERE s.serverId = session_head.serverId
                AND s.sessionId = session_head.sessionId
                AND s.tombstonedAtMs IS NOT NULL AND s.tombstonedAtMs + :ttlMs <= :nowMs
        )
        """,
    )
    suspend fun purgeSessionHeads(nowMs: Long, ttlMs: Long): Int

    @Query(
        """
        DELETE FROM applied_island WHERE EXISTS (
            SELECT 1 FROM session AS s WHERE s.serverId = applied_island.serverId
                AND s.sessionId = applied_island.sessionId
                AND s.tombstonedAtMs IS NOT NULL AND s.tombstonedAtMs + :ttlMs <= :nowMs
        )
        """,
    )
    suspend fun purgeSessionIslands(nowMs: Long, ttlMs: Long): Int

    @Query("DELETE FROM session WHERE tombstonedAtMs IS NOT NULL AND tombstonedAtMs + :ttlMs <= :nowMs")
    suspend fun deleteTombstonedSessions(nowMs: Long, ttlMs: Long): Int

    // ---- 分页读（keyset） ----

    /**
     * D19 规定的主分页游标：`rosterRank DESC, sessionId DESC`。rank 不可变，因此后台同步改写
     * `updatedAtMs` 不会造成跳项或重复（G12）。首页用 [observeRosterTop]，续页把上一页最后一行的
     * `(rosterRank, sessionId)` 原样传回来。
     */
    @Query(
        """
        SELECT * FROM session
        WHERE serverId = :serverId
          AND tombstonedAtMs IS NULL
          AND (rosterRank < :rank OR (rosterRank = :rank AND sessionId < :sessionId))
        ORDER BY rosterRank DESC, sessionId DESC
        LIMIT :limit
        """,
    )
    fun observeRosterPage(serverId: String, rank: Long, sessionId: String, limit: Int): Flow<List<SessionEntity>>

    /**
     * 只有 rank 的 keyset（`SessionStore.rosterPage(limit, offsetRank)` 的形状，端口没有破平列）。
     * **同一批 rank 相等的行会被跳过**：端口签名无法表达 `(rank, sessionId)` 游标，
     * 所以 `:core:data` 实现该端口时应优先用 [observeRosterPage]，只在必须逐字对齐端口时用这里。
     */
    @Query(
        """
        SELECT * FROM session
        WHERE serverId = :serverId AND tombstonedAtMs IS NULL AND rosterRank < :offsetRank
        ORDER BY rosterRank DESC, sessionId DESC
        LIMIT :limit
        """,
    )
    fun observeRosterPageAfterRank(serverId: String, offsetRank: Long, limit: Int): Flow<List<SessionEntity>>

    @Query(
        """
        SELECT * FROM session
        WHERE serverId = :serverId AND tombstonedAtMs IS NULL
        ORDER BY rosterRank DESC, sessionId DESC
        LIMIT :limit
        """,
    )
    fun observeRosterTop(serverId: String, limit: Int): Flow<List<SessionEntity>>

    /**
     * 任务书要求的 `updatedAt` keyset 原语（`updatedAt DESC, sessionId DESC`），只用于“按活跃度翻页”。
     * **不能当 PagingSource 主游标**：`updatedAt` 会被后台同步改写，届时不跳项无法成立（D19）。
     */
    @Query(
        """
        SELECT * FROM session
        WHERE serverId = :serverId
          AND tombstonedAtMs IS NULL
          AND (updatedAtMs < :u OR (updatedAtMs = :u AND sessionId < :s))
        ORDER BY updatedAtMs DESC, sessionId DESC
        LIMIT :limit
        """,
    )
    fun observeRosterPageByUpdatedAt(serverId: String, u: Long, s: String, limit: Int): Flow<List<SessionEntity>>

    /** 分页总数（活跃行）。 */
    @Query("SELECT COUNT(*) AS total FROM session WHERE serverId = :serverId AND tombstonedAtMs IS NULL")
    fun observeRosterCount(serverId: String): Flow<CountRow>

    /** 名册总行数（含 tombstone），[replaceRoster] 用它的前后差算 inserted。 */
    @Query("SELECT COUNT(*) AS total FROM session WHERE serverId = :serverId")
    suspend fun countRows(serverId: String): CountRow

    @Query("SELECT COUNT(*) AS total FROM session WHERE serverId = :serverId AND tombstonedAtMs IS NOT NULL")
    suspend fun tombstonedCount(serverId: String): CountRow

    /** 诊断：rank 不低于某值的行数（“这一代同步新建了多少行”的粗口径）。 */
    @Query("SELECT COUNT(*) AS total FROM session WHERE serverId = :serverId AND rosterRank >= :rank")
    suspend fun countWithRankAtLeast(serverId: String, rank: Long): CountRow

    // ---- 单行 / 全量读 ----

    @Query("SELECT * FROM session WHERE serverId = :serverId AND sessionId = :sessionId")
    fun observeSession(serverId: String, sessionId: String): Flow<SessionEntity?>

    @Query("SELECT * FROM session WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun session(serverId: String, sessionId: String): SessionEntity?

    /** 小名册场景（收件箱/会话 chip）用的不分页全量。UI 主列表请用 [observeRosterPage]。 */
    @Query(
        """
        SELECT * FROM session WHERE serverId = :serverId AND tombstonedAtMs IS NULL
        ORDER BY rosterRank DESC, sessionId DESC
        """,
    )
    fun observeAllSessions(serverId: String): Flow<List<SessionEntity>>
}

/**
 * [SessionDao.replaceRoster] 的行数汇总，供诊断与“名册是否变化”判断。
 *
 * 只有 insert / tombstone 可计数：Room 的 `@Query` INSERT 不回报受影响行数，
 * 而被 rank 守卫拒绝的陈旧写本来就是 no-op，没有需要区分的第三种结果。
 */
data class RosterSyncResult(
    val incoming: Int,
    val inserted: Int,
    val tombstoned: Int,
) {
    /** 服务端省略、但本次同步没有对应入参的行：absent 的语义来源。 */
    val absentCandidates: Int get() = tombstoned
}
