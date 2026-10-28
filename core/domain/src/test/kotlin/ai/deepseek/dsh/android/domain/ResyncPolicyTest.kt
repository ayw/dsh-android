package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.HistoryPage
import ai.deepseek.dsh.android.model.ResyncReason
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TransportFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val resyncTuning = SyncTuning.DEFAULT
private val resyncServer = ServerId("srv-1")
private val resyncOtherServer = ServerId("srv-2")
private val resyncSession = SessionId("s-1")
private val resyncOtherSession = SessionId("s-2")

private fun resyncPosition(contiguous: Long?, appliedMax: Long?): SessionPosition = SessionPosition(
    contiguousSeq = if (contiguous == null) null else EventSeq(contiguous),
    appliedMaxSeq = if (appliedMax == null) null else EventSeq(appliedMax),
)

private fun resyncSeq(value: Long): EventSeq = EventSeq(value)

private fun resyncState(
    expected: Long,
    reason: ResyncReason = ResyncReason.HistoryRepair,
    attempts: Int = 0,
    degraded: Boolean = false,
    historyIncomplete: Boolean = false,
    serverId: ServerId = resyncServer,
    sessionId: SessionId = resyncSession,
    manualResyncAvailable: Boolean = true,
): ResyncState = ResyncState(
    serverId = serverId,
    sessionId = sessionId,
    expectedSeq = resyncSeq(expected),
    reason = reason,
    attempts = attempts,
    degraded = degraded,
    historyIncomplete = historyIncomplete,
    manualResyncAvailable = manualResyncAvailable,
)

private fun resyncRead(
    coveredHead: Long?,
    frontier: Long?,
    hasMore: Boolean,
    serverId: ServerId = resyncServer,
    sessionId: SessionId = resyncSession,
): HistoryRead = HistoryRead(
    serverId = serverId,
    sessionId = sessionId,
    coveredHead = if (coveredHead == null) null else resyncSeq(coveredHead),
    frontier = if (frontier == null) null else resyncSeq(frontier),
    hasMore = hasMore,
)

private fun resyncFrame(value: Long): TransportFrame = TransportFrame(
    channel = ChannelKind.Mux,
    sessionId = resyncSession,
    seq = resyncSeq(value),
    type = "system/message",
    ignorable = false,
    payloadJson = "{}",
    wallClockMs = value,
    replay = false,
)

private fun resyncPage(from: Long, to: Long, hasMore: Boolean): HistoryPage = HistoryPage(
    sessionId = resyncSession,
    frames = (from..to).map { resyncFrame(it) },
    hasMore = hasMore,
)

class ResyncPolicyTest {

    @Test
    fun cursorResyncExpectedSeqIsContiguousPlusOneReset() {
        val step = ResyncPolicy.raise(
            serverId = resyncServer,
            sessionId = resyncSession,
            position = resyncPosition(100, 950),
            head = resyncSeq(950),
            reason = ResyncReason.SeqGap,
            existing = null,
            tuning = resyncTuning,
        )
        val state = assertNotNull(step.state)
        assertEquals(resyncSeq(101), state.expectedSeq)
        assertEquals(ResyncReason.SeqGap, state.reason)
        assertEquals(0, state.attempts)
        assertEquals(resyncSeq(101), step.needsResync?.expectedSeq)
        assertEquals(resyncSession, step.needsResync?.sessionId)
    }

    @Test
    fun cursorResyncExpectedSeqKeepsExistingWatermarkAndSeedsOnlyWithoutOneReset() {
        val existing = resyncState(expected = 60)
        assertEquals(
            resyncSeq(60),
            ResyncPolicy.expectedSeq(resyncPosition(100, 950), resyncSeq(950), existing),
        )
        assertEquals(
            resyncSeq(101),
            ResyncPolicy.expectedSeq(resyncPosition(100, 950), resyncSeq(950), null),
        )
        assertEquals(
            resyncSeq(950),
            ResyncPolicy.expectedSeq(resyncPosition(null, null), resyncSeq(950), null),
        )
    }

    @Test
    fun cursorResyncRaiseWithoutWatermarkSeedsExpectedFromHeadReset() {
        val step = ResyncPolicy.raise(
            serverId = resyncServer,
            sessionId = resyncSession,
            position = resyncPosition(null, null),
            head = resyncSeq(950),
            reason = ResyncReason.HistoryRepair,
            existing = null,
            tuning = resyncTuning,
        )
        assertEquals(resyncSeq(950), assertNotNull(step.state).expectedSeq)
        val request = assertNotNull(step.nextRequest)
        assertEquals(resyncTuning.historyPageSize, request.maxMessages)
        assertNull(request.beforeSeq)
        assertEquals(RepairResult.Opened, step.result)
    }

    @Test
    fun cursorResyncClearedOnlyByReadCoveringExpectedSeqReset() {
        val state = resyncState(expected = 101)
        val run = ResyncPolicy.startRepairRun(resyncSession, null)
        val short = ResyncPolicy.step(state, resyncRead(coveredHead = 100, frontier = 80, hasMore = true), run, resyncTuning)
        assertEquals(RepairResult.Continue, short.result)
        assertEquals(state, short.state)

        val cleared = ResyncPolicy.step(state, resyncRead(coveredHead = 101, frontier = 90, hasMore = true), run, resyncTuning)
        assertEquals(RepairResult.Cleared, cleared.result)
        assertNull(cleared.state)
        assertNull(cleared.needsResync)
        assertNull(cleared.run)
    }

    @Test
    fun cursorResyncReadFromAnotherServerDoesNotClearHoleReset() {
        val state = resyncState(expected = 101)
        val step = ResyncPolicy.step(
            state,
            resyncRead(coveredHead = 500, frontier = 400, hasMore = true, serverId = resyncOtherServer),
            ResyncPolicy.startRepairRun(resyncSession, null),
            resyncTuning,
        )
        assertEquals(RepairResult.Ignored, step.result)
        assertEquals(state, step.state)
    }

    @Test
    fun cursorResyncReadForAnotherSessionDoesNotClearHoleReset() {
        val state = resyncState(expected = 101)
        val step = ResyncPolicy.step(
            state,
            resyncRead(coveredHead = 500, frontier = 400, hasMore = true, sessionId = resyncOtherSession),
            ResyncPolicy.startRepairRun(resyncSession, null),
            resyncTuning,
        )
        assertEquals(RepairResult.Ignored, step.result)
        assertEquals(state, step.state)
    }

    @Test
    fun cursorResyncUncoveredRoundConsumesOneAttemptReset() {
        val state = resyncState(expected = 101)
        val exhausted = RepairRun(resyncSession, beforeSeq = resyncSeq(500), pagesUsed = resyncTuning.historyRepairPageBudget)
        val step = ResyncPolicy.step(state, resyncRead(coveredHead = 50, frontier = 40, hasMore = true), exhausted, resyncTuning)
        assertEquals(RepairResult.RoundExhausted, step.result)
        assertEquals(1, assertNotNull(step.state).attempts)
        assertFalse(step.state?.degraded ?: true)
        assertTrue(step.state?.allowsAutomaticRepair ?: false)
        assertNull(step.nextRequest)
    }

    @Test
    fun cursorResyncTwoUncoveredRoundsDegradeAndStopAutomaticRepairReset() {
        val state = resyncState(expected = 101, attempts = resyncTuning.needsResyncMaxAttempts - 1)
        val exhausted = RepairRun(resyncSession, beforeSeq = resyncSeq(500), pagesUsed = resyncTuning.historyRepairPageBudget)
        val step = ResyncPolicy.step(state, resyncRead(coveredHead = 50, frontier = 40, hasMore = true), exhausted, resyncTuning)
        val degraded = assertNotNull(step.state)
        assertEquals(RepairResult.Degraded, step.result)
        assertEquals(resyncTuning.needsResyncMaxAttempts, degraded.attempts)
        assertTrue(degraded.degraded)
        assertFalse(degraded.allowsAutomaticRepair)
        assertTrue(degraded.needsResync().degraded)
    }

    @Test
    fun cursorHistoryExhaustedWithOpenHoleBecomesTerminalIncompleteReset() {
        val state = resyncState(expected = 101)
        val run = ResyncPolicy.startRepairRun(resyncSession, resyncSeq(700))
        val step = ResyncPolicy.step(state, resyncRead(coveredHead = 50, frontier = 40, hasMore = false), run, resyncTuning)
        val terminal = assertNotNull(step.state)
        assertEquals(RepairResult.HistoryIncomplete, step.result)
        assertTrue(terminal.historyIncomplete)
        assertTrue(terminal.degraded)
        assertEquals(HoleKind.HistoryIncomplete, terminal.holeKind)
        assertFalse(terminal.allowsAutomaticRepair)
        assertNull(step.nextRequest)
    }

    @Test
    fun cursorHistoryIncompleteHoleKindDiffersFromTransportGapReset() {
        val transport = resyncState(expected = 101, reason = ResyncReason.SeqGap)
        val repair = resyncState(expected = 101, reason = ResyncReason.HistoryRepair)
        val terminal = resyncState(expected = 101, reason = ResyncReason.HistoryRepair, historyIncomplete = true)
        assertEquals(HoleKind.TransportGap, transport.holeKind)
        assertEquals(HoleKind.HistoryRepair, repair.holeKind)
        assertEquals(HoleKind.HistoryIncomplete, terminal.holeKind)
        assertNotEquals(transport.holeKind, terminal.holeKind)
    }

    @Test
    fun cursorHistoryIncompleteAllowsExactlyOneManualResyncReset() {
        val terminal = resyncState(expected = 101, attempts = 1, degraded = true, historyIncomplete = true)
        val manual = assertNotNull(ResyncPolicy.manualResync(terminal, resyncTuning))
        assertEquals(RepairResult.ManualImmediate, manual.result)
        val reset = assertNotNull(manual.state)
        assertEquals(ResyncReason.ManualImmediate, reset.reason)
        assertEquals(0, reset.attempts)
        assertFalse(reset.degraded)
        assertFalse(reset.historyIncomplete)
        assertFalse(reset.manualResyncAvailable)
        assertNotNull(manual.nextRequest)

        assertNull(ResyncPolicy.manualResync(reset, resyncTuning))
    }

    @Test
    fun cursorTransportGapKeepsManualResyncAvailableEveryRoundReset() {
        val gap = resyncState(expected = 101, reason = ResyncReason.SeqGap, degraded = true)
        val first = assertNotNull(ResyncPolicy.manualResync(gap, resyncTuning))
        assertEquals(ResyncReason.ManualImmediate, assertNotNull(first.state).reason)
        val second = ResyncPolicy.manualResync(first.state ?: gap, resyncTuning)
        assertNotNull(second)
        assertEquals(RepairResult.ManualImmediate, second.result)
    }

    @Test
    fun cursorRepairReadBudgetComesFromTuningReset() {
        val fresh = ResyncPolicy.startRepairRun(resyncSession, null)
        assertEquals(resyncTuning.historyRepairPageBudget, ResyncPolicy.repairPagesRemaining(fresh, resyncTuning))
        val request = assertNotNull(ResyncPolicy.nextRepairRequest(fresh, resyncTuning))
        assertEquals(resyncTuning.historyPageSize, request.maxMessages)
        assertEquals(resyncSession, request.sessionId)

        val spent = fresh.copy(pagesUsed = resyncTuning.historyRepairPageBudget)
        assertEquals(0, ResyncPolicy.repairPagesRemaining(spent, resyncTuning))
        assertNull(ResyncPolicy.nextRepairRequest(spent, resyncTuning))
    }

    @Test
    fun cursorRepairResumesAtLowestReadSeqNotHeadOrMinusOneReset() {
        val page = resyncPage(from = 850, to = 900, hasMore = true)
        val read = HistoryRead.of(page, resyncServer)
        assertEquals(resyncSeq(850), read.frontier)
        assertEquals(resyncSeq(900), read.coveredHead)

        val advanced = ResyncPolicy.advanceRepairRun(RepairRun(resyncSession, beforeSeq = resyncSeq(1000), pagesUsed = 2), read)
        assertEquals(resyncSeq(850), advanced.beforeSeq)
        assertNotEquals(resyncSeq(900), advanced.beforeSeq)
        assertNotEquals(resyncSeq(849), advanced.beforeSeq)
        assertEquals(3, advanced.pagesUsed)
        val next = assertNotNull(ResyncPolicy.nextRepairRequest(advanced, resyncTuning))
        assertEquals(resyncSeq(850), next.beforeSeq)
    }

    @Test
    fun cursorRepairEmptyPageKeepsPreviousResumePointReset() {
        val empty = HistoryPage(sessionId = resyncSession, frames = emptyList(), hasMore = true)
        val read = HistoryRead.of(empty, resyncServer)
        assertNull(read.frontier)
        assertNull(read.coveredHead)

        val advanced = ResyncPolicy.advanceRepairRun(RepairRun(resyncSession, beforeSeq = resyncSeq(300), pagesUsed = 1), read)
        assertEquals(resyncSeq(300), advanced.beforeSeq)
        assertEquals(2, advanced.pagesUsed)
    }

    @Test
    fun cursorRepairChainWalksBackwardNeverRestartsFromHeadReset() {
        val pageSize = resyncTuning.historyPageSize
        val head = 900L
        var run = ResyncPolicy.startRepairRun(resyncSession, null)
        var lowest = head
        repeat(3) {
            val from = lowest - pageSize + 1
            val page = resyncPage(from = from, to = lowest, hasMore = true)
            run = ResyncPolicy.advanceRepairRun(run, HistoryRead.of(page, resyncServer))
            lowest = from
            val request = assertNotNull(ResyncPolicy.nextRepairRequest(run, resyncTuning))
            assertEquals(resyncSeq(from), request.beforeSeq)
            assertNotEquals(resyncSeq(head), request.beforeSeq)
        }
        assertEquals(resyncTuning.historyRepairPageBudget - 3, ResyncPolicy.repairPagesRemaining(run, resyncTuning))
    }

    @Test
    fun cursorNeedsResyncRecordCarriesReasonExpectedSeqAndAttemptsReset() {
        val state = resyncState(expected = 101, reason = ResyncReason.SeqGap, attempts = 1, degraded = true)
        val record = state.needsResync()
        assertEquals(resyncSession, record.sessionId)
        assertEquals(ResyncReason.SeqGap, record.reason)
        assertEquals(resyncSeq(101), record.expectedSeq)
        assertEquals(1, record.attempts)
        assertTrue(record.degraded)
    }

    @Test
    fun cursorRepairRunResumesAtLowestReachedSeqNotHeadReset() {
        val run = ResyncPolicy.startRepairRun(resyncSession, resumeFrom = resyncSeq(400))
        val request = assertNotNull(ResyncPolicy.nextRepairRequest(run, resyncTuning))
        assertEquals(resyncSeq(400), request.beforeSeq)
        assertNotEquals(resyncSeq(950), request.beforeSeq)
    }
}
