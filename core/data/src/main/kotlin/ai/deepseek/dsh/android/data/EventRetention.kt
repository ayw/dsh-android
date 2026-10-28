package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DiagnosticsCounters
import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.repository.domainCall
import androidx.room.withTransaction
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId

/**
 * 原始帧保留裁剪（验收 G12 / D07）：每个会话只留最新 `maxEventsPerSession` 行。
 *
 * 界由 [SyncTuning.maxEventsPerSession] 给，不在这里写字面量。两条不可让的约束：
 *  1. **只删已覆盖前缀之内、且严格早于新界的行**——DAO 的删除条件带 `seq < contiguousSeq` 守卫，
 *     没有连续前缀（或压根没有 head 行）的会话一行都不删，投影因此永远能读到它声称读到的东西；
 *  2. 删过就必须写下 `prunedBelowSeq` 并置 `historyIncomplete`，否则 UI 会把“被裁掉的旧历史”
 *     说成完整历史。裁剪是本地容量决定，不是服务器上的空洞。
 *
 * @return 实际删除的行数，同时作为 `retentionTrimmed` 诊断计数累加。
 */
class EventRetention(
    private val db: DshDatabase,
    private val clock: Clock,
    private val tuning: SyncTuning = SyncTuning.DEFAULT,
) {

    suspend fun trim(): Result<Int> = domainCall {
        val events = db.sessionEventDao()
        val heads = db.sessionHeadDao()
        val cap = tuning.maxEventsPerSession
        var trimmed = 0
        db.withTransaction {
            events.selectOverCapSessions(cap).forEach { row ->
                val threshold = events.thresholdSeqOf(row.serverId, row.sessionId, cap) ?: return@forEach
                val removed = events.pruneOlderThan(row.serverId, row.sessionId, threshold)
                if (removed > 0) {
                    // 连续前缀会把删除夹到更早的位置：写下夹后的界，而不是候选界。
                    // 记 `threshold` 会声称 `[contiguousSeq, threshold)` 那段也没了，历史修复就会跳过还在的帧（D08）。
                    val contiguous = heads.head(row.serverId, row.sessionId)?.contiguousSeq?.value
                    heads.notePrunedFloor(
                        row.serverId,
                        row.sessionId,
                        contiguous?.let { minOf(threshold, it) } ?: threshold,
                    )
                    trimmed += removed
                }
            }
            if (trimmed > 0) {
                db.diagnosticsDao()
                    .incrementCounter(DiagnosticsCounters.RETENTION_TRIMMED, trimmed.toLong(), clock.wallClockMs())
            }
        }
        // 只记条数：会话 id 都不写，裁剪是纯本地事件（G09 记录纪律）。
        trimmed
    }

    /** 单会话视图：给定会话在裁剪后还剩多少行、界在哪。诊断页与测试用。 */
    suspend fun snapshot(serverId: ServerId, sessionId: SessionId): Result<RetentionSnapshot> = domainCall {
        val stored = db.sessionEventDao().eventCount(serverId.value, sessionId.value).total
        val floor = db.sessionHeadDao().head(serverId.value, sessionId.value)?.prunedBelowSeq?.value
        RetentionSnapshot(storedEvents = stored, prunedBelowSeq = floor, capPerSession = tuning.maxEventsPerSession)
    }
}

data class RetentionSnapshot(
    val storedEvents: Int,
    /** 严格早于它的行已被本机裁掉；`null` 表示这个会话从未裁剪过。 */
    val prunedBelowSeq: Long?,
    val capPerSession: Int,
)
