package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.HistoryPage
import ai.deepseek.dsh.android.model.HistoryRequest
import ai.deepseek.dsh.android.model.NeedsResync
import ai.deepseek.dsh.android.model.ResyncReason
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition

/** 空洞性质：传输层空洞与“历史读不完”是两种状态，退出策略不同。 */
enum class HoleKind { TransportGap, HistoryRepair, HistoryIncomplete }

enum class RepairResult {
    Opened,
    Cleared,
    Continue,
    RoundExhausted,
    Degraded,
    HistoryIncomplete,
    Ignored,
    ManualImmediate,
}

/**
 * 一个 (serverId, sessionId) 的待修复空洞。
 * `attempts` 计的是“未覆盖的修复轮”：一轮最多 `historyRepairPageBudget` 页，
 * 连续 `needsResyncMaxAttempts` 轮没覆盖到 expectedSeq 就 degraded 并停止自动 resync。
 */
data class ResyncState(
    val serverId: ServerId,
    val sessionId: SessionId,
    val expectedSeq: EventSeq,
    val reason: ResyncReason,
    val attempts: Int = 0,
    val degraded: Boolean = false,
    val historyIncomplete: Boolean = false,
    val manualResyncAvailable: Boolean = true,
) {
    val holeKind: HoleKind
        get() = when {
            historyIncomplete -> HoleKind.HistoryIncomplete
            reason == ResyncReason.SeqGap -> HoleKind.TransportGap
            else -> HoleKind.HistoryRepair
        }

    fun needsResync(): NeedsResync = NeedsResync(sessionId, reason, expectedSeq, attempts, degraded)

    /** 下一轮自动 resync 的准入门：degraded 与终态都挡住。 */
    val allowsAutomaticRepair: Boolean get() = !degraded && !historyIncomplete
}

/** 一次历史读的结果摘要：`coveredHead` 决定能否清除，`frontier` 决定续跑点。 */
data class HistoryRead(
    val serverId: ServerId,
    val sessionId: SessionId,
    val coveredHead: EventSeq?,
    val frontier: EventSeq?,
    val hasMore: Boolean,
) {
    companion object {
        fun of(page: HistoryPage, serverId: ServerId): HistoryRead = HistoryRead(
            serverId = serverId,
            sessionId = page.sessionId,
            coveredHead = page.frames.maxByOrNull { it.seq.value }?.seq,
            frontier = page.readFrontier,
            hasMore = page.hasMore,
        )
    }
}

/** 一轮修复读：预算内分页，续跑点只前移、绝不回到 head。 */
data class RepairRun(val sessionId: SessionId, val beforeSeq: EventSeq?, val pagesUsed: Int)

data class ResyncStep(
    val state: ResyncState?,
    val result: RepairResult,
    val run: RepairRun?,
    val nextRequest: HistoryRequest?,
) {
    val needsResync: NeedsResync? get() = state?.needsResync()
}

object ResyncPolicy {

    /**
     * expectedSeq 只有两种互斥来源：已有 watermark 用 `contiguousSeq + 1`；
     * 没有 watermark 才 seed 到 head。已存在的记录不会被更新的位置改写。
     */
    fun expectedSeq(position: SessionPosition, head: EventSeq, existing: ResyncState?): EventSeq {
        if (existing != null) return existing.expectedSeq
        return Positions.nextExpected(position) ?: head
    }

    fun raise(
        serverId: ServerId,
        sessionId: SessionId,
        position: SessionPosition,
        head: EventSeq,
        reason: ResyncReason,
        existing: ResyncState?,
        tuning: SyncTuning,
    ): ResyncStep {
        val state = existing ?: ResyncState(serverId, sessionId, expectedSeq(position, head, null), reason)
        val run = RepairRun(sessionId, beforeSeq = null, pagesUsed = 0)
        return ResyncStep(state, RepairResult.Opened, run, nextRepairRequest(run, tuning))
    }

    fun step(state: ResyncState, read: HistoryRead, run: RepairRun, tuning: SyncTuning): ResyncStep {
        if (read.serverId != state.serverId || read.sessionId != state.sessionId) {
            return ResyncStep(state, RepairResult.Ignored, run, null)
        }
        val covered = read.coveredHead
        if (covered != null && covered.value >= state.expectedSeq.value) {
            return ResyncStep(null, RepairResult.Cleared, null, null)
        }
        val advanced = advanceRepairRun(run, read)
        if (!read.hasMore) {
            val terminal = state.copy(
                attempts = state.attempts + 1,
                degraded = true,
                historyIncomplete = true,
            )
            return ResyncStep(terminal, RepairResult.HistoryIncomplete, advanced, null)
        }
        val next = nextRepairRequest(advanced, tuning)
        if (next != null) return ResyncStep(state, RepairResult.Continue, advanced, next)
        val attempts = state.attempts + 1
        val degraded = attempts >= tuning.needsResyncMaxAttempts
        return ResyncStep(
            state = state.copy(attempts = attempts, degraded = degraded),
            result = if (degraded) RepairResult.Degraded else RepairResult.RoundExhausted,
            run = advanced,
            nextRequest = null,
        )
    }

    /**
     * 手动“立即 resync”：清掉 degraded 与终态并重新计数。
     * `HistoryIncomplete` 只允许一次；额度用尽后返回 null，调用方不得再次自动重试。
     */
    fun manualResync(state: ResyncState, tuning: SyncTuning): ResyncStep? {
        if (!state.manualResyncAvailable) return null
        val reset = state.copy(
            reason = ResyncReason.ManualImmediate,
            attempts = 0,
            degraded = false,
            historyIncomplete = false,
            manualResyncAvailable = state.manualResyncAvailable && !state.historyIncomplete,
        )
        val run = startRepairRun(state.sessionId, resumeFrom = null)
        return ResyncStep(reset, RepairResult.ManualImmediate, run, nextRepairRequest(run, tuning))
    }

    fun startRepairRun(sessionId: SessionId, resumeFrom: EventSeq?): RepairRun =
        RepairRun(sessionId, beforeSeq = resumeFrom, pagesUsed = 0)

    /** 预算耗尽即返回 null：修复读必须被页数预算约束。 */
    fun nextRepairRequest(run: RepairRun, tuning: SyncTuning): HistoryRequest? {
        if (run.pagesUsed >= tuning.historyRepairPageBudget) return null
        return HistoryRequest(run.sessionId, beforeSeq = run.beforeSeq, maxMessages = tuning.historyPageSize)
    }

    fun repairPagesRemaining(run: RepairRun, tuning: SyncTuning): Int =
        maxOf(0, tuning.historyRepairPageBudget - run.pagesUsed)

    /**
     * 续跑点是本页最小 seq（`readFrontier`），并且是下一页的 exclusive 边界：
     * 空页保持原位置不动，绝不因为拿不到 frontier 就回到 head。
     */
    fun advanceRepairRun(run: RepairRun, read: HistoryRead): RepairRun = RepairRun(
        sessionId = run.sessionId,
        beforeSeq = if (read.frontier != null) read.frontier else run.beforeSeq,
        pagesUsed = run.pagesUsed + 1,
    )
}
