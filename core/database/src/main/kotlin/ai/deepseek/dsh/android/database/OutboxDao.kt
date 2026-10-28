package ai.deepseek.dsh.android.database

import ai.deepseek.dsh.android.model.OutboxState
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * `notification_outbox`（D28）：只承载**通知外发期望**，不承载任何写请求回执队列。
 *
 * 主键 `(identityHash, kind, requestId)` 让“入队”天然幂等：同一个 pending key 重复到达不会重复通知。
 * 状态列存 `:core:model` 的 `OutboxState`（`New / Sent / Cancelled / Skipped`，D27/D28 取消了
 * 本模块自造的 `NEW/IN_PROGRESS/DONE/FAILED` 拼写），SQL 里的字面量必须与枚举名逐字节一致，
 * 由 `OutboxStateLiteralTest` 反射守住这条耦合。
 *
 * `identityHash` 是外发所属的**连接身份**（通知必须由当前身份发出），所以整行随退役账本一起被清
 * （见 [IdentityDao.deleteOutboxFor]）；`notificationTag` 是系统通知的替换/撤回标识，随行一起删除。
 */
@Dao
interface OutboxDao {

    /** 写原语（list-taking）：已存在的行保持原状态，被跳过的行返回 rowId = -1（API 24+ 语义）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutbox(entries: List<NotificationOutboxEntity>): List<Long>

    /** 无条件覆盖（重建队列/修复用）。 */
    @Upsert
    suspend fun putOutbox(entries: List<NotificationOutboxEntity>)

    /** 待外发（`New`）的最早一批，drain 顺序稳定。 */
    @Query(
        """
        SELECT * FROM notification_outbox
        WHERE identityHash = :identityHash AND state = 'New'
        ORDER BY createdAtMs ASC, requestId ASC
        LIMIT :limit
        """,
    )
    suspend fun takeNew(identityHash: String, limit: Int): List<NotificationOutboxEntity>

    @Query(
        """
        SELECT * FROM notification_outbox
        WHERE identityHash = :identityHash AND state = 'New'
        ORDER BY createdAtMs ASC, requestId ASC
        LIMIT :limit
        """,
    )
    fun observeNew(identityHash: String, limit: Int): Flow<List<NotificationOutboxEntity>>

    /**
     * 状态迁移（New → Sent，或 New → Cancelled / Skipped）：带期望态守卫，
     * 抢不到期望态就是 0 行（并发 drain 时不会重复外发）。
     * 入参是枚举名的字符串形式（`OutboxState.name`），由 `:core:data` 传入，本模块不复制状态机。
     */
    @Query(
        """
        UPDATE notification_outbox SET state = :nextState, updatedAtMs = :nowMs
        WHERE identityHash = :identityHash AND state = :expectedState AND requestId IN (:requestIds)
        """,
    )
    suspend fun transition(
        identityHash: String,
        requestIds: List<String>,
        expectedState: String,
        nextState: String,
        nowMs: Long,
    ): Int

    /** 整 tag 撤回/覆盖：一个 tag 可能对应多个 pending key（[observeTags]）。 */
    @Query(
        """
        UPDATE notification_outbox SET state = :nextState, updatedAtMs = :nowMs
        WHERE identityHash = :identityHash AND notificationTag = :notificationTag AND state = :expectedState
        """,
    )
    suspend fun transitionByTag(
        identityHash: String,
        notificationTag: String,
        expectedState: String,
        nextState: String,
        nowMs: Long,
    ): Int

    /**
     * 枚举形式的 [transition]：适配器手里是 `OutboxState` 而不是字符串（D27）。
     * 只在这里 `.name` 一次，SQL 绑定的仍是 TEXT，不依赖查询参数的类型转换。
     */
    suspend fun transition(
        identityHash: String,
        requestIds: List<String>,
        expected: OutboxState,
        next: OutboxState,
        nowMs: Long,
    ): Int = transition(identityHash, requestIds, expected.name, next.name, nowMs)

    /** 枚举形式的 [transitionByTag]。 */
    suspend fun transitionByTag(
        identityHash: String,
        notificationTag: String,
        expected: OutboxState,
        next: OutboxState,
        nowMs: Long,
    ): Int = transitionByTag(identityHash, notificationTag, expected.name, next.name, nowMs)

    /** tag 归属的 pending key 聚合：一个 tag 对应多个 key 时通知要聚合。 */
    @Query(
        """
        SELECT notificationTag, COUNT(*) AS requestCount FROM notification_outbox
        WHERE identityHash = :identityHash
        GROUP BY notificationTag
        ORDER BY MAX(createdAtMs) DESC
        """,
    )
    fun observeTags(identityHash: String): Flow<List<OutboxTagRow>>

    /**
     * 已结算条目（`Sent` / `Cancelled` / `Skipped`）的常规回收。
     * 没有 TTL 参数：这三态就是“本机不再需要对它做任何事”，保留只有诊断价值；
     * 退役身份的整组删除由 [IdentityDao.deleteOutboxFor] 负责，不走这里。
     */
    @Query("DELETE FROM notification_outbox WHERE identityHash = :identityHash AND state IN ('Sent', 'Cancelled', 'Skipped')")
    suspend fun purgeSettled(identityHash: String): Int

    @Query("SELECT COUNT(*) AS total FROM notification_outbox WHERE identityHash = :identityHash AND state = 'New'")
    fun observeNewCount(identityHash: String): Flow<CountRow>

    @Query("DELETE FROM notification_outbox WHERE identityHash = :identityHash")
    suspend fun clearOutbox(identityHash: String): Int
}
