package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.database.IdentityRetiredEntity
import ai.deepseek.dsh.android.database.SyncOwnerEntity
import ai.deepseek.dsh.android.database.toCursor
import ai.deepseek.dsh.android.database.toEntity
import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.SyncOwnerRecord
import ai.deepseek.dsh.android.domain.SyncStateStore
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SyncOwnerRole
import ai.deepseek.dsh.android.model.TransportCursor
import ai.deepseek.dsh.android.network.DshLog
import androidx.room.withTransaction

/**
 * `SyncStateStore` 的 Room 实现：传输游标（按 identity+channel 存 seq/epoch）+ 前后台所有权（D10）
 * + 身份退役账本（D14）。
 *
 * 所有权是**单行表**，因此 CAS 是跨进程的唯一真相：`casOwner` 在同一个写事务里先建行再比较，
 * 抢不到只返回 false（不抛异常），调用方据此映射 Busy → `Result.retry()`（D10/G18）。
 *
 * 端口没有 `serverId`（`markIdentityRetired` 只有身份），因此这里用 `server.identityHash` 反查归属；
 * 反查不到时按配对规则用摘要前缀造一个占位 id（`serverId` 列非空，且退役账本只用于归因，不影响投影）。
 */
class RoomSyncStateStore(
    private val db: DshDatabase,
    private val clock: Clock,
) : SyncStateStore {

    private val cursors get() = db.syncStateDao()
    private val ownerDao get() = db.syncOwnerDao()
    private val identities get() = db.identityDao()
    private val servers get() = db.serverDao()

    override suspend fun cursor(identity: ConnectionIdentity, channel: ChannelKind): TransportCursor? =
        cursors.cursor(identity.digest, channel.value)?.toCursor()

    /** 端口的 `put` 就是无条件覆盖（前进式守卫在 [RoomDurableStore] 的 cursor 段落里）。 */
    override suspend fun putCursor(cursor: TransportCursor) {
        cursors.putCursors(listOf(cursor.toEntity(clock.wallClockMs())))
    }

    override suspend fun clearTransportCursors(identity: ConnectionIdentity) {
        cursors.clearCursors(identity.digest)
    }

    /** 单行表的当前所有权快照：行不存在时先建（`None`），因此这里永远返回可读记录。 */
    override suspend fun owner(): SyncOwnerRecord = db.withTransaction {
        val nowMs = clock.wallClockMs()
        ownerDao.ensureOwnerRow(nowMs)
        val row = ownerDao.owner(SyncOwnerEntity.SINGLETON)
        SyncOwnerRecord(
            role = row?.role ?: SyncOwnerRole.None,
            holderId = row?.holderId?.takeIf { it.isNotEmpty() },
            claimedAtMs = row?.updatedAtMs ?: nowMs,
            foregroundRequestedAtMs = row?.foregroundRequestedAtMs,
        )
    }

    /**
     * CAS（D10）：`expected` 命中才改写。抢到 Foreground 时顺带清掉前台接管请求 ——
     * 已经拿到所有权的前台不需要再等标记（`SyncOwnerDao.casOwner` 的 `clearForeground`）。
     */
    override suspend fun casOwner(
        expected: SyncOwnerRole,
        next: SyncOwnerRole,
        holderId: String,
        nowMs: Long,
    ): Boolean = db.withTransaction {
        ownerDao.ensureOwnerRow(nowMs)
        val claimed = ownerDao.casOwner(
            ownerKey = SyncOwnerEntity.SINGLETON,
            expectedRole = expected.name,
            nextRole = next.name,
            nextHolder = holderId,
            clearForeground = next == SyncOwnerRole.Foreground,
            nowMs = nowMs,
        ) == 1
        if (!claimed) {
            // 只记角色与本地 holderId：不含凭据、URL、会话标题。
            DshLog.d("owner cas lost expected=$expected next=$next holder=$holderId")
        }
        claimed
    }

    /** 前台接管请求：`null` 即撤销（不改变 owner，只立标记，G18）。 */
    override suspend fun setForegroundRequestedAt(nowMs: Long?) {
        ownerDao.setForegroundRequested(nowMs)
    }

    /** 轮换/删除配置时登记旧身份：**只登记账本，不清传输态**（保留窗口给迟到帧归因，D14）。 */
    override suspend fun markIdentityRetired(identity: ConnectionIdentity, retiredAtMs: Long) {
        val serverId = servers.serverByIdentity(identity.digest)?.serverId ?: ServerId(identity.digest.take(DIGEST_ID_PREFIX))
        identities.markRetired(IdentityRetiredEntity(identity.digest, serverId, retiredAtMs))
    }

    /** 幂等且可崩溃续跑：一个写事务里枚举 → 清单个身份传输态 → 删账本行（A→B→A 由 SQL 的 live 守卫挡住）。 */
    override suspend fun purgeEligibleIdentities(nowMs: Long, graceMs: Long): List<ConnectionIdentity> =
        identities.purgeRetiredIdentities(nowMs, graceMs).map { ConnectionIdentity(it) }

    private companion object {
        /** 占位 serverId 取摘要前缀：与 `PairingUseCase` 的私有 `SERVER_ID_PREFIX_LEN` 同一口径（该常量不可见）。 */
        const val DIGEST_ID_PREFIX = 16
    }
}
