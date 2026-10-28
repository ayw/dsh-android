package ai.deepseek.dsh.android.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 退役身份账本（D14）。
 *
 * “可清理集合”的谓词只有两层含义，写在 [purgeableDigests] 与 [deletePurgedLedgerRows] 两条语句里：
 *  - `retiredAtMs + graceMs <= nowMs`：保留窗口（`identityPurgeAfterMs`，给迟到帧留归因时间）；
 *  - `NOT EXISTS(server …)`：**A→B→A 保护**。同一个摘要在窗口内重新成为某个 server 的当前身份时，
 *    它就不是退役身份，清理不会碰它，账本行也保留到它再次退役。
 * 按 digest 单发的 DELETE（[deleteCursorsFor] 等）不带这两层守卫：它们服务“明确遗忘某个身份”的手工路径，
 * 退役清理只在枚举出的集合上使用它们。
 *
 * 字面量必须逐条内联（Room 的 KSP 只接受注解参数里的字符串常量），两条谓词的一致性由
 * `RetirementPurgeSqlTest` 反射比较 [purgeableDigests] 与 [deletePurgedLedgerRows] 守住。
 *
 * 清理顺序：先清单个身份的传输态（游标 / 通知外发与 tag / scope 失败 / 密文），最后才删账本行，
 * 全部在同一个写事务里（SQLite 写锁排他，枚举与删除之间不会插入一次重新配对）。
 * 崩溃时事务整体回滚，账本行仍在，下一次运行重新执行同一组语句 —— 幂等、可续跑，
 * 不会留下再也找不到的孤儿行。
 *
 * 会话投影（`session` / `session_head` / `session_event` / `applied_island` / `pending_request`）一律不删：
 * 它们按 serverId 归属，跨 token 轮换继续可用。
 */
@Dao
interface IdentityDao {

    @Upsert
    suspend fun putRetired(rows: List<IdentityRetiredEntity>)

    /** 单行幂等登记（轮换时最常用）。 */
    @Upsert
    suspend fun retire(row: IdentityRetiredEntity)

    @Query("SELECT * FROM identity_retired WHERE identityHash = :identityHash")
    suspend fun retired(identityHash: String): IdentityRetiredEntity?

    @Query("SELECT * FROM identity_retired ORDER BY retiredAtMs ASC")
    fun observeRetired(): Flow<List<IdentityRetiredEntity>>

    /** 仍在保留窗口内（还可能用于迟到帧归因）的退役身份数，对应 `identityRetiredPendingPurge`。 */
    @Query("SELECT COUNT(*) AS total FROM identity_retired WHERE retiredAtMs + :graceMs > :nowMs")
    fun observePendingPurgeCount(nowMs: Long, graceMs: Long): Flow<CountRow>

    @Query("SELECT COUNT(*) AS total FROM identity_retired")
    suspend fun retiredCount(): CountRow

    /**
     * 当前会被清理的摘要（[purgeRetiredIdentities] 的枚举，也是诊断口径）。
     * 谓词两处出现（这里与 [deletePurgedLedgerRows]）必须逐字节一致，由 `RetirementPurgeSqlTest` 反射比较。
     */
    @Query(
        """
        SELECT identityHash FROM identity_retired
        WHERE retiredAtMs + :graceMs <= :nowMs
          AND NOT EXISTS (SELECT 1 FROM server AS s WHERE s.identityHash = identity_retired.identityHash)
        """,
    )
    suspend fun purgeableDigests(nowMs: Long, graceMs: Long): List<String>

    /**
     * A→B→A 的正向一步：摘要重新成为某个 server 的当前身份时撤销退役标记。
     * 即使漏调用也不影响正确性 —— 清理语句自带 live 守卫。
     */
    @Query(
        """
        DELETE FROM identity_retired
        WHERE identityHash = :identityHash
          AND EXISTS (SELECT 1 FROM server AS s WHERE s.identityHash = identity_retired.identityHash)
        """,
    )
    suspend fun restoreLiveIdentity(identityHash: String): Int

    // ---- 单表单条 DELETE（幂等、可崩溃续跑） ----

    @Query("DELETE FROM sync_state WHERE identityHash = :identityHash")
    suspend fun deleteCursorsFor(identityHash: String): Int

    @Query("DELETE FROM notification_outbox WHERE identityHash = :identityHash")
    suspend fun deleteOutboxFor(identityHash: String): Int

    @Query("DELETE FROM auth_scope_failures WHERE identityHash = :identityHash")
    suspend fun deleteScopeFailuresFor(identityHash: String): Int

    /** 已退役身份的密文（Keystore 材料在 SecretStore 侧由同一 ledger 驱动删除）。 */
    @Query("DELETE FROM server_credential WHERE identityHash = :identityHash")
    suspend fun deleteCredentialFor(identityHash: String): Int

    /** 一个退役身份的全部传输态：游标 + 通知外发/tag 行 + scope 失败 + 密文。 */
    @Transaction
    suspend fun clearTransportStateFor(identityHash: String): Int {
        var cleared = 0
        cleared += deleteCursorsFor(identityHash)
        cleared += deleteOutboxFor(identityHash)
        cleared += deleteScopeFailuresFor(identityHash)
        cleared += deleteCredentialFor(identityHash)
        return cleared
    }

    /**
     * 身份轮换的一步：只在账本上登记退役。**不清任何传输态** ——
     * 保留窗口（`identityPurgeAfterMs`）存在的理由就是迟到帧还要按旧摘要归因，
     * 真正的删除只由 [purgeRetiredIdentities] 做（端口 `SyncStateStore.markIdentityRetired` 的语义）。
     */
    @Transaction
    suspend fun markRetired(row: IdentityRetiredEntity) {
        retire(row)
        restoreLiveIdentity(row.identityHash)
    }

    /**
     * 全量清理：枚举已过窗口且已非当前身份的摘要 → 逐个清传输态 → 最后删账本行。
     * 守卫的落点是 [purgeableDigests] 的谓词（保留窗口 + `NOT EXISTS(server…)`）与
     * [deletePurgedLedgerRows] 里逐字相同的那一份；整个序列在**一个写事务**里，
     * SQLite 的写锁排他，所以枚举与删除之间不可能插入一次重新配对（A→B→A 要么整体生效，要么整体不生效）。
     *
     * 崩溃可续跑：事务没提交就什么都没有（账本行仍在），下一次运行重新执行同一组语句；
     * 提交成功后再跑就是 0 行。会话投影一律不删。
     *
     * @return 本次清理掉的摘要（空列表表示没有到窗口，或全被 A→B→A 保护）。
     *   `SyncStateStore.purgeEligibleIdentities` 直接把它映射成 `ConnectionIdentity` 列表。
     */
    @Transaction
    suspend fun purgeRetiredIdentities(nowMs: Long, graceMs: Long): List<String> {
        val digests = purgeableDigests(nowMs, graceMs)
        digests.forEach { clearTransportStateFor(it) }
        deletePurgedLedgerRows(nowMs, graceMs)
        return digests
    }

    @Query(
        """
        DELETE FROM identity_retired
        WHERE retiredAtMs + :graceMs <= :nowMs
          AND NOT EXISTS (SELECT 1 FROM server AS s WHERE s.identityHash = identity_retired.identityHash)
        """,
    )
    suspend fun deletePurgedLedgerRows(nowMs: Long, graceMs: Long): Int
}

/**
 * `auth_scope_failures`：按身份记 401/403 的观察结果（G18：AuthExpired 只能由观察到的 401/403 进入，
 * 超时/TLS/DNS/1006/坏 JSON 不置位，所以那些分支不调用这里）。
 * 只存 scope 名与状态码：不含 URL、文件路径、凭据。
 */
@Dao
interface AuthDao {

    /** 一次失败：已存在则计数 +1，不存在则从 1 开始。 */
    @Query(
        """
        INSERT INTO auth_scope_failures (identityHash, scope, failCount, lastCode, lastFailureAtMs)
        VALUES (:identityHash, :scope, 1, :lastCode, :lastFailureAtMs)
        ON CONFLICT(identityHash, scope) DO UPDATE SET
            failCount = auth_scope_failures.failCount + 1,
            lastCode = excluded.lastCode,
            lastFailureAtMs = excluded.lastFailureAtMs
        """,
    )
    suspend fun recordFailure(identityHash: String, scope: String, lastCode: Int, lastFailureAtMs: Long)

    @Upsert
    suspend fun putScopeFailures(rows: List<AuthScopeFailureEntity>)

    @Query("SELECT * FROM auth_scope_failures WHERE identityHash = :identityHash ORDER BY scope ASC")
    fun observeScopeFailures(identityHash: String): Flow<List<AuthScopeFailureEntity>>

    @Query("SELECT * FROM auth_scope_failures WHERE identityHash = :identityHash AND scope = :scope")
    suspend fun scopeFailure(identityHash: String, scope: String): AuthScopeFailureEntity?

    /** 重连/探测成功后清零（`auth401BackgroundPauseThreshold` 的判断需要能回到 0）。 */
    @Query("DELETE FROM auth_scope_failures WHERE identityHash = :identityHash")
    suspend fun clearFailures(identityHash: String): Int

    @Query("DELETE FROM auth_scope_failures WHERE identityHash = :identityHash AND scope = :scope")
    suspend fun clearFailure(identityHash: String, scope: String): Int
}
