package ai.deepseek.dsh.android.database

import ai.deepseek.dsh.android.model.SessionPosition
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * `session_head`：每个会话的持久位置（D07 补齐 serverId）。
 *
 * `contiguousSeq` 与 `appliedMaxSeq` 两列独立推进，绝不合并成一个 watermark（G06）：
 * 快照尾部可以应用 900..950 而 contiguousSeq 仍是 100。
 */
@Dao
interface SessionHeadDao {

    /**
     * head 行的前向守卫 upsert（max-seq-wins 的行级表达）：只有更高的 `appliedMaxSeq` 才能改写位置列；
     * 还没有任何应用（`appliedMaxSeq IS NULL`）的行接受首次写入。
     *
     * `historyUnsafe` 故意不在 SET 列表里：它是单向标记，只能由 [markUnsafe] 置位、由 [clearUnsafe]
     * 显式清除（历史重建之后），普通同步不能顺手抹掉它。
     *
     * 陈旧写入（更低 `appliedMaxSeq`）被 SQL 守卫拒绝成 no-op，位置不会倒退。
     */
    @Query(
        """
        INSERT INTO session_head (serverId, sessionId, contiguousSeq, appliedMaxSeq, historyUnsafe,
                                  historyIncomplete, prunedBelowSeq, lastAppliedAtMs)
        VALUES (:serverId, :sessionId, :contiguousSeq, :appliedMaxSeq, :historyUnsafe,
                :historyIncomplete, :prunedBelowSeq, :lastAppliedAtMs)
        ON CONFLICT(serverId, sessionId) DO UPDATE SET
            contiguousSeq = excluded.contiguousSeq,
            appliedMaxSeq = excluded.appliedMaxSeq,
            historyIncomplete = excluded.historyIncomplete,
            prunedBelowSeq = excluded.prunedBelowSeq,
            lastAppliedAtMs = excluded.lastAppliedAtMs
        WHERE session_head.appliedMaxSeq IS NULL OR excluded.appliedMaxSeq >= session_head.appliedMaxSeq
        """,
    )
    suspend fun advanceHeadRow(
        serverId: String,
        sessionId: String,
        contiguousSeq: Long?,
        appliedMaxSeq: Long?,
        historyUnsafe: Boolean,
        historyIncomplete: Boolean,
        prunedBelowSeq: Long?,
        lastAppliedAtMs: Long,
    )

    /** 一批位置的前进式写入（适配器把它和其他原语放进同一个 `withTransaction`）。 */
    @Transaction
    suspend fun upsertSessionHeads(heads: List<SessionHeadEntity>) {
        heads.forEach { head ->
            advanceHeadRow(
                serverId = head.serverId.value,
                sessionId = head.sessionId.value,
                contiguousSeq = head.contiguousSeq?.value,
                appliedMaxSeq = head.appliedMaxSeq?.value,
                historyUnsafe = head.historyUnsafe,
                historyIncomplete = head.historyIncomplete,
                prunedBelowSeq = head.prunedBelowSeq?.value,
                lastAppliedAtMs = head.lastAppliedAtMs,
            )
        }
    }

    /** 无条件整体覆盖：重置（G06 的 reset 顺序）与导入场景用，绕过前向守卫。 */
    @Upsert
    suspend fun putHeads(heads: List<SessionHeadEntity>)

    // ---- 读 ----

    @Query("SELECT * FROM session_head WHERE serverId = :serverId AND sessionId = :sessionId")
    fun observeHead(serverId: String, sessionId: String): Flow<SessionHeadEntity?>

    @Query("SELECT * FROM session_head WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun head(serverId: String, sessionId: String): SessionHeadEntity?

    @Query("SELECT * FROM session WHERE serverId = :serverId AND sessionId = :sessionId")
    fun observeSessionRow(serverId: String, sessionId: String): Flow<SessionEntity?>

    /**
     * 会话详情头部：名册行 + 位置行。两条 Flow 各自一致，不在同一个事务里（读到瞬间的轻微错位会被
     * 下一次失效信号自愈），因此不需要 `@Transaction`，也避免了 Room 对 Flow 事务终止的限制。
     */
    fun observeSessionWithHead(serverId: String, sessionId: String): Flow<Pair<SessionEntity?, SessionHeadEntity?>> =
        observeSessionRow(serverId, sessionId).combine(observeHead(serverId, sessionId)) { session, head -> session to head }

    @Transaction
    suspend fun positionOf(serverId: String, sessionId: String): SessionPosition? = head(serverId, sessionId)?.toPosition()

    @Query("SELECT * FROM session_head WHERE serverId = :serverId AND sessionId IN (:sessionIds)")
    suspend fun headsFor(serverId: String, sessionIds: List<String>): List<SessionHeadEntity>

    @Query("SELECT * FROM session_head WHERE serverId = :serverId AND sessionId IN (:sessionIds)")
    fun observeHeadsFor(serverId: String, sessionIds: List<String>): Flow<List<SessionHeadEntity>>

    /** 有空洞的会话数（`contiguousSeqLag` 口径：appliedMaxSeq 已跑在 contiguousSeq 前面）。 */
    @Query(
        """
        SELECT COUNT(*) AS total FROM session_head
        WHERE serverId = :serverId AND contiguousSeq IS NOT NULL AND appliedMaxSeq IS NOT NULL
          AND appliedMaxSeq > contiguousSeq
        """,
    )
    fun observeLaggingSessionCount(serverId: String): Flow<CountRow>

    // ---- 历史安全标记（D08） ----

    @Query(
        """
        UPDATE session_head SET historyUnsafe = 1, historyIncomplete = :incomplete
        WHERE serverId = :serverId AND sessionId = :sessionId
        """,
    )
    suspend fun markUnsafe(serverId: String, sessionId: String, incomplete: Boolean): Int

    @Query(
        """
        UPDATE session_head SET historyUnsafe = 0, historyIncomplete = 0
        WHERE serverId = :serverId AND sessionId = :sessionId
        """,
    )
    suspend fun clearUnsafe(serverId: String, sessionId: String): Int

    /** 保留裁剪写完（[SessionEventDao.pruneOlderThan]）后记录裁剪界，UI 显示“历史可能不完整”。 */
    @Query(
        """
        UPDATE session_head SET prunedBelowSeq = :floorSeq, historyIncomplete = 1
        WHERE serverId = :serverId AND sessionId = :sessionId
        """,
    )
    suspend fun notePrunedFloor(serverId: String, sessionId: String, floorSeq: Long): Int

    @Query("DELETE FROM session_head WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun clearHead(serverId: String, sessionId: String): Int
}
