package ai.deepseek.dsh.android.database

import ai.deepseek.dsh.android.model.SyncOwnerRole
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * `sync_state`：只用于 poll 增量的传输游标（按 identityHash + kind 存 seq + epoch）。
 *
 * 游标属于连接身份而不是会话：token 轮换 → 新摘要 → 旧摘要进退役账本，游标随摘要一起被清
 * （[IdentityDao.purgeRetiredIdentities]），绝不与 `session_event.seq` 混用（F10）。
 */
@Dao
interface SyncStateDao {

    /**
     * 前向守卫 upsert：`WHERE excluded.seq >= sync_state.seq` 让回退的游标成为 0 行 no-op。
     * reset 场景要写更低的 seq 时用 [putCursors]（无条件覆盖），不要用这里。
     */
    @Query(
        """
        INSERT INTO sync_state (identityHash, kind, seq, epoch, updatedAtMs)
        VALUES (:identityHash, :kind, :seq, :epoch, :updatedAtMs)
        ON CONFLICT(identityHash, kind) DO UPDATE SET
            seq = excluded.seq,
            epoch = excluded.epoch,
            updatedAtMs = excluded.updatedAtMs
        WHERE excluded.seq >= sync_state.seq
        """,
    )
    suspend fun advanceCursorRow(identityHash: String, kind: String, seq: Long, epoch: String?, updatedAtMs: Long)

    /** 一批游标的前进式写入（写原语之一，适配器放在同一个 `withTransaction` 里，cursor 必须最后写）。 */
    @Transaction
    suspend fun advanceCursor(cursors: List<SyncStateEntity>) {
        cursors.forEach { row ->
            advanceCursorRow(row.identityHash, row.kind, row.seq, row.epoch, row.updatedAtMs)
        }
    }

    /** 无条件覆盖：reset（G06 的 tombstone → baseline → events → cursor 顺序的最后一步）与 epoch 变化。 */
    @Upsert
    suspend fun putCursors(cursors: List<SyncStateEntity>)

    @Query("SELECT * FROM sync_state WHERE identityHash = :identityHash AND kind = :kind")
    suspend fun cursor(identityHash: String, kind: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE identityHash = :identityHash ORDER BY kind ASC")
    fun observeCursors(identityHash: String): Flow<List<SyncStateEntity>>

    @Query("DELETE FROM sync_state WHERE identityHash = :identityHash")
    suspend fun clearCursors(identityHash: String): Int
}

/**
 * `sync_owner`：跨进程唯一真相（D10）。单行表，`ownerKey` 恒为 [SyncOwnerEntity.SINGLETON]。
 * Worker 开始前 CAS，抢不到就 `Result.retry()`；前台接管时 `Result.success()`。
 */
@Dao
interface SyncOwnerDao {

    /** 建行（幂等）：已存在就是 no-op。role/holderId 的字面量与 `SyncOwnerRole.None` / [NoneRole.HOLDER] 对齐。 */
    @Query(
        """
        INSERT OR IGNORE INTO sync_owner (ownerKey, role, holderId, foregroundRequestedAtMs, updatedAtMs)
        VALUES (:ownerKey, 'None', '', NULL, :nowMs)
        """,
    )
    suspend fun initialize(ownerKey: String, nowMs: Long)

    /** 单行表的建行便捷形式（装配点启动时调用；已存在时静默 no-op）。 */
    suspend fun ensureOwnerRow(nowMs: Long) = initialize(SyncOwnerEntity.SINGLETON, nowMs)

    @Query("SELECT * FROM sync_owner WHERE ownerKey = :ownerKey")
    suspend fun owner(ownerKey: String): SyncOwnerEntity?

    /** [owner] 的单行便捷形式。 */
    suspend fun owner(): SyncOwnerEntity? = owner(SyncOwnerEntity.SINGLETON)

    @Query("SELECT * FROM sync_owner WHERE ownerKey = :ownerKey")
    fun observeOwner(ownerKey: String): Flow<SyncOwnerEntity?>

    /** [observeOwner] 的单行便捷形式（UI 观察前后台归属）。 */
    fun observeOwner(): Flow<SyncOwnerEntity?> = observeOwner(SyncOwnerEntity.SINGLETON)

    /** 三列一次读出（G18 的 owner 快照）。 */
    @Query("SELECT role, holderId, foregroundRequestedAtMs FROM sync_owner WHERE ownerKey = :ownerKey")
    fun observeOwnerState(ownerKey: String): Flow<SyncOwnerStateRow?>

    /**
     * CAS：只有当前 role 等于 [expectedRole] 才改写。
     * [clearForeground] 为真时顺带清掉前台接管请求（前台抢到所有权后请求标记就没有意义了）。
     *
     * @return 1 表示抢到，0 表示别人持有（调用方据此决定 retry / success）。
     */
    @Query(
        """
        UPDATE sync_owner
        SET role = :nextRole,
            holderId = :nextHolder,
            foregroundRequestedAtMs = CASE WHEN :clearForeground THEN NULL ELSE foregroundRequestedAtMs END,
            updatedAtMs = :nowMs
        WHERE ownerKey = :ownerKey AND role = :expectedRole
        """,
    )
    suspend fun casOwner(
        ownerKey: String,
        expectedRole: String,
        nextRole: String,
        nextHolder: String,
        clearForeground: Boolean,
        nowMs: Long,
    ): Int

    /**
     * 枚举 + 单行键形式的 CAS，正好是 `SyncStateStore.casOwner(expected, next, holderId, nowMs)` 的形状。
     * `holderId` 是本地标识（进程/actor），不是协议字段。
     */
    suspend fun casOwner(expected: SyncOwnerRole, next: SyncOwnerRole, holderId: String, nowMs: Long): Boolean =
        casOwner(SyncOwnerEntity.SINGLETON, expected.name, next.name, holderId, false, nowMs) == 1

    /** 所有权释放：role 回 None，holderId 回空串。抢不到（别人已改写）返回 false。 */
    suspend fun release(expected: SyncOwnerRole, nowMs: Long): Boolean =
        casOwner(expected, SyncOwnerRole.None, NoneRole.HOLDER, nowMs)

    /** 前台请求接管（不改变 owner，只立标记）。传 null 即撤销请求。 */
    @Query(
        """
        UPDATE sync_owner SET foregroundRequestedAtMs = :atMs, updatedAtMs = COALESCE(:atMs, updatedAtMs)
        WHERE ownerKey = :ownerKey
        """,
    )
    suspend fun setForegroundRequested(ownerKey: String, atMs: Long?): Int

    /** 单行便捷形式（G18 的接管标记）。 */
    suspend fun setForegroundRequested(atMs: Long?): Boolean =
        setForegroundRequested(SyncOwnerEntity.SINGLETON, atMs) > 0

    /**
     * 一次读出的所有权状态（Worker 决定 retry/success 前的判断）。
     */
    @Transaction
    suspend fun ownerState(ownerKey: String = SyncOwnerEntity.SINGLETON): SyncOwnerStateRow? = owner(ownerKey)?.let {
        SyncOwnerStateRow(it.role.name, it.holderId, it.foregroundRequestedAtMs)
    }
}

/** `resync_request`：needsResync 的持久化（D08 / G06）。attempts 与 degraded 由引擎计算后整体覆盖。 */
@Dao
interface ResyncDao {

    /** 写原语（list-taking）：同会话重复请求覆盖为最新状态。 */
    @Upsert
    suspend fun putResync(requests: List<ResyncRequestEntity>)

    @Query("SELECT * FROM resync_request WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun resync(serverId: String, sessionId: String): ResyncRequestEntity?

    @Query("SELECT * FROM resync_request WHERE serverId = :serverId ORDER BY requestedAtMs ASC")
    fun observeResync(serverId: String): Flow<List<ResyncRequestEntity>>

    /** degraded 的会话（连续两次未覆盖读即停止自动 resync，`needsResyncMaxAttempts`）。 */
    @Query("SELECT COUNT(*) AS total FROM resync_request WHERE serverId = :serverId AND degraded = 1")
    fun observeDegradedCount(serverId: String): Flow<CountRow>

    @Query("DELETE FROM resync_request WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun clearResync(serverId: String, sessionId: String): Int

    @Query("DELETE FROM resync_request WHERE serverId = :serverId")
    suspend fun clearAllResync(serverId: String): Int
}
