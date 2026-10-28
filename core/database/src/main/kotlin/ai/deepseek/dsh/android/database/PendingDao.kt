package ai.deepseek.dsh.android.database

import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.RequestId
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * `pending_request`（D13）。主键 `(serverId, kind, requestId)`；幂等性靠 SQL 里的 `asOfSeq` 单调守卫，
 * 陈旧写入是 **0 行 no-op**，不是 Kotlin 侧的比较。
 *
 * baseline sweep 用 `sweptAtMs` 当“世代标记”：本次确认到的 key 打上本次时间戳，没打上的即 absent，
 * 因此 sweep 只需两条语句，且可重跑。
 *
 * SQL 里的状态字面量必须与 `PendingStatus.name` 逐字节一致，由 `PendingSqlLiteralTest` 守住这条耦合。
 */
@Dao
interface PendingDao {

    /**
     * 守卫 upsert：`ON CONFLICT` 目标是完整主键，
     * `WHERE excluded.asOfSeq >= pending_request.asOfSeq` 就是 D13 的单调守卫。
     *
     * `firstSeenAtMs` / `createdAtMs` 不在 SET 列表里（首次可见时间必须保留）；
     * `dismissedAtMs` / `resolvedAtMs` / `tombstonedAtMs` / `sweptAtMs` 用
     * `COALESCE(excluded, 现有)`：划掉、解决、tombstone、sweep 世代都是“有值就不被空值抹掉”的事实（G07）。
     *
     * @return 受影响行数；0 表示被守卫拒绝。
     */
    @Query(
        """
        INSERT INTO pending_request (serverId, kind, requestId, sessionId, status, completeness, payloadJson,
                                     firstSeenAtMs, createdAtMs, asOfSeq, anchorSeq, callId, toolName,
                                     dismissedAtMs, resolvedAtMs, tombstonedAtMs, sweptAtMs)
        VALUES (:serverId, :kind, :requestId, :sessionId, :status, :completeness, :payloadJson,
                :firstSeenAtMs, :createdAtMs, :asOfSeq, :anchorSeq, :callId, :toolName,
                :dismissedAtMs, :resolvedAtMs, :tombstonedAtMs, :sweptAtMs)
        ON CONFLICT(serverId, kind, requestId) DO UPDATE SET
            sessionId = excluded.sessionId,
            status = excluded.status,
            completeness = excluded.completeness,
            payloadJson = excluded.payloadJson,
            anchorSeq = excluded.anchorSeq,
            callId = excluded.callId,
            toolName = excluded.toolName,
            dismissedAtMs = COALESCE(excluded.dismissedAtMs, pending_request.dismissedAtMs),
            resolvedAtMs = COALESCE(excluded.resolvedAtMs, pending_request.resolvedAtMs),
            tombstonedAtMs = COALESCE(excluded.tombstonedAtMs, pending_request.tombstonedAtMs),
            sweptAtMs = COALESCE(excluded.sweptAtMs, pending_request.sweptAtMs),
            asOfSeq = excluded.asOfSeq
        WHERE excluded.asOfSeq >= pending_request.asOfSeq
        """,
    )
    suspend fun upsertPendingRow(
        serverId: String,
        kind: String,
        requestId: String,
        sessionId: String,
        status: String,
        completeness: String,
        payloadJson: String,
        firstSeenAtMs: Long,
        createdAtMs: Long,
        asOfSeq: Long,
        anchorSeq: Long?,
        callId: String?,
        toolName: String?,
        dismissedAtMs: Long?,
        resolvedAtMs: Long?,
        tombstonedAtMs: Long?,
        sweptAtMs: Long?,
    )

    /**
     * 一批 pending 变更的幂等写入（写原语之一，适配器把它和其他原语放进同一个 `withTransaction`）。
     * 入参是实体：`asOfSeq` 由适配器按当前会话位置计算。
     * 被 asOfSeq 守卫拒绝的陈旧写是行级 no-op，不会让位置或状态倒退。
     */
    @Transaction
    suspend fun applyPendingChanges(changes: List<PendingRequestEntity>) {
        changes.forEach { row ->
            upsertPendingRow(
                serverId = row.serverId.value,
                kind = row.kind.name,
                requestId = row.requestId.value,
                sessionId = row.sessionId.value,
                status = row.status.name,
                completeness = row.completeness.name,
                payloadJson = row.payloadJson,
                firstSeenAtMs = row.firstSeenAtMs,
                createdAtMs = row.createdAtMs,
                asOfSeq = row.asOfSeq,
                anchorSeq = row.anchorSeq?.value,
                callId = row.callId?.value,
                toolName = row.toolName,
                dismissedAtMs = row.dismissedAtMs,
                resolvedAtMs = row.resolvedAtMs,
                tombstonedAtMs = row.tombstonedAtMs,
                sweptAtMs = row.sweptAtMs,
            )
        }
    }

    /** 无条件整体覆盖（导入/修复场景，绕过 asOfSeq 守卫）。 */
    @Upsert
    suspend fun putPending(rows: List<PendingRequestEntity>)

    /** 手动划掉：只写 `dismissedAtMs`，不改 status，因此不减少“resolvedAt 为空”的集合（G07）。 */
    @Query(
        """
        UPDATE pending_request SET dismissedAtMs = :nowMs
        WHERE serverId = :serverId AND kind = :kind AND requestId = :requestId AND dismissedAtMs IS NULL
        """,
    )
    suspend fun dismiss(serverId: String, kind: String, requestId: String, nowMs: Long): Int

    /** tombstone：标记“基线里没有它”，与 dismiss 严格区分（G07）。 */
    @Query(
        """
        UPDATE pending_request
        SET status = 'Stale', completeness = :completeness, tombstonedAtMs = :nowMs, sweptAtMs = :nowMs
        WHERE serverId = :serverId AND kind = :kind AND requestId = :requestId
        """,
    )
    suspend fun tombstone(serverId: String, kind: String, requestId: String, completeness: String, nowMs: Long): Int

    /** 枚举形式的 [dismiss]：适配器手里是 `PendingKind` / `RequestId`，不是字符串（D27）。 */
    suspend fun dismiss(serverId: String, kind: PendingKind, requestId: RequestId, nowMs: Long): Int =
        dismiss(serverId, kind.name, requestId.value, nowMs)

    /** 枚举形式的 [tombstone]。 */
    suspend fun tombstone(
        serverId: String,
        kind: PendingKind,
        requestId: RequestId,
        completeness: PendingCompleteness,
        nowMs: Long,
    ): Int = tombstone(serverId, kind.name, requestId.value, completeness.name, nowMs)

    /** 本次 baseline 确认到的 key（`kind@requestId`），清除 tombstone 并打上本次世代。 */
    @Query(
        """
        UPDATE pending_request SET sweptAtMs = :sweepAtMs, tombstonedAtMs = NULL
        WHERE serverId = :serverId AND status <> 'Resolved' AND (kind || '@' || requestId) IN (:keys)
        """,
    )
    suspend fun markPresent(serverId: String, keys: List<String>, sweepAtMs: Long): Int

    /**
     * 没被本次世代打上的行才转 Stale；`Resolved` 与已 tombstone 的行不动（G07：resolve 后重放 request
     * 仍保持 tombstoned）。`sweptAtMs IS NOT :sweepAtMs` 是 NULL 安全比较：从未被 sweep 的行也算未确认。
     */
    @Query(
        """
        UPDATE pending_request
        SET status = 'Stale', completeness = :completeness, tombstonedAtMs = COALESCE(tombstonedAtMs, :nowMs)
        WHERE serverId = :serverId AND status NOT IN ('Resolved', 'Stale') AND sweptAtMs IS NOT :sweepAtMs
        """,
    )
    suspend fun tombstoneUnconfirmed(serverId: String, sweepAtMs: Long, nowMs: Long, completeness: String): Int

    /**
     * sweep 的完整一步（两条语句 + 一个事务）。`Unknown` 状态由 baseline 重确认回 `Pending` 的转移
     * 属于状态机（`:core:domain`），由适配器通过 [applyPendingChanges] 写入，这里不擅自改 status。
     */
    @Transaction
    suspend fun sweepAbsent(
        serverId: String,
        presentKeys: List<String>,
        sweepAtMs: Long,
        nowMs: Long,
        completeness: String,
    ): Int {
        if (presentKeys.isNotEmpty()) markPresent(serverId, presentKeys, sweepAtMs)
        return tombstoneUnconfirmed(serverId, sweepAtMs, nowMs, completeness)
    }

    /** tombstone TTL 到期：整行删除，下一次 baseline 可以把它当新 key 重新确认（`tombstoneTtlMs`）。 */
    @Query("DELETE FROM pending_request WHERE tombstonedAtMs IS NOT NULL AND tombstonedAtMs + :ttlMs <= :nowMs")
    suspend fun purgeExpiredTombstones(nowMs: Long, ttlMs: Long): Int

    @Query("DELETE FROM pending_request WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun clearForSession(serverId: String, sessionId: String): Int

    // ---- 读 ----

    @Query("SELECT * FROM pending_request WHERE serverId = :serverId ORDER BY firstSeenAtMs DESC, requestId ASC")
    fun observePending(serverId: String): Flow<List<PendingRequestEntity>>

    @Query(
        """
        SELECT * FROM pending_request WHERE serverId = :serverId AND sessionId = :sessionId
        ORDER BY firstSeenAtMs DESC, requestId ASC
        """,
    )
    fun observePendingForSession(serverId: String, sessionId: String): Flow<List<PendingRequestEntity>>

    @Query(
        """
        SELECT * FROM pending_request WHERE serverId = :serverId AND sessionId = :sessionId
        ORDER BY firstSeenAtMs DESC, requestId ASC
        """,
    )
    suspend fun pendingForSession(serverId: String, sessionId: String): List<PendingRequestEntity>

    @Query("SELECT * FROM pending_request WHERE serverId = :serverId AND kind = :kind AND requestId = :requestId")
    suspend fun pending(serverId: String, kind: String, requestId: String): PendingRequestEntity?

    /** 按会话分组聚合（会话卡片 chip）。 */
    @Query(
        """
        SELECT sessionId,
               SUM(CASE WHEN status IN ('Pending', 'Answering', 'Unknown') AND dismissedAtMs IS NULL
                        THEN 1 ELSE 0 END) AS badge,
               SUM(CASE WHEN dismissedAtMs IS NOT NULL THEN 1 ELSE 0 END) AS dismissed,
               SUM(CASE WHEN status = 'Unknown' THEN 1 ELSE 0 END) AS unknownCount,
               SUM(CASE WHEN status = 'Stale' THEN 1 ELSE 0 END) AS staleCount,
               SUM(CASE WHEN tombstonedAtMs IS NOT NULL THEN 1 ELSE 0 END) AS tombstonedCount,
               MAX(asOfSeq) AS maxAsOfSeq
        FROM pending_request WHERE serverId = :serverId
        GROUP BY sessionId ORDER BY MAX(firstSeenAtMs) DESC
        """,
    )
    fun observePendingGrouped(serverId: String): Flow<List<PendingSessionGroupRow>>

    /**
     * 全局聚合（收件箱）。badge 不计 Resolved/Stale，也不计 dismissed（G07）；
     * `pendingKeyCount` 是 sweep 的待处理 key 数，用来和 `baselineKeySweepSkipThreshold` 比较（D12），
     * 超阈值时 completeness 取 Unknown、计数显示“未知”而不是数字。
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN status IN ('Pending', 'Answering', 'Unknown') AND dismissedAtMs IS NULL
                                 THEN 1 ELSE 0 END), 0) AS badge,
               COALESCE(SUM(CASE WHEN dismissedAtMs IS NOT NULL THEN 1 ELSE 0 END), 0) AS dismissed,
               COALESCE(SUM(CASE WHEN status = 'Pending' THEN 1 ELSE 0 END), 0) AS pendingCount,
               COALESCE(SUM(CASE WHEN status = 'Answering' THEN 1 ELSE 0 END), 0) AS answeringCount,
               COALESCE(SUM(CASE WHEN status = 'Unknown' THEN 1 ELSE 0 END), 0) AS unknownCount,
               COALESCE(SUM(CASE WHEN status = 'Resolved' THEN 1 ELSE 0 END), 0) AS resolvedCount,
               COALESCE(SUM(CASE WHEN status = 'Stale' THEN 1 ELSE 0 END), 0) AS staleCount,
               COALESCE(SUM(CASE WHEN tombstonedAtMs IS NOT NULL THEN 1 ELSE 0 END), 0) AS tombstonedCount,
               COALESCE(SUM(CASE WHEN status IN ('Pending', 'Answering', 'Unknown', 'Stale')
                                 THEN 1 ELSE 0 END), 0) AS pendingKeyCount,
               MAX(asOfSeq) AS maxAsOfSeq,
               MAX(sweptAtMs) AS lastSweptAtMs
        FROM pending_request WHERE serverId = :serverId
        """,
    )
    fun observePendingBadge(serverId: String): Flow<PendingBadgeRow>

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN status IN ('Pending', 'Answering', 'Unknown') AND dismissedAtMs IS NULL
                                 THEN 1 ELSE 0 END), 0) AS badge,
               COALESCE(SUM(CASE WHEN dismissedAtMs IS NOT NULL THEN 1 ELSE 0 END), 0) AS dismissed,
               COALESCE(SUM(CASE WHEN status = 'Pending' THEN 1 ELSE 0 END), 0) AS pendingCount,
               COALESCE(SUM(CASE WHEN status = 'Answering' THEN 1 ELSE 0 END), 0) AS answeringCount,
               COALESCE(SUM(CASE WHEN status = 'Unknown' THEN 1 ELSE 0 END), 0) AS unknownCount,
               COALESCE(SUM(CASE WHEN status = 'Resolved' THEN 1 ELSE 0 END), 0) AS resolvedCount,
               COALESCE(SUM(CASE WHEN status = 'Stale' THEN 1 ELSE 0 END), 0) AS staleCount,
               COALESCE(SUM(CASE WHEN tombstonedAtMs IS NOT NULL THEN 1 ELSE 0 END), 0) AS tombstonedCount,
               COALESCE(SUM(CASE WHEN status IN ('Pending', 'Answering', 'Unknown', 'Stale')
                                 THEN 1 ELSE 0 END), 0) AS pendingKeyCount,
               MAX(asOfSeq) AS maxAsOfSeq,
               MAX(sweptAtMs) AS lastSweptAtMs
        FROM pending_request WHERE serverId = :serverId
        """,
    )
    suspend fun pendingBadge(serverId: String): PendingBadgeRow
}

/** baseline sweep 的 key 编码：`kind@requestId`（[PendingKind] 的名字里不可能出现 `@`）。 */
fun pendingSweepKey(kind: PendingKind, requestId: RequestId): String = "${kind.name}@${requestId.value}"
