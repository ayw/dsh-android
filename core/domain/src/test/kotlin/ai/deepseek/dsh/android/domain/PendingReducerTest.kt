package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.ApprovalOutcome
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.DurableEvent
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.QUESTION_TOOL_NAME
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.WriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val pendingTuning = SyncTuning.DEFAULT
private val pendingServer = ServerId("srv-1")
private val pendingOtherServer = ServerId("srv-2")
private val pendingSession = SessionId("s-1")
private val pendingPayload = "{\"data\":{\"id\":\"r-1\"}}"

private fun pendingRow(
    id: String,
    kind: PendingKind = PendingKind.Approval,
    serverId: ServerId = pendingServer,
    status: PendingStatus = PendingStatus.Pending,
    firstSeenAtMs: Long = 0L,
    resolvedAtMs: Long? = null,
    dismissedAtMs: Long? = null,
): PendingRequest = PendingRequest(
    serverId = serverId,
    kind = kind,
    requestId = RequestId(id),
    sessionId = pendingSession,
    status = status,
    completeness = PendingCompleteness.Stale,
    payloadJson = pendingPayload,
    firstSeenAtMs = firstSeenAtMs,
    createdAtMs = firstSeenAtMs,
    dismissedAtMs = dismissedAtMs,
    resolvedAtMs = resolvedAtMs,
)

private fun pendingKey(kind: PendingKind, id: String): DurableStore.PendingKey =
    DurableStore.PendingKey(kind, RequestId(id))

private fun pendingSeq(value: Long): EventSeq = EventSeq(value)

private fun approvalAsked(id: String, seq: Long = 10): DurableEvent.ApprovalAsked =
    DurableEvent.ApprovalAsked(pendingSeq(seq), seq, RequestId(id), "Bash", null, null)

private fun approvalDecided(id: String, seq: Long = 12): DurableEvent.ApprovalDecided =
    DurableEvent.ApprovalDecided(pendingSeq(seq), seq, RequestId(id), ApprovalOutcome.AllowedOnce)

private fun questionCall(callId: String, seq: Long = 20, toolName: String = QUESTION_TOOL_NAME): DurableEvent.ToolCall =
    DurableEvent.ToolCall(pendingSeq(seq), seq, CallId(callId), toolName, "{}", null)

private fun questionEvent(callId: String, seq: Long = 20): DurableEvent.QuestionAsked =
    DurableEvent.QuestionAsked(pendingSeq(seq), seq, CallId(callId), "[]")

private fun questionResolvedEvent(callId: String, seq: Long = 21): DurableEvent.QuestionResolved =
    DurableEvent.QuestionResolved(pendingSeq(seq), seq, CallId(callId), "{}", "user")

private fun questionToolResult(callId: String, seq: Long = 22): DurableEvent.ToolResult =
    DurableEvent.ToolResult(pendingSeq(seq), seq, CallId(callId), QUESTION_TOOL_NAME, "{}", false, null)

private fun capabilitiesWith(operations: Int): BackendCapabilities {
    val raw = mapOf(
        "wsTicket" to 1,
        "eventPolling" to 1,
        "eventEpoch" to 1,
        "operations" to operations,
        "workspaceFiles" to 1,
        "resumableUploads" to 0,
        "fileInbox" to 0,
        "imagePromptTransport" to 0,
    )
    return BackendCapabilities(
        wsTicket = raw.getValue("wsTicket"),
        eventPolling = raw.getValue("eventPolling"),
        eventEpoch = raw.getValue("eventEpoch"),
        operations = raw.getValue("operations"),
        workspaceFiles = raw.getValue("workspaceFiles"),
        resumableUploads = raw.getValue("resumableUploads"),
        fileInbox = raw.getValue("fileInbox"),
        imagePromptTransport = raw.getValue("imagePromptTransport"),
        raw = raw,
    )
}

class PendingReducerTest {

    @Test
    fun stateMachineAllowsEveryDocumentedPendingEdge() {
        assertTrue(PendingReducer.canTransition(PendingStatus.Pending, PendingStatus.Answering))
        assertTrue(PendingReducer.canTransition(PendingStatus.Pending, PendingStatus.Resolved))
        assertTrue(PendingReducer.canTransition(PendingStatus.Pending, PendingStatus.Stale))
        assertTrue(PendingReducer.canTransition(PendingStatus.Pending, PendingStatus.Unknown))
        assertTrue(PendingReducer.canTransition(PendingStatus.Answering, PendingStatus.Resolved))
        assertTrue(PendingReducer.canTransition(PendingStatus.Answering, PendingStatus.Stale))
        assertTrue(PendingReducer.canTransition(PendingStatus.Answering, PendingStatus.Unknown))
        assertTrue(PendingReducer.canTransition(PendingStatus.Unknown, PendingStatus.Pending))
        assertTrue(PendingReducer.canTransition(PendingStatus.Unknown, PendingStatus.Answering))
        assertTrue(PendingReducer.canTransition(PendingStatus.Unknown, PendingStatus.Resolved))
        assertTrue(PendingReducer.canTransition(PendingStatus.Unknown, PendingStatus.Stale))
        assertTrue(PendingReducer.canTransition(null, PendingStatus.Pending))
    }

    @Test
    fun stateMachineRejectsEdgesOutsideThePendingTable() {
        assertFalse(PendingReducer.canTransition(PendingStatus.Resolved, PendingStatus.Pending))
        assertFalse(PendingReducer.canTransition(PendingStatus.Resolved, PendingStatus.Stale))
        assertFalse(PendingReducer.canTransition(PendingStatus.Resolved, PendingStatus.Answering))
        assertFalse(PendingReducer.canTransition(PendingStatus.Stale, PendingStatus.Answering))
        assertFalse(PendingReducer.canTransition(PendingStatus.Stale, PendingStatus.Resolved))
    }

    @Test
    fun transitionSnapshotListsPendingEdgesInSortedOrder() {
        val snapshot = PendingReducer.transitionTableSnapshot()
        assertEquals(snapshot.sorted(), snapshot)
        assertEquals(snapshot.size, snapshot.distinct().size)
        snapshot.forEach { edge ->
            val arrowAt = edge.indexOf("->")
            val sourceAt = edge.indexOf('@')
            val from = PendingStatus.valueOf(edge.substring(0, arrowAt))
            val to = PendingStatus.valueOf(edge.substring(arrowAt + 2, sourceAt))
            assertTrue(PendingReducer.canTransition(from, to), edge)
        }
    }

    @Test
    fun anotherClientCanSettleAPendingApprovalWithoutLocalRequest() {
        val existing = pendingRow("r-1")
        val settled = PendingReducer.onApprovalDecided(pendingServer, pendingSession, approvalDecided("r-1"), pendingPayload, existing, nowMs = 500)
        assertEquals(PendingStatus.Resolved, settled.status)
        assertEquals(500L, settled.resolvedAtMs)
        assertEquals(PendingCompleteness.Complete, settled.completeness)
    }

    @Test
    fun lateDecidedFrameCreatesThePendingRowItNeverSawAskedFor() {
        val row = PendingReducer.onApprovalDecided(pendingServer, pendingSession, approvalDecided("r-7", seq = 30), pendingPayload, existing = null, nowMs = 900)
        assertEquals(PendingStatus.Resolved, row.status)
        assertEquals(900L, row.resolvedAtMs)
        assertEquals(pendingSeq(30), row.anchorSeq)
        assertEquals(PendingKind.Approval, row.kind)
    }

    @Test
    fun replayedAskedFrameKeepsSettledOrTombstonedPendingRowClosed() {
        val ttl = pendingTuning.tombstoneTtlMs
        val settled = PendingReducer.onApprovalAsked(
            pendingServer,
            pendingSession,
            approvalAsked("r-1"),
            pendingPayload,
            pendingRow("r-1", status = PendingStatus.Resolved, resolvedAtMs = 10L),
            nowMs = ttl * 2,
            tuning = pendingTuning,
        )
        assertEquals(PendingStatus.Resolved, settled.status)

        val tombstoned = PendingReducer.onApprovalAsked(
            pendingServer,
            pendingSession,
            approvalAsked("r-1"),
            pendingPayload,
            pendingRow("r-1", status = PendingStatus.Stale, firstSeenAtMs = ttl * 2),
            nowMs = ttl * 2 + 1,
            tuning = pendingTuning,
        )
        assertEquals(PendingStatus.Stale, tombstoned.status)
    }

    @Test
    fun expiredTombstoneRevivesWhenThePendingRequestIsAskedAgain() {
        val ttl = pendingTuning.tombstoneTtlMs
        val revived = PendingReducer.onApprovalAsked(
            pendingServer,
            pendingSession,
            approvalAsked("r-1"),
            pendingPayload,
            pendingRow("r-1", status = PendingStatus.Stale, firstSeenAtMs = 0L, dismissedAtMs = 5L),
            nowMs = ttl,
            tuning = pendingTuning,
        )
        assertEquals(PendingStatus.Pending, revived.status)
        assertNull(revived.dismissedAtMs)
    }

    @Test
    fun baselineAbsenceTurnsCountedPendingRowStaleWithoutRetry() {
        val swept = PendingReducer.onBaselineSweep(
            requests = listOf(pendingRow("r-1"), pendingRow("r-2", kind = PendingKind.Question)),
            presentKeys = setOf(pendingKey(PendingKind.Question, "r-2")),
            sweepSkipped = false,
            nowMs = 100,
            tuning = pendingTuning,
        )
        assertEquals(PendingStatus.Stale, swept.first { it.requestId == RequestId("r-1") }.status)
        assertEquals(PendingStatus.Pending, swept.first { it.requestId == RequestId("r-2") }.status)
        assertEquals(1, swept.count { it.status == PendingStatus.Stale })
    }

    @Test
    fun baselineSweepSkippedLeavesEveryPendingRowUntouched() {
        val requests = listOf(pendingRow("r-1"), pendingRow("r-2", status = PendingStatus.Unknown))
        val swept = PendingReducer.onBaselineSweep(requests, presentKeys = emptySet(), sweepSkipped = true, nowMs = 100, tuning = pendingTuning)
        assertEquals(requests, swept)
    }

    @Test
    fun baselineReconfirmsUnknownPendingRowBackToPending() {
        val unknown = pendingRow("r-1", status = PendingStatus.Unknown)
        val swept = PendingReducer.onBaselineSweep(
            requests = listOf(unknown),
            presentKeys = setOf(pendingKey(PendingKind.Approval, "r-1")),
            sweepSkipped = false,
            nowMs = 10,
            tuning = pendingTuning,
        )
        val revived = swept.single()
        assertEquals(PendingStatus.Pending, revived.status)
        assertEquals(PendingCompleteness.Stale, revived.completeness)
    }

    @Test
    fun resolveAcceptedMarksPendingResolvedAwaitingReceiptConfirmation() {
        val answering = pendingRow("r-1", status = PendingStatus.Answering)
        val pendingReceipt = PendingReducer.onResolveResult(
            answering,
            WriteResult(accepted = true, receiptId = "rc-1", outcome = WriteResult.WriteOutcome.ReceiptPending),
            nowMs = 400,
        )
        assertEquals(PendingStatus.Resolved, pendingReceipt.request.status)
        assertEquals(400L, pendingReceipt.request.resolvedAtMs)
        assertTrue(pendingReceipt.receiptConfirmed)
        assertFalse(pendingReceipt.retryAllowed)
        assertFalse(pendingReceipt.refreshBaseline)

        val plainAccepted = PendingReducer.onResolveResult(
            answering,
            WriteResult(accepted = true, receiptId = null, outcome = WriteResult.WriteOutcome.Accepted),
            nowMs = 400,
        )
        assertFalse(plainAccepted.receiptConfirmed)
    }

    @Test
    fun resolveRejectedTurnsPendingStaleAndForbidsRetry() {
        val outcome = PendingReducer.onResolveResult(
            pendingRow("r-1", status = PendingStatus.Answering),
            WriteResult(accepted = false, receiptId = null, outcome = WriteResult.WriteOutcome.Rejected),
            nowMs = 400,
        )
        assertEquals(PendingStatus.Stale, outcome.request.status)
        assertFalse(outcome.retryAllowed)
        assertTrue(outcome.refreshBaseline)
    }

    @Test
    fun resolveTimeoutMarksPendingUnknownAndRefreshesBaselineWithoutResend() {
        val outcome = PendingReducer.onResolveResult(
            pendingRow("r-1", status = PendingStatus.Answering),
            WriteResult(accepted = null, receiptId = null, outcome = WriteResult.WriteOutcome.Unknown),
            nowMs = 400,
        )
        assertEquals(PendingStatus.Unknown, outcome.request.status)
        assertFalse(outcome.retryAllowed)
        assertTrue(outcome.refreshBaseline)
        assertNull(outcome.conflict)
    }

    @Test
    fun v0ResolvePathNeverProducesTheBffPendingConflictTaxonomy() {
        val results = listOf(
            WriteResult(true, "rc-1", WriteResult.WriteOutcome.ReceiptPending),
            WriteResult(false, null, WriteResult.WriteOutcome.Rejected),
            WriteResult(null, null, WriteResult.WriteOutcome.Unknown),
            WriteResult(null, null, WriteResult.WriteOutcome.Forbidden),
            WriteResult(null, null, WriteResult.WriteOutcome.Accepted),
        )
        results.forEach { result ->
            val outcome = PendingReducer.onResolveResult(
                pendingRow("r-1", status = PendingStatus.Answering),
                result,
                nowMs = 10,
            )
            assertNull(outcome.conflict, "v0 must not classify conflicts")
            assertFalse(outcome.retryAllowed, "v0 must not auto-resend")
        }
        assertEquals(BffConflict.entries.size, 3)
    }

    @Test
    fun answeringPendingRowCanStillResolveGoStaleOrUnknown() {
        assertTrue(PendingReducer.canTransition(PendingStatus.Answering, PendingStatus.Resolved))
        assertTrue(PendingReducer.canTransition(PendingStatus.Answering, PendingStatus.Stale))
        assertTrue(PendingReducer.canTransition(PendingStatus.Answering, PendingStatus.Unknown))
        val resolved = PendingReducer.onResolveResult(
            pendingRow("r-1", status = PendingStatus.Answering),
            WriteResult(true, null, WriteResult.WriteOutcome.Accepted),
            nowMs = 1,
        )
        assertEquals(PendingStatus.Resolved, resolved.request.status)
    }

    @Test
    fun stalePendingRowCannotBeReopenedByLateSettledFrame() {
        val stale = pendingRow("r-1", status = PendingStatus.Stale, firstSeenAtMs = 5L)
        val row = PendingReducer.onApprovalDecided(pendingServer, pendingSession, approvalDecided("r-1"), pendingPayload, stale, nowMs = 20L)
        assertEquals(PendingStatus.Stale, row.status)
        assertNull(row.resolvedAtMs)
    }

    @Test
    fun receiptlessBackendBlocksTheLocalSubmitEdgeOfPendingRows() {
        val withoutReceipts = CapabilityGate.gates(capabilitiesWith(operations = 0), pendingTuning)
        val withReceipts = CapabilityGate.gates(capabilitiesWith(operations = 1), pendingTuning)
        val blockedEdges = PendingReducer.transitionEdges(withoutReceipts)
        assertTrue(blockedEdges.none { it.source == PendingEdgeSource.LocalSubmit })
        assertEquals(
            PendingReducer.transitionTableSnapshot().size,
            PendingReducer.transitionEdges(withReceipts).size,
        )
        val untouched = PendingReducer.onAnswering(pendingRow("r-1"), nowMs = 5, receiptsAvailable = false)
        assertEquals(PendingStatus.Pending, untouched.status)
        val unknownUntouched = PendingReducer.onAnswering(pendingRow("r-1", status = PendingStatus.Unknown), nowMs = 5, receiptsAvailable = false)
        assertEquals(PendingStatus.Unknown, unknownUntouched.status)
        assertEquals(PendingStatus.Answering, PendingReducer.onAnswering(pendingRow("r-1"), nowMs = 5, receiptsAvailable = true).status)
    }

    @Test
    fun dismissedPendingRowIsNotATombstoneAndKeepsResolvedAtNull() {
        val dismissed = PendingReducer.onDismiss(pendingRow("r-1"), nowMs = 77)
        assertEquals(PendingStatus.Pending, dismissed.status)
        assertEquals(77L, dismissed.dismissedAtMs)
        assertNull(dismissed.resolvedAtMs)
        assertFalse(dismissed.visibleInBadge)

        val counts = PendingReducer.counts(listOf(dismissed), sweepSkipped = false, verifiedBaseline = true)
        assertEquals(0, counts.badge)
        assertEquals(1, counts.dismissed)
    }

    @Test
    fun resolvingAPendingRowClearsItsDismissedMark() {
        val dismissed = PendingReducer.onDismiss(pendingRow("r-1"), nowMs = 77)
        val settled = PendingReducer.onApprovalDecided(pendingServer, pendingSession, approvalDecided("r-1"), pendingPayload, dismissed, nowMs = 88)
        assertEquals(PendingStatus.Resolved, settled.status)
        assertNull(settled.dismissedAtMs)
        assertEquals(88L, settled.resolvedAtMs)
    }

    @Test
    fun badgeCountsPendingAnsweringAndUnknownRowsOnly() {
        val rows = listOf(
            pendingRow("a", status = PendingStatus.Pending),
            pendingRow("b", status = PendingStatus.Answering),
            pendingRow("c", status = PendingStatus.Unknown),
            pendingRow("d", status = PendingStatus.Resolved, resolvedAtMs = 1L),
            pendingRow("e", status = PendingStatus.Stale),
        )
        val counts = PendingReducer.counts(rows, sweepSkipped = false, verifiedBaseline = true)
        assertEquals(3, counts.badge)
        assertEquals(0, counts.dismissed)
        assertEquals(PendingCompleteness.Complete, counts.completeness)
        assertFalse(counts.showsUnknown)
    }

    @Test
    fun badgeIsUnknownWhenThePendingKeySweepIsSkipped() {
        val counts = PendingReducer.counts(listOf(pendingRow("a")), sweepSkipped = true, verifiedBaseline = true)
        assertNull(counts.badge)
        assertEquals(PendingCompleteness.Unknown, counts.completeness)
        assertTrue(counts.showsUnknown)
    }

    @Test
    fun sweepSkipHysteresisUsesBothPendingThresholds() {
        val skipAt = pendingTuning.baselineKeySweepSkipThreshold
        val resumeAt = pendingTuning.baselineKeySweepResumeThreshold
        assertTrue(PendingReducer.sweepSkipped(skipAt + 1, previouslySkipped = false, tuning = pendingTuning))
        assertFalse(PendingReducer.sweepSkipped(skipAt, previouslySkipped = false, tuning = pendingTuning))

        assertFalse(PendingReducer.sweepSkipped(resumeAt - 1, previouslySkipped = true, tuning = pendingTuning))
        assertTrue(PendingReducer.sweepSkipped(resumeAt, previouslySkipped = true, tuning = pendingTuning))

        val insideHysteresisBand = (skipAt + resumeAt) / 2
        assertTrue(PendingReducer.sweepSkipped(insideHysteresisBand, previouslySkipped = true, tuning = pendingTuning))
        assertFalse(PendingReducer.sweepSkipped(insideHysteresisBand, previouslySkipped = false, tuning = pendingTuning))
    }

    @Test
    fun tombstoneTtlForStalePendingRowsComesFromSyncTuning() {
        val ttl = pendingTuning.tombstoneTtlMs
        val fresh = pendingRow("r-1", status = PendingStatus.Stale, firstSeenAtMs = 0L)
        assertFalse(PendingReducer.tombstoneExpired(fresh, nowMs = ttl - 1, tuning = pendingTuning))
        assertTrue(PendingReducer.tombstoneExpired(fresh, nowMs = ttl, tuning = pendingTuning))

        val resolved = pendingRow("r-2", status = PendingStatus.Resolved, firstSeenAtMs = 0L, resolvedAtMs = 5L)
        assertFalse(PendingReducer.tombstoneExpired(resolved, nowMs = ttl * 2, tuning = pendingTuning))
    }

    @Test
    fun baselineKeyCountsCountOnlyLivePendingKeys() {
        val ttl = pendingTuning.tombstoneTtlMs
        val rows = listOf(
            pendingRow("a", status = PendingStatus.Pending),
            pendingRow("b", status = PendingStatus.Answering),
            pendingRow("c", status = PendingStatus.Unknown),
            pendingRow("d", status = PendingStatus.Stale, firstSeenAtMs = 0L),
            pendingRow("e", status = PendingStatus.Resolved, resolvedAtMs = 1L),
        )
        assertEquals(3, PendingReducer.baselineKeyCount(rows, nowMs = ttl / 2, tuning = pendingTuning))
        assertEquals(3, PendingReducer.baselineKeyCount(rows, nowMs = ttl * 2, tuning = pendingTuning))
    }

    @Test
    fun sameRequestIdOnTwoServersStaysTwoDistinctPendingRows() {
        val onFirst = PendingIdentity(pendingServer, PendingKind.Approval, RequestId("r-1"))
        val onSecond = PendingIdentity(pendingOtherServer, PendingKind.Approval, RequestId("r-1"))
        assertNotEquals(onFirst, onSecond)
        val rows = mapOf(onFirst to pendingRow("r-1"), onSecond to pendingRow("r-1", serverId = pendingOtherServer))
        assertEquals(2, rows.size)
        assertEquals(pendingKey(PendingKind.Approval, "r-1"), onFirst.toPendingKey())
        assertEquals(pendingKey(PendingKind.Approval, "r-1"), onSecond.toPendingKey())
    }

    @Test
    fun approvalPendingIdentityComesFromTheAskedRequestId() {
        val asked = approvalAsked("r-9")
        assertEquals(PendingIdentity(pendingServer, PendingKind.Approval, RequestId("r-9")), PendingReducer.identityOf(pendingServer, asked))
        val row = PendingReducer.reduce(pendingServer, pendingSession, asked, pendingPayload, existing = null, nowMs = 3, tuning = pendingTuning)
        assertEquals(pendingPayload, row?.payloadJson)
        assertEquals(pendingSession, row?.sessionId)
    }

    @Test
    fun questionPendingIdentityComesFromAskUserQuestionCallId() {
        val call = questionCall("c-7")
        val identity = PendingIdentity(pendingServer, PendingKind.Question, RequestId("c-7"))
        assertEquals(identity, PendingReducer.identityOf(pendingServer, call))
        assertEquals(identity, PendingReducer.identityOf(pendingServer, questionEvent("c-7")))
        assertEquals(identity, PendingReducer.identityOf(pendingServer, questionResolvedEvent("c-7")))
        assertEquals(identity, PendingReducer.identityOf(pendingServer, questionToolResult("c-7")))
    }

    @Test
    fun nonQuestionToolCallCreatesNoPendingRow() {
        val ordinary = questionCall("c-1", toolName = "Read")
        assertNull(PendingReducer.identityOf(pendingServer, ordinary))
        assertNull(PendingReducer.reduce(pendingServer, pendingSession, ordinary, "{}", existing = null, nowMs = 1, tuning = pendingTuning))
    }

    @Test
    fun pendingDeltaReportsStaleRowsAsTombstonesOnly() {
        val staleIdentity = PendingIdentity(pendingServer, PendingKind.Approval, RequestId("r-1"))
        val resolvedIdentity = PendingIdentity(pendingServer, PendingKind.Question, RequestId("q-1"))
        val delta = PendingDelta(
            serverId = pendingServer,
            writes = listOf(
                PendingWrite(staleIdentity, pendingRow("r-1", status = PendingStatus.Stale)),
                PendingWrite(resolvedIdentity, pendingRow("q-1", kind = PendingKind.Question, status = PendingStatus.Resolved, resolvedAtMs = 1L)),
            ),
        )
        assertEquals(listOf(pendingKey(PendingKind.Approval, "r-1")), delta.tombstones)
    }

    @Test
    fun reduceEventsAppliesAskedThenResolvedWithinOnePendingBatch() {
        val delta = PendingReducer.reduceEvents(
            serverId = pendingServer,
            events = listOf(
                PendingEvent(pendingSession, approvalAsked("r-1", seq = 10), pendingPayload),
                PendingEvent(pendingSession, approvalDecided("r-1", seq = 12), pendingPayload),
            ),
            existingByIdentity = emptyMap(),
            nowMs = 50,
            tuning = pendingTuning,
        )
        val write = delta.writes.single()
        assertEquals(PendingStatus.Resolved, write.request.status)
        assertEquals(50L, write.request.resolvedAtMs)
        assertTrue(delta.tombstones.isEmpty())
    }

    @Test
    fun reduceEventsKeepsQuestionAndApprovalRowsApartWithinPendingBatch() {
        val delta = PendingReducer.reduceEvents(
            serverId = pendingServer,
            events = listOf(
                PendingEvent(pendingSession, approvalAsked("r-1", seq = 10), pendingPayload),
                PendingEvent(pendingSession, questionEvent("c-1", seq = 11), pendingPayload),
            ),
            existingByIdentity = emptyMap(),
            nowMs = 60,
            tuning = pendingTuning,
        )
        assertEquals(2, delta.writes.size)
        assertEquals(setOf(PendingKind.Approval, PendingKind.Question), delta.writes.map { it.identity.kind }.toSet())
        assertTrue(delta.writes.all { it.request.status == PendingStatus.Pending })
    }
}
