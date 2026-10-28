package ai.deepseek.dsh.android.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 诊断计数器名（D23 / G10）。字符串是表的键，不是可调参数，因此在这里集中定义；
 * `:core:domain` 的 `SyncTuning` 数值不出现在本模块。
 */
object DiagnosticsCounters {
    const val BAD_JSON = "badJson"
    const val WS_DOWNGRADE = "wsDowngrade"
    const val RETENTION_TRIMMED = "retentionTrimmed"
    const val BASELINE_SWEEP_SKIPPED = "baselineSweepSkipped"
}

/**
 * 诊断快照语句：Flow 版本与一次性版本共用同一条文本（两条注解必须逐字节一致，
 * 由 `DiagnosticsSnapshotSqlTest` 反射比较守住），避免两个口径。
 * 每个字段各一个子查询，行数恒为 1，因此 UI 诊断页只需订阅一个 Flow（schema 里不建视图）。
 * Room 的 KSP 只接受注解参数里的字符串常量，所以这里不做 const 复用。
 */

/**
 * `diagnostics_counters` + 跨表快照。
 *
 * 快照是一条“每个字段各一个子查询”的聚合语句：行数恒为 1，UI 的诊断页因此可以只订阅一个 Flow。
 * 不依赖 `@Transaction` 的 Flow 终止语义，也不需要视图（schema 里保持普通表）。
 */
@Dao
interface DiagnosticsDao {

    /** 累加式写入（G10 的计数都是累计值）。 */
    @Query(
        """
        INSERT INTO diagnostics_counters (name, value, updatedAtMs)
        VALUES (:name, :delta, :updatedAtMs)
        ON CONFLICT(name) DO UPDATE SET
            value = diagnostics_counters.value + excluded.value,
            updatedAtMs = excluded.updatedAtMs
        """,
    )
    suspend fun incrementCounter(name: String, delta: Long, updatedAtMs: Long)

    /** 覆盖式写入：布尔状态类计数（如 `baselineSweepSkipped`）用这条。 */
    @Upsert
    suspend fun putCounters(counters: List<DiagnosticsCounterEntity>)

    @Query("SELECT * FROM diagnostics_counters ORDER BY name ASC")
    fun observeCounters(): Flow<List<DiagnosticsCounterEntity>>

    @Query("SELECT * FROM diagnostics_counters WHERE name = :name")
    suspend fun counter(name: String): DiagnosticsCounterEntity?

    @Query("DELETE FROM diagnostics_counters WHERE name = :name")
    suspend fun resetCounter(name: String): Int

    /**
     * 持久化部分的可读值 + 累计量，诊断页一次订阅（G10 要求 degraded 有机器可读定义）。
     * `degraded` 布尔由 `:core:model` 的 `DiagnosticsSnapshot` 计算，不在 SQL 里复制规则。
     */
    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM resync_request) AS needsResyncCount,
          (SELECT COUNT(*) FROM resync_request WHERE degraded = 1) AS degradedResyncCount,
          (SELECT COUNT(*) FROM session_head WHERE historyUnsafe = 1) AS historyUnsafeSessions,
          (SELECT COUNT(*) FROM session_head WHERE historyIncomplete = 1) AS historyIncompleteSessions,
          (SELECT COUNT(*) FROM session_head
             WHERE contiguousSeq IS NOT NULL AND appliedMaxSeq IS NOT NULL
               AND appliedMaxSeq > contiguousSeq) AS laggingSessions,
          (SELECT MAX(appliedMaxSeq - contiguousSeq) FROM session_head
             WHERE contiguousSeq IS NOT NULL AND appliedMaxSeq IS NOT NULL) AS maxContiguousLag,
          (SELECT SUM(appliedMaxSeq - contiguousSeq) FROM session_head
             WHERE contiguousSeq IS NOT NULL AND appliedMaxSeq IS NOT NULL) AS contiguousLagSum,
          (SELECT COUNT(*) FROM identity_retired) AS retiredIdentities,
          (SELECT COUNT(*) FROM sync_state) AS cursorRows,
          (SELECT COUNT(*) FROM session WHERE tombstonedAtMs IS NULL) AS liveSessions,
          (SELECT COUNT(*) FROM session WHERE tombstonedAtMs IS NOT NULL) AS tombstonedSessions,
          (SELECT COALESCE(SUM(CASE WHEN status IN ('Pending', 'Answering', 'Unknown') AND dismissedAtMs IS NULL
                                    THEN 1 ELSE 0 END), 0) FROM pending_request) AS pendingBadge,
          (SELECT COUNT(*) FROM pending_request) AS pendingRows,
          (SELECT COUNT(*) FROM notification_outbox WHERE state = 'NEW') AS outboxNew,
          (SELECT COUNT(*) FROM auth_scope_failures) AS authScopeFailureScopes,
          (SELECT COALESCE(SUM(failCount), 0) FROM auth_scope_failures) AS authScopeFailureCount
        """,
    )
    fun observeDiagnostics(): Flow<DiagnosticsAggregateRow>

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM resync_request) AS needsResyncCount,
          (SELECT COUNT(*) FROM resync_request WHERE degraded = 1) AS degradedResyncCount,
          (SELECT COUNT(*) FROM session_head WHERE historyUnsafe = 1) AS historyUnsafeSessions,
          (SELECT COUNT(*) FROM session_head WHERE historyIncomplete = 1) AS historyIncompleteSessions,
          (SELECT COUNT(*) FROM session_head
             WHERE contiguousSeq IS NOT NULL AND appliedMaxSeq IS NOT NULL
               AND appliedMaxSeq > contiguousSeq) AS laggingSessions,
          (SELECT MAX(appliedMaxSeq - contiguousSeq) FROM session_head
             WHERE contiguousSeq IS NOT NULL AND appliedMaxSeq IS NOT NULL) AS maxContiguousLag,
          (SELECT SUM(appliedMaxSeq - contiguousSeq) FROM session_head
             WHERE contiguousSeq IS NOT NULL AND appliedMaxSeq IS NOT NULL) AS contiguousLagSum,
          (SELECT COUNT(*) FROM identity_retired) AS retiredIdentities,
          (SELECT COUNT(*) FROM sync_state) AS cursorRows,
          (SELECT COUNT(*) FROM session WHERE tombstonedAtMs IS NULL) AS liveSessions,
          (SELECT COUNT(*) FROM session WHERE tombstonedAtMs IS NOT NULL) AS tombstonedSessions,
          (SELECT COALESCE(SUM(CASE WHEN status IN ('Pending', 'Answering', 'Unknown') AND dismissedAtMs IS NULL
                                    THEN 1 ELSE 0 END), 0) FROM pending_request) AS pendingBadge,
          (SELECT COUNT(*) FROM pending_request) AS pendingRows,
          (SELECT COUNT(*) FROM notification_outbox WHERE state = 'NEW') AS outboxNew,
          (SELECT COUNT(*) FROM auth_scope_failures) AS authScopeFailureScopes,
          (SELECT COALESCE(SUM(failCount), 0) FROM auth_scope_failures) AS authScopeFailureCount
        """,
    )
    suspend fun diagnosticsSnapshot(): DiagnosticsAggregateRow
}
