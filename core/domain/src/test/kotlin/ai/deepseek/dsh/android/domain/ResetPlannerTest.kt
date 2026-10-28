package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.BaselineEntry
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.IdentityDigest
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRef
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ResetTrigger
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TransportCursor
import ai.deepseek.dsh.android.model.TransportFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val resetIdentity = ConnectionIdentity(IdentityDigest.of("https://gateway.example.test/base", "token-1"))
private val resetServer = ServerId("srv-1")
private val resetSession = SessionId("s-1")
private val resetOtherSession = SessionId("s-2")
private val resetChannel = ChannelKind.Mux

private fun resetSeq(value: Long): EventSeq = EventSeq(value)

private fun resetKey(kind: PendingKind, id: String): DurableStore.PendingKey =
    DurableStore.PendingKey(kind, RequestId(id))

private fun resetRef(kind: PendingKind, id: String): PendingRef = PendingRef(kind, RequestId(id))

private fun resetContext(
    storedEpoch: String? = null,
    batchEpoch: String? = null,
    since: Long = 100,
    latestSeq: Long = 200,
    serverResetFlag: Boolean = false,
    truncated: Boolean = false,
): ResetContext = ResetContext(
    storedEpoch = storedEpoch,
    batchEpoch = batchEpoch,
    since = since,
    latestSeq = latestSeq,
    serverResetFlag = serverResetFlag,
    truncated = truncated,
)

private fun resetFrame(type: String, sessionId: SessionId = resetSession, value: Long = 150): TransportFrame =
    TransportFrame(
        channel = resetChannel,
        sessionId = sessionId,
        seq = resetSeq(value),
        type = type,
        ignorable = false,
        payloadJson = "{}",
        wallClockMs = value,
        replay = false,
    )

private fun resetBaseline(
    sessionId: SessionId = resetSession,
    lastSeq: Long?,
    pending: PendingRef? = null,
): BaselineEntry = BaselineEntry(
    sessionId = sessionId,
    lastSeq = if (lastSeq == null) null else resetSeq(lastSeq),
    payloadJson = "{}",
    pendingRef = pending,
)

private fun resetBatch(
    epoch: String? = "e1",
    since: Long = 100,
    latestSeq: Long = 200,
    truncated: Boolean = false,
    reset: Boolean = false,
    baseline: List<BaselineEntry>? = null,
    frames: List<TransportFrame> = emptyList(),
): EventBatch = EventBatch(
    channel = resetChannel,
    epoch = epoch,
    since = since,
    latestSeq = latestSeq,
    truncated = truncated,
    reset = reset,
    waitSupported = true,
    baseline = baseline,
    frames = frames,
)

private fun resetCursor(epoch: String?): TransportCursor =
    TransportCursor(resetIdentity, resetChannel, sinceSeq = 100, epoch = epoch)

class ResetPlannerTest {

    @Test
    fun serverFlagAloneIsTheUnobservableResetTrigger() {
        val assessment = ResetPlanner.assess(resetContext(serverResetFlag = true), epochTracking = true)
        assertEquals(setOf(ResetTrigger.ServerResetFlag), assessment.triggers)
        assertTrue(assessment.reset)
        assertTrue(assessment.triggerUnobservable)
        assertEquals(listOf(ResetTrigger.ServerResetFlag), assessment.observableTriggers)
    }

    @Test
    fun epochChangeAndServerFlagAreSeparateResetTriggers() {
        val assessment = ResetPlanner.assess(
            resetContext(storedEpoch = "e1", batchEpoch = "e2", serverResetFlag = true),
            epochTracking = true,
        )
        assertEquals(setOf(ResetTrigger.ServerResetFlag, ResetTrigger.EpochChanged), assessment.triggers)
        assertFalse(assessment.triggerUnobservable)
        assertEquals(2, assessment.observableTriggers.size)
        assertTrue(assessment.reconnectionReloadingBaseline)
    }

    @Test
    fun truncatedIsItsOwnResetTrigger() {
        val context = resetContext(truncated = true)
        assertEquals(setOf(ResetTrigger.Truncated), ResetPlanner.triggers(context, epochTracking = true))
        val assessment = ResetPlanner.assess(context, epochTracking = true)
        assertTrue(assessment.reset)
        assertFalse(assessment.triggerUnobservable)
    }

    @Test
    fun latestSeqBehindSinceIsItsOwnResetTrigger() {
        val context = resetContext(since = 200, latestSeq = 199)
        assertEquals(setOf(ResetTrigger.LatestSeqBehindSince), ResetPlanner.triggers(context, epochTracking = true))
        assertTrue(ResetPlanner.assess(context, epochTracking = true).reset)
    }

    @Test
    fun equalSeqsFireNoResetTrigger() {
        val context = resetContext(since = 200, latestSeq = 200)
        assertEquals(emptySet(), ResetPlanner.triggers(context, epochTracking = true))
        assertFalse(ResetPlanner.assess(context, epochTracking = true).reset)
    }

    @Test
    fun epochTrackingDisabledSuppressesTheEpochResetTrigger() {
        val context = resetContext(storedEpoch = "e1", batchEpoch = "e2")
        assertEquals(emptySet(), ResetPlanner.triggers(context, epochTracking = false))
        assertEquals(setOf(ResetTrigger.EpochChanged), ResetPlanner.triggers(context, epochTracking = true))
        val batch = resetBatch(epoch = "e2")
        assertEquals(emptySet(), ResetPlanner.triggers(batch, storedEpoch = "e1", epochTracking = false))
        assertEquals(setOf(ResetTrigger.EpochChanged), ResetPlanner.triggers(batch, storedEpoch = "e1", epochTracking = true))
    }

    @Test
    fun epochResetTriggerRequiresBothStoredAndArrivedEpoch() {
        assertEquals(emptySet(), ResetPlanner.triggers(resetContext(storedEpoch = null, batchEpoch = "e2"), true))
        assertEquals(emptySet(), ResetPlanner.triggers(resetContext(storedEpoch = "e1", batchEpoch = null), true))
        assertEquals(emptySet(), ResetPlanner.triggers(resetContext(storedEpoch = "e1", batchEpoch = "e1"), true))
    }

    @Test
    fun orderedResetPlanPutsTombstoneBeforeBaselineEventsAndCursor() {
        val plan = ResetPlanner.plan(
            identity = resetIdentity,
            serverId = resetServer,
            batch = resetBatch(
                epoch = "e2",
                latestSeq = 500,
                reset = true,
                truncated = true,
                baseline = listOf(resetBaseline(lastSeq = 480, pending = resetRef(PendingKind.Approval, "r-1"))),
                frames = listOf(resetFrame("assistant/message", value = 500)),
            ),
            storedCursor = resetCursor(epoch = "e1"),
            preBatchKeys = mapOf(resetSession to setOf(resetKey(PendingKind.Approval, "r-1"), resetKey(PendingKind.Approval, "r-2"))),
        )
        assertEquals(
            listOf(
                ResetStep.ClearTransportCursor,
                ResetStep.TombstoneAbsent(resetSession, listOf(resetKey(PendingKind.Approval, "r-2"))),
                ResetStep.ApplyBaseline,
                ResetStep.ApplyEvents,
                ResetStep.AdvanceCursor,
            ),
            plan.steps,
        )
        assertEquals(500L, plan.cursorAfter?.sinceSeq)
        assertEquals("e2", plan.cursorAfter?.epoch)
        assertEquals(resetChannel, plan.cursorAfter?.channel)
        assertTrue(plan.reset)
        assertEquals(
            setOf(ResetTrigger.ServerResetFlag, ResetTrigger.EpochChanged, ResetTrigger.Truncated),
            plan.assessment.triggers,
        )
        assertEquals(listOf(resetSession), plan.sweepSessions)
    }

    @Test
    fun incrementalBatchKeepsCursorStepOutOfTheResetPlan() {
        val plan = ResetPlanner.plan(
            identity = resetIdentity,
            serverId = resetServer,
            batch = resetBatch(epoch = "e1", frames = listOf(resetFrame("assistant/message"))),
            storedCursor = resetCursor(epoch = "e1"),
            preBatchKeys = emptyMap(),
        )
        assertFalse(plan.reset)
        assertNull(plan.cursorAfter)
        assertEquals(listOf(ResetStep.ApplyEvents), plan.steps)
        assertEquals(resetSeq(150), plan.headSeq)
    }

    @Test
    fun tombstoneSetComesFromPreBatchRowsInResetSweep() {
        val sweeps = ResetPlanner.baselineTargets(
            batch = resetBatch(
                baseline = listOf(
                    resetBaseline(lastSeq = 480, pending = resetRef(PendingKind.Approval, "r-1")),
                    resetBaseline(sessionId = resetOtherSession, lastSeq = 90),
                ),
            ),
            preBatchKeys = mapOf(
                resetSession to setOf(
                    resetKey(PendingKind.Approval, "r-1"),
                    resetKey(PendingKind.Approval, "r-2"),
                    resetKey(PendingKind.Question, "q-1"),
                ),
            ),
        )
        val target = sweeps.first { it.sessionId == resetSession }
        assertEquals(setOf(resetKey(PendingKind.Approval, "r-1")), target.presentKeys)
        assertEquals(
            listOf(resetKey(PendingKind.Approval, "r-2"), resetKey(PendingKind.Question, "q-1")),
            target.absentKeys,
        )
        assertEquals(1, sweeps.count { it.absentKeys.isNotEmpty() })
        assertEquals(2, sweeps.size)
    }

    @Test
    fun absentBaselineProducesNoTombstoneStepInTheResetPlan() {
        val plan = ResetPlanner.plan(
            identity = resetIdentity,
            serverId = resetServer,
            batch = resetBatch(reset = true, baseline = null, frames = listOf(resetFrame("assistant/message"))),
            storedCursor = resetCursor(epoch = "e1"),
            preBatchKeys = mapOf(resetSession to setOf(resetKey(PendingKind.Approval, "r-1"))),
        )
        assertTrue(plan.sweeps.isEmpty())
        assertFalse(plan.steps.contains(ResetStep.ApplyBaseline))
        assertFalse(plan.steps.any { it is ResetStep.TombstoneAbsent })
        assertEquals(emptyList(), plan.sweepSessions)
    }

    @Test
    fun baselineAbsenceIsNotSweptWhenTheFamilyIsEmpty() {
        val sweeps = ResetPlanner.baselineTargets(
            batch = resetBatch(baseline = emptyList()),
            preBatchKeys = mapOf(resetSession to setOf(resetKey(PendingKind.Approval, "r-1"))),
        )
        assertTrue(sweeps.isEmpty())
    }

    @Test
    fun storedWatermarkWinsOverBaselineLastSeqInResetSweep() {
        val sweeps = ResetPlanner.baselineTargets(
            batch = resetBatch(baseline = listOf(resetBaseline(lastSeq = 950))),
            preBatchKeys = emptyMap(),
            preBatchWatermark = mapOf(resetSession to resetSeq(100)),
        )
        val target = sweeps.single()
        assertEquals(resetSeq(100), target.watermark)
        assertEquals(resetSeq(950), target.lastSeq)
    }

    @Test
    fun baselineHeadSeqPrecedesChannelLatestInResetPlan() {
        assertEquals(
            resetSeq(950),
            ResetPlanner.headSeqOf(resetBatch(latestSeq = 4000, baseline = listOf(resetBaseline(lastSeq = 950)))),
        )
        assertEquals(
            resetSeq(1200),
            ResetPlanner.headSeqOf(
                resetBatch(
                    latestSeq = 4000,
                    baseline = listOf(resetBaseline(lastSeq = 950)),
                    frames = listOf(resetFrame("assistant/message", value = 1200)),
                ),
            ),
        )
        assertEquals(resetSeq(4000), ResetPlanner.headSeqOf(resetBatch(latestSeq = 4000)))
    }

    @Test
    fun sessionSubscribedMovesWatermarkButNeverCreatesRowInResetPlan() {
        val op = ResetPlanner.sessionRowOp("session/subscribed", resetSession, knownRows = setOf(resetOtherSession))
        assertEquals(SessionRowOp.Ignore(resetSession, RowIgnoreReason.RosterOnly), op)
        val update = RosterPlanner.watermarkUpdate(resetSession, current = resetSeq(100), arrived = resetSeq(140))
        assertEquals(WatermarkChange.Advance, update.change)
        assertEquals(resetSeq(140), update.lastSeq)
    }

    @Test
    fun projectionRowsUpdateOnlyKnownSessionsAfterReset() {
        val known = setOf(resetSession)
        assertEquals(SessionRowOp.Update(resetSession), ResetPlanner.sessionRowOp("session/projection", resetSession, known))
        assertEquals(SessionRowOp.Update(resetSession), ResetPlanner.sessionRowOp("host/session-activity", resetSession, known))
        assertEquals(SessionRowOp.Update(resetSession), ResetPlanner.sessionRowOp("host/session-status", resetSession, known))
        assertEquals(
            SessionRowOp.Ignore(resetOtherSession, RowIgnoreReason.UnknownRow),
            ResetPlanner.sessionRowOp("session/projection", resetOtherSession, known),
        )
        assertEquals(
            SessionRowOp.Ignore(resetOtherSession, RowIgnoreReason.UnknownType),
            ResetPlanner.sessionRowOp("future/unknown-event", resetOtherSession, known),
        )
    }

    @Test
    fun hostSessionRemovedIsNeverTrustedAfterReset() {
        val known = setOf(resetSession)
        assertEquals(
            SessionRowOp.Ignore(resetSession, RowIgnoreReason.NotTrusted),
            ResetPlanner.sessionRowOp("host/session-removed", resetSession, known),
        )
        assertEquals(
            SessionRowOp.Ignore(resetSession, RowIgnoreReason.RosterOnly),
            ResetPlanner.sessionRowOp("session/list", resetSession, known),
        )
        assertEquals(
            SessionRowOp.Ignore(resetSession, RowIgnoreReason.RosterOnly),
            ResetPlanner.sessionRowOp("host/session-list", resetSession, known),
        )
    }

    @Test
    fun rosterEventBatchLimitsRowWritesToExistingRowsAfterReset() {
        val plan = ResetPlanner.planRosterEventBatch(
            frames = listOf(
                resetFrame("host/session-status", value = 1),
                resetFrame("host/session-status", sessionId = resetOtherSession, value = 2),
                resetFrame("session/subscribed", value = 3),
            ),
            knownRows = setOf(resetSession),
        )
        assertEquals(listOf(SessionRowOp.Update(resetSession)), plan.sessionRowOps)
        assertFalse(plan.hostRosterReloadPending)
        assertFalse(plan.pendingMuxReconnect)
    }

    @Test
    fun hostMuxReloadRebindsMuxWithoutBaselineReset() {
        val frame = resetFrame("host/mux-reload")
        assertEquals(
            RosterEventPlan.Row(SessionRowOp.Ignore(resetSession, RowIgnoreReason.UnknownType)),
            ResetPlanner.planRosterEvent(frame, setOf(resetSession)),
        )
        val plan = ResetPlanner.planRosterEventBatch(listOf(frame), setOf(resetSession))
        assertTrue(plan.pendingMuxReconnect)
        assertFalse(plan.hostRosterReloadPending)
        assertTrue(plan.sessionRowOps.isEmpty())
    }

    @Test
    fun hostResyncRequestedMarksRosterReloadOnlyInResetPlan() {
        val frame = resetFrame(ResetPlanner.HOST_RESYNC_REQUESTED)
        assertEquals(RosterEventPlan.RosterReload, ResetPlanner.planRosterEvent(frame, setOf(resetSession)))
        val plan = ResetPlanner.planRosterEventBatch(listOf(frame), setOf(resetSession))
        assertTrue(plan.hostRosterReloadPending)
        assertFalse(plan.pendingMuxReconnect)
    }

    @Test
    fun reconnectionScopeSeparatesEpochResetFromHostReload() {
        assertEquals(RosterResetScope.Mux, ResetPlanner.reconnectionReloadScope(batchEpoch = "e2", storedEpoch = "e1"))
        assertEquals(RosterResetScope.Host, ResetPlanner.reconnectionReloadScope(batchEpoch = "e2", storedEpoch = null))
        assertEquals(RosterResetScope.Host, ResetPlanner.reconnectionReloadScope(batchEpoch = null, storedEpoch = "e1"))
        assertFalse(ResetPlanner.assess(resetContext(truncated = true), epochTracking = true).reconnectionReloadingBaseline)
    }

    @Test
    fun watermarkNeverMovesBackwardOrStaysEqualInResetPlan() {
        assertEquals(WatermarkChange.Advance, ResetPlanner.advanceWatermark(current = null, arrived = resetSeq(10)))
        assertEquals(WatermarkChange.Advance, ResetPlanner.advanceWatermark(current = resetSeq(10), arrived = resetSeq(11)))
        assertEquals(WatermarkChange.Ignore, ResetPlanner.advanceWatermark(current = resetSeq(10), arrived = resetSeq(10)))
        assertEquals(WatermarkChange.Ignore, ResetPlanner.advanceWatermark(current = resetSeq(10), arrived = resetSeq(9)))
    }

    @Test
    fun muxReloadSignalInBaselineDoesNotSweepPendingRowsInResetSweep() {
        val baseline = BaselineEntry(
            sessionId = resetSession,
            lastSeq = resetSeq(500),
            payloadJson = "{\"type\":\"host/mux-reload\"}",
            pendingRef = resetRef(PendingKind.Approval, "r-1"),
        )
        val target = ResetPlanner.baselineTargets(
            batch = resetBatch(baseline = listOf(baseline)),
            preBatchKeys = mapOf(resetSession to setOf(resetKey(PendingKind.Approval, "r-1"), resetKey(PendingKind.Question, "q-9"))),
        ).single()
        assertTrue(target.reloadMux)
        assertEquals(listOf(resetKey(PendingKind.Question, "q-9")), target.absentKeys)
        assertEquals(resetSeq(500), target.watermark)
    }

    @Test
    fun resetAssessmentKeepsEveryTriggerVisibleBehindTheServerBoolean() {
        val assessment = ResetPlanner.assess(
            resetContext(
                storedEpoch = "e1",
                batchEpoch = "e2",
                since = 300,
                latestSeq = 200,
                serverResetFlag = true,
                truncated = true,
            ),
            epochTracking = true,
        )
        assertTrue(assessment.reset)
        assertEquals(ResetTrigger.entries.toSet(), assessment.triggers)
        assertEquals(ResetTrigger.entries.toList(), assessment.observableTriggers)
        assertFalse(assessment.triggerUnobservable)
    }
}
