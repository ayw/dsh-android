package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SyncOwnerRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val rosterTuning = SyncTuning.DEFAULT
private val rosterSession = SessionId("s-1")
private val rosterOtherSession = SessionId("s-2")

private fun rosterSeq(value: Long): EventSeq = EventSeq(value)

private fun rosterEntry(session: SessionId, rank: Long): RosterEntry = RosterEntry(
    sessionId = session,
    title = "会话 $rank",
    updatedAtMs = rank,
    running = false,
    archived = false,
    workspaceId = null,
    rosterRank = rank,
)

private fun rosterState(
    role: SyncOwnerRole,
    ageMs: Long?,
    hostResyncPending: Boolean = false,
): RosterState = RosterState(role = role, rosterAgeMs = ageMs, hostResyncPending = hostResyncPending)

class RosterPlannerTest {

    @Test
    fun foregroundPullsFullRosterOnlyWhenAgeExceedsStaleThresholdAfterReset() {
        val stale = rosterTuning.rosterForegroundStaleMs + 1
        assertEquals(
            RosterPullDecision.Pull(RosterPullReason.ForegroundStale),
            RosterPlanner.decide(rosterState(SyncOwnerRole.Foreground, stale), rosterTuning),
        )
        assertTrue(RosterPlanner.shouldPullFullRoster(SyncOwnerRole.Foreground, stale, false, rosterTuning))
    }

    @Test
    fun foregroundReusesRosterAtTheExactStaleThresholdAfterReset() {
        val atThreshold = rosterTuning.rosterForegroundStaleMs
        assertEquals(RosterPullDecision.Reuse, RosterPlanner.decide(rosterState(SyncOwnerRole.Foreground, atThreshold), rosterTuning))
        assertFalse(RosterPlanner.shouldPullFullRoster(SyncOwnerRole.Foreground, atThreshold, false, rosterTuning))
    }

    @Test
    fun foregroundWithoutRosterTimestampPullsFullRosterAfterReset() {
        assertEquals(
            RosterPullDecision.Pull(RosterPullReason.ForegroundStale),
            RosterPlanner.decide(rosterState(SyncOwnerRole.Foreground, null), rosterTuning),
        )
    }

    @Test
    fun workerPullsFullRosterWhenHostResyncPendingAfterReset() {
        val fresh = rosterTuning.rosterForegroundStaleMs / 2
        assertEquals(
            RosterPullDecision.Pull(RosterPullReason.WorkerResyncPending),
            RosterPlanner.decide(rosterState(SyncOwnerRole.Worker, fresh, hostResyncPending = true), rosterTuning),
        )
    }

    @Test
    fun workerPullsFullRosterOnlyAboveItsOwnStaleThresholdAfterReset() {
        val beyond = rosterTuning.rosterWorkerStaleMs + 1
        assertEquals(
            RosterPullDecision.Pull(RosterPullReason.WorkerStale),
            RosterPlanner.decide(rosterState(SyncOwnerRole.Worker, beyond), rosterTuning),
        )
        assertEquals(
            RosterPullDecision.Reuse,
            RosterPlanner.decide(rosterState(SyncOwnerRole.Worker, rosterTuning.rosterWorkerStaleMs), rosterTuning),
        )
    }

    @Test
    fun workerCadenceAgeBetweenThresholdsKeepsRosterAfterReset() {
        val cadenceAgeMs = (rosterTuning.rosterForegroundStaleMs + rosterTuning.rosterWorkerStaleMs) / 2
        assertTrue(RosterPlanner.workerRosterCadenceOk(rosterTuning))
        assertEquals(RosterPullDecision.Reuse, RosterPlanner.decide(rosterState(SyncOwnerRole.Worker, cadenceAgeMs), rosterTuning))
        assertFalse(RosterPlanner.shouldPullFullRoster(SyncOwnerRole.Worker, cadenceAgeMs, false, rosterTuning))
        assertEquals(
            RosterPullDecision.Pull(RosterPullReason.ForegroundStale),
            RosterPlanner.decide(rosterState(SyncOwnerRole.Foreground, cadenceAgeMs), rosterTuning),
        )
    }

    @Test
    fun workerWithoutRosterTimestampPullsFullRosterOnceAfterReset() {
        assertEquals(
            RosterPullDecision.Pull(RosterPullReason.WorkerStale),
            RosterPlanner.decide(rosterState(SyncOwnerRole.Worker, null), rosterTuning),
        )
    }

    @Test
    fun ownerlessRoleNeverPullsFullRosterAfterReset() {
        assertEquals(RosterPullDecision.Reuse, RosterPlanner.decide(rosterState(SyncOwnerRole.None, null), rosterTuning))
        val farBeyondBoth = rosterTuning.rosterWorkerStaleMs * 2
        assertFalse(RosterPlanner.shouldPullFullRoster(SyncOwnerRole.None, farBeyondBoth, true, rosterTuning))
    }

    @Test
    fun rosterAgeDerivesFromTheSyncedTimestampAfterReset() {
        val syncedAtMs = 1_000L
        val nowMs = syncedAtMs + rosterTuning.rosterForegroundStaleMs
        assertEquals(rosterTuning.rosterForegroundStaleMs, RosterPlanner.ageMs(nowMs, syncedAtMs))
        assertEquals(null, RosterPlanner.ageMs(nowMs, null))
    }

    @Test
    fun rosterIsTheOnlyAuthorityForRemovingSessionRowsAfterReset() {
        val known = setOf(rosterSession, rosterOtherSession)
        val roster = listOf(rosterEntry(rosterSession, rank = 1))
        assertEquals(setOf(rosterOtherSession), RosterPlanner.removedFromRoster(known, roster))
        assertEquals(emptyList(), RosterPlanner.insertedByRoster(known, roster))
    }

    @Test
    fun rosterInsertsOnlySessionsPresentInServerListAfterReset() {
        val roster = listOf(rosterEntry(rosterSession, rank = 2), rosterEntry(rosterOtherSession, rank = 3))
        assertEquals(listOf(rosterOtherSession), RosterPlanner.insertedByRoster(setOf(rosterSession), roster))
        assertEquals(
            roster.map { it.sessionId },
            RosterPlanner.insertedByRoster(emptySet(), roster),
        )
    }

    @Test
    fun rosterWatermarkIgnoresMissingOrBackwardLastSeqAfterReset() {
        val missing = RosterPlanner.watermarkUpdate(rosterSession, current = rosterSeq(10), arrived = null)
        assertEquals(WatermarkChange.Ignore, missing.change)
        assertEquals(rosterSeq(10), missing.lastSeq)

        val backward = RosterPlanner.watermarkUpdate(rosterSession, current = rosterSeq(10), arrived = rosterSeq(9))
        assertEquals(WatermarkChange.Ignore, backward.change)
        assertEquals(rosterSeq(9), backward.lastSeq)

        val forward = RosterPlanner.watermarkUpdate(rosterSession, current = rosterSeq(10), arrived = rosterSeq(11))
        assertEquals(WatermarkChange.Advance, forward.change)
    }

    @Test
    fun emptyRosterRemovesEveryKnownSessionRowAfterReset() {
        val knownHere = setOf(rosterSession, rosterOtherSession)
        assertEquals(knownHere, RosterPlanner.removedFromRoster(knownHere, emptyList()))
        assertEquals(setOf(), RosterPlanner.removedFromRoster(emptySet(), listOf(rosterEntry(rosterSession, rank = 1))))
    }
}
