package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.SyncStateStore
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.model.SyncOwnerRole

/** 抢位结果：只有 [Acquired] 允许进入同步临界区。 */
enum class OwnerClaim { Acquired, Busy, YieldToForeground }

/**
 * 前后台所有权的裁决（D10 / 验收 G18）。
 *
 * Room 的 `sync_owner` 单行是跨进程唯一真相，因此这里不缓存角色，每次都读一次再 CAS：
 * CAS 失败就是别人赢了，返回 [OwnerClaim.Busy]，绝不重试到赢为止（重试属于 WorkManager 层）。
 *
 * 三条不对称是刻意的：
 * - Worker **不抢**前台：前台在场就让它做完，Worker 只等标记（[OwnerClaim.YieldToForeground]）；
 * - 前台**可以**抢 Worker：用户回到前台的延迟预算比一轮后台同步重要；
 * - 崩溃遗留的 Worker 持有按回合预算判定为过期，由下一个 Worker 接管，否则所有权永久卡死。
 */
class SyncOwnerArbiter(
    private val store: SyncStateStore,
    private val clock: Clock,
    private val tuning: SyncTuning = SyncTuning.DEFAULT,
) {

    /** 一次持有的最长时长：超过它，持有者要么已崩溃要么被系统杀掉。由回合预算推导，不新增旋钮（D12）。 */
    private val staleClaimMs: Long = tuning.workerRoundBudgetMs * tuning.workerMaxRounds

    /** Worker 进临界区前调用。 */
    suspend fun claimForWorker(holderId: String): OwnerClaim {
        val nowMs = clock.wallClockMs()
        val current = store.owner()
        return when (current.role) {
            // 前台持有时 Worker 永远等待：不抢、不轮询抢占，交给 WorkManager 的重试预算。
            SyncOwnerRole.Foreground -> OwnerClaim.Busy

            SyncOwnerRole.Worker ->
                if (nowMs - current.claimedAtMs > staleClaimMs &&
                    store.casOwner(SyncOwnerRole.Worker, SyncOwnerRole.Worker, holderId, nowMs)
                ) {
                    OwnerClaim.Acquired
                } else {
                    OwnerClaim.Busy
                }

            SyncOwnerRole.None ->
                if (store.casOwner(SyncOwnerRole.None, SyncOwnerRole.Worker, holderId, nowMs)) {
                    OwnerClaim.Acquired
                } else {
                    OwnerClaim.Busy
                }
        }
    }

    /** 前台 actor 接管：Worker 的持有可以在回合边界被让出。 */
    suspend fun claimForForeground(holderId: String): OwnerClaim {
        val nowMs = clock.wallClockMs()
        val expected = when (store.owner().role) {
            SyncOwnerRole.Foreground -> return OwnerClaim.Acquired
            SyncOwnerRole.Worker -> SyncOwnerRole.Worker
            SyncOwnerRole.None -> SyncOwnerRole.None
        }
        return if (store.casOwner(expected, SyncOwnerRole.Foreground, holderId, nowMs)) {
            OwnerClaim.Acquired
        } else {
            OwnerClaim.YieldToForeground
        }
    }

    /**
     * 回合边界自检：角色被别人改写、或前台立了接管标记，Worker 就该交还并结束本轮。
     * 前台接管标记不改变所有者，所以必须单独读一次（G18）。
     */
    suspend fun shouldYield(holderId: String): Boolean {
        val current = store.owner()
        return current.role != SyncOwnerRole.Worker ||
            current.holderId != holderId ||
            current.foregroundRequestedAtMs != null
    }

    /** 释放：只有仍然持有的人才放得掉，避免把别人的所有权清成 `None`。 */
    suspend fun releaseWorker(holderId: String): Boolean {
        val current = store.owner()
        if (current.role != SyncOwnerRole.Worker || current.holderId != holderId) return false
        return store.casOwner(SyncOwnerRole.Worker, SyncOwnerRole.None, holderId, clock.wallClockMs())
    }

    /** 前台离开：清掉所有权与接管标记，把临界区让回 Worker。 */
    suspend fun releaseForeground(holderId: String): Boolean {
        val current = store.owner()
        if (current.role != SyncOwnerRole.Foreground) return false
        if (current.holderId != null && current.holderId != holderId) return false
        val released = store.casOwner(SyncOwnerRole.Foreground, SyncOwnerRole.None, holderId, clock.wallClockMs())
        if (released) store.setForegroundRequestedAt(null)
        return released
    }

    /** 前台请求接管（不改变所有者，只立标记）。`false` 即撤销请求。 */
    suspend fun requestForegroundHandoff(active: Boolean): Unit =
        store.setForegroundRequestedAt(if (active) clock.wallClockMs() else null)
}
