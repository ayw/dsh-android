package ai.deepseek.dsh.android.database

import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * `applied_island`：会话里“已经投影过的连续区间”。
 *
 * **本 DAO 不做任何区间合并**（D26）：合并/相邻折叠的唯一实现是 `:core:domain` 的 `Islands`
 * （`applyIslands` / `normalize` / `trimFrontier` / `coveredHead`，纯函数）。SQL 侧写
 * `DELETE FROM applied_island WHERE endSeq BETWEEN …` 式的折叠就是第二套真相，一旦与 domain 的相邻判定
 * 不一致，覆盖率与 frontier 会各自漂移。这里只提供原语：调用方先算好已合并的区间列表，再整体替换。
 *
 * [replaceIslands] 在同一事务里删除该会话的全部区间并批量写入给定列表，因此：
 *  - 幂等：同一个列表重复写入结果相同；
 *  - `islandMergeGap` 的取值（D12）完全不进本模块，DAO 签名里没有 gap 参数；
 *  - 岛数量本来就小（frontier 之前的会被 `Islands.trimFrontier` 删掉），全量替换的代价可忽略。
 */
@Dao
interface IslandDao {

    /** 单会话读出现有区间（升序），供合并与投影校验。 */
    @Query(
        """
        SELECT * FROM applied_island WHERE serverId = :serverId AND sessionId = :sessionId
        ORDER BY startSeq ASC
        """,
    )
    suspend fun islands(serverId: String, sessionId: String): List<AppliedIslandEntity>

    @Query(
        """
        SELECT * FROM applied_island WHERE serverId = :serverId AND sessionId = :sessionId
        ORDER BY startSeq ASC
        """,
    )
    fun observeIslands(serverId: String, sessionId: String): Flow<List<AppliedIslandEntity>>

    /** 纯插入（冲突即失败）：调用方保证列表已归一，本模块不检查相交。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIslands(islands: List<AppliedIslandEntity>)

    @Query("DELETE FROM applied_island WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun deleteIslands(serverId: String, sessionId: String): Int

    /**
     * 写原语（D26 的 `replaceIslands`）：删除该会话的全部区间 + 批量插入已合并列表，一个事务内完成。
     * 入参是 `:core:model` 的 [AppliedIsland] 列表，[serverId] / [sessionId] 单独给出
     * （D07：所有表以 `(serverId, sessionId)` 为键，因此签名比 D26 的简写多一个 `serverId`）。
     *
     * 空列表 = 清空（reset 场景：G06 的 tombstone → baseline → events → cursor 顺序里先清旧区间）。
     * 不返回结果：调用方手里已经有它自己算出的列表，再读一次只会多一次查询。
     */
    @Transaction
    suspend fun replaceIslands(serverId: String, sessionId: String, spans: List<AppliedIsland>) {
        deleteIslands(serverId, sessionId)
        if (spans.isEmpty()) return
        val sid = ServerId(serverId)
        val cid = SessionId(sessionId)
        insertIslands(spans.map { AppliedIslandEntity(sid, cid, it.startSeq, it.endSeq) })
    }

    @Query("SELECT COUNT(*) AS total FROM applied_island WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun islandCount(serverId: String, sessionId: String): CountRow
}
