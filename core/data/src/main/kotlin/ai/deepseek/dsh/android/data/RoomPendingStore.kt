package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.database.PendingBadgeRow
import ai.deepseek.dsh.android.database.pendingSweepKey
import ai.deepseek.dsh.android.database.toEntity
import ai.deepseek.dsh.android.database.toModel
import ai.deepseek.dsh.android.domain.DurableStore
import ai.deepseek.dsh.android.domain.PendingStore
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `PendingStore` 的 Room 实现（D13）。
 *
 * 两条写路径刻意不同：
 *  - [upsert] 走**无条件整行覆盖**（`putPending`）：端口没有 `asOfSeq` 上下文，本地提交/修复场景必须能落；
 *  - 事件流的幂等写走 [RoomDurableStore] 的 projection 段落（`applyPendingChanges` + `asOfSeq` 守卫），
 *    陈旧帧因此在 SQL 层是 0 行 no-op（D13），不靠 Kotlin 侧比较。
 *
 * `dismiss` 只写 `dismissedAtMs`、不改 status；`tombstone` 写 `tombstonedAtMs` 并转 Stale —— 两者不互替（G07）。
 */
class RoomPendingStore(
    private val db: DshDatabase,
) : PendingStore {

    private val pending get() = db.pendingDao()

    override fun observePending(serverId: ServerId): Flow<List<PendingRequest>> =
        pending.observePending(serverId.value).map { rows -> rows.map { it.toModel() } }

    override fun observeForSession(serverId: ServerId, sessionId: SessionId): Flow<List<PendingRequest>> =
        pending.observePendingForSession(serverId.value, sessionId.value).map { rows -> rows.map { it.toModel() } }

    override suspend fun upsert(request: PendingRequest) {
        pending.putPending(listOf(request.toEntity(asOfSeqOf(request))))
    }

    override suspend fun dismiss(serverId: ServerId, kind: PendingKind, requestId: RequestId, nowMs: Long) {
        pending.dismiss(serverId.value, kind, requestId, nowMs)
    }

    override suspend fun tombstone(serverId: ServerId, kind: PendingKind, requestId: RequestId, nowMs: Long) {
        pending.tombstone(serverId.value, kind, requestId, PendingCompleteness.Stale, nowMs)
    }

    /** sweep 的两步（markPresent + tombstoneUnconfirmed）由 DAO 在同一个事务里完成，本层不复制状态机。 */
    override suspend fun sweepAbsent(
        serverId: ServerId,
        presentKeys: Set<DurableStore.PendingKey>,
        nowMs: Long,
    ): Int = pending.sweepAbsent(
        serverId = serverId.value,
        presentKeys = presentKeys.map { pendingSweepKey(it.kind, it.requestId) },
        sweepAtMs = nowMs,
        nowMs = nowMs,
        completeness = PendingCompleteness.Stale.name,
    )

    /** 观察单行（resolve 路径的读前置）：端口没有这个形状，只有本层用得到。 */
    suspend fun find(serverId: ServerId, kind: PendingKind, requestId: RequestId): PendingRequest? =
        pending.pending(serverId.value, kind.name, requestId.value)?.toModel()

    /** 手动划掉的读改写：端口的 `dismiss` 是命令，这里补上“先取现值再走 reducer”的读取面。 */
    suspend fun currentBadge(serverId: ServerId): PendingBadgeRow = pending.pendingBadge(serverId.value)

    private fun asOfSeqOf(request: PendingRequest): Long = request.anchorSeq?.value ?: 0L
}
