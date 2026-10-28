package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SyncOwnerRole

/** 全量名册拉取的触发原因。 */
enum class RosterPullReason { ForegroundStale, WorkerResyncPending, WorkerStale }

data class RosterState(
    val role: SyncOwnerRole,
    val rosterAgeMs: Long?,
    val hostResyncPending: Boolean,
)

sealed interface RosterPullDecision {
    data class Pull(val reason: RosterPullReason) : RosterPullDecision
    data object Reuse : RosterPullDecision
}

/** watermark 变更：相等与回退都不写。 */
data class WatermarkUpdate(val sessionId: SessionId, val lastSeq: EventSeq?, val change: WatermarkChange)

object RosterPlanner {

    fun ageMs(nowMs: Long, syncedAtMs: Long?): Long? = if (syncedAtMs == null) null else nowMs - syncedAtMs

    fun decide(state: RosterState, tuning: SyncTuning): RosterPullDecision = when (state.role) {
        SyncOwnerRole.None -> RosterPullDecision.Reuse
        SyncOwnerRole.Foreground -> {
            if (state.rosterAgeMs == null || state.rosterAgeMs > tuning.rosterForegroundStaleMs) {
                RosterPullDecision.Pull(RosterPullReason.ForegroundStale)
            } else {
                RosterPullDecision.Reuse
            }
        }

        SyncOwnerRole.Worker -> when {
            state.hostResyncPending -> RosterPullDecision.Pull(RosterPullReason.WorkerResyncPending)
            state.rosterAgeMs == null -> RosterPullDecision.Pull(RosterPullReason.WorkerStale)
            state.rosterAgeMs > tuning.rosterWorkerStaleMs ->
                RosterPullDecision.Pull(RosterPullReason.WorkerStale)
            else -> RosterPullDecision.Reuse
        }
    }

    fun shouldPullFullRoster(
        role: SyncOwnerRole,
        rosterAgeMs: Long?,
        hostResyncPending: Boolean,
        tuning: SyncTuning,
    ): Boolean = decide(RosterState(role, rosterAgeMs, hostResyncPending), tuning) is RosterPullDecision.Pull

    /** 15 分钟周期落在 10min 与 30min 之间：Worker 默认重用已持久化名册。 */
    fun workerRosterCadenceOk(tuning: SyncTuning): Boolean =
        tuning.rosterWorkerStaleMs > tuning.rosterForegroundStaleMs

    /** watermark 只移动已存在会话的位置，与建行无关。 */
    fun watermarkUpdate(sessionId: SessionId, current: EventSeq?, arrived: EventSeq?): WatermarkUpdate =
        if (arrived == null) {
            WatermarkUpdate(sessionId, current, WatermarkChange.Ignore)
        } else {
            WatermarkUpdate(sessionId, arrived, ResetPlanner.advanceWatermark(current, arrived))
        }

    /** 名册是增删的唯一权威：差集之外的会话一律不动。 */
    fun removedFromRoster(known: Set<SessionId>, roster: List<RosterEntry>): Set<SessionId> =
        known - roster.map { it.sessionId }.toSet()

    fun insertedByRoster(known: Set<SessionId>, roster: List<RosterEntry>): List<SessionId> =
        roster.filterNot { it.sessionId in known }.map { it.sessionId }
}
