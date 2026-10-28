package ai.deepseek.dsh.android.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 原始帧表 `session_event`（durable raw log）。
 *
 * `payloadJson` 一律原样存取，本模块绝不重新编码（D06），这样 BFF 切换时字节可比。
 * `ignorable` 是帧上的字段而不是 payload 里的字段，因此单独存列（D08 的三档投影在离线重放时依赖它）。
 */
@Dao
interface SessionEventDao {

    // ---- 写 ----

    /**
     * 幂等 upsert：冲突目标就是完整复合键 `(serverId, sessionId, seq)`，
     * 同 seq 的重复帧整行覆盖（payload 以最后一次到达的为准），不同 seq 互不影响 —— 即 max-seq-wins 的
     * 行级含义：更高的 seq 永远不会被更低的 seq 覆盖掉（它们是不同行）。
     */
    @Upsert
    suspend fun upsertEvents(events: List<SessionEventEntity>)

    /**
     * 已应用过的帧走 IGNORE：命中唯一键时 SQLite 直接跳过，比 [upsertEvents] 便宜。
     * @return 每行的 rowId，被忽略的行返回 -1（API 24+ 语义，minSdk 26 可用）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventsIgnore(events: List<SessionEventEntity>): List<Long>

    @Delete
    suspend fun deleteEvents(events: List<SessionEventEntity>)

    @Query("DELETE FROM session_event WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun clearSessionEvents(serverId: String, sessionId: String): Int

    // ---- 历史分页（keyset on seq，beforeSeq exclusive，F1/F19） ----

    @Query(
        """
        SELECT * FROM session_event
        WHERE serverId = :serverId AND sessionId = :sessionId
          AND (:beforeSeq IS NULL OR seq < :beforeSeq)
        ORDER BY seq DESC
        LIMIT :limit
        """,
    )
    fun observeEventsBefore(serverId: String, sessionId: String, beforeSeq: Long?, limit: Int): Flow<List<SessionEventEntity>>

    @Query(
        """
        SELECT * FROM session_event
        WHERE serverId = :serverId AND sessionId = :sessionId
          AND (:beforeSeq IS NULL OR seq < :beforeSeq)
        ORDER BY seq DESC
        LIMIT :limit
        """,
    )
    suspend fun eventsBefore(serverId: String, sessionId: String, beforeSeq: Long?, limit: Int): List<SessionEventEntity>

    /** 从 `fromSeq` 向上按 seq 升序读（投影/重放顺序，G06 需要“按写入顺序应用”）。 */
    @Query(
        """
        SELECT * FROM session_event
        WHERE serverId = :serverId AND sessionId = :sessionId AND seq >= :fromSeq
        ORDER BY seq ASC
        LIMIT :limit
        """,
    )
    suspend fun eventsFrom(serverId: String, sessionId: String, fromSeq: Long, limit: Int): List<SessionEventEntity>

    @Query("SELECT * FROM session_event WHERE serverId = :serverId AND sessionId = :sessionId AND seq = :seq")
    suspend fun eventAt(serverId: String, sessionId: String, seq: Long): SessionEventEntity?

    /** 库里已存的最大 seq（`MAX` 聚合恒有一行，空表时 maxSeq 为 null）。 */
    @Query("SELECT MAX(seq) AS maxSeq FROM session_event WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun maxStoredSeq(serverId: String, sessionId: String): MaxSeqRow

    @Query("SELECT COUNT(*) AS total FROM session_event WHERE serverId = :serverId AND sessionId = :sessionId")
    fun observeEventCount(serverId: String, sessionId: String): Flow<CountRow>

    @Query("SELECT COUNT(*) AS total FROM session_event WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun eventCount(serverId: String, sessionId: String): CountRow

    // ---- 保留裁剪（G12 / D07） ----

    /**
     * 每会话只保留最新 [capPerSession] 行，且**永远不删 `contiguousSeq` 及其之后的行**：
     * 投影窗口和 needsResync 修复都要读连续前缀之上的帧，删掉它们等于自造空洞。
     * 删除条件里的 `seq < (SELECT contiguousSeq …)` 在无 head 行或 contiguousSeq 为 NULL 时整体为 NULL，
     * 于是该会话一行都不删（没有连续前缀就没有“已覆盖前缀”，裁剪只能等投影追上）。
     *
     * 不使用窗口函数：minSdk 26 的设备自带 SQLite 3.19，`OVER` 要 3.25。
     *
     * @return 实际删除的行数；调用方据此写 `retentionTrimmed` 诊断计数（D07），本模块不跨 DAO 写计数器。
     */
    @Transaction
    suspend fun pruneEvents(capPerSession: Int): Int {
        var trimmed = 0
        selectOverCapSessions(capPerSession).forEach { row ->
            val threshold = thresholdSeqOf(row.serverId, row.sessionId, capPerSession) ?: return@forEach
            trimmed += pruneOlderThan(row.serverId, row.sessionId, threshold)
        }
        return trimmed
    }

    @Query(
        """
        SELECT serverId, sessionId, COUNT(*) AS eventCount FROM session_event
        GROUP BY serverId, sessionId HAVING COUNT(*) > :capPerSession
        """,
    )
    suspend fun selectOverCapSessions(capPerSession: Int): List<SessionEventCountRow>

    /** 最新 keepCount 行里最靠前的 seq：它之前的行就是“已覆盖前缀”的候选。会话不足 keepCount 行时返回其最小 seq（于是删 0 行）。 */
    @Query(
        """
        SELECT MIN(seq) FROM (
            SELECT seq FROM session_event
            WHERE serverId = :serverId AND sessionId = :sessionId
            ORDER BY seq DESC LIMIT :keepCount
        )
        """,
    )
    suspend fun thresholdSeqOf(serverId: String, sessionId: String, keepCount: Int): Long?

    @Query(
        """
        DELETE FROM session_event
        WHERE serverId = :serverId AND sessionId = :sessionId
          AND seq < :thresholdSeq
          AND seq < (SELECT contiguousSeq FROM session_head WHERE serverId = :serverId AND sessionId = :sessionId)
        """,
    )
    suspend fun pruneOlderThan(serverId: String, sessionId: String, thresholdSeq: Long): Int

    /** head 表由 [SessionHeadDao.notePrunedFloor] 记录裁剪界，UI 据此显示“历史可能不完整”。 */
}

/** `MAX(seq) AS maxSeq`（无行时为 NULL）。 */
data class MaxSeqRow(val maxSeq: Long?)
