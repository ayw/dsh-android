package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.AbortCause
import ai.deepseek.dsh.android.model.ActivityEntry
import ai.deepseek.dsh.android.model.ApprovalOutcome
import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.GroupStatus
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.TimelineItem
import ai.deepseek.dsh.android.model.ToolEntry
import ai.deepseek.dsh.android.model.ToolStatus
import ai.deepseek.dsh.android.model.TurnEndReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ProjectorTurnTest {

    @Test
    fun turnEndBannerCarriesCompletedReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.Completed)
    }

    @Test
    fun turnEndBannerCarriesAbortedUserReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.Aborted(AbortCause.User))
    }

    @Test
    fun turnEndBannerCarriesAbortedParentReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.Aborted(AbortCause.Parent))
    }

    @Test
    fun turnEndBannerCarriesAbortedHookReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.Aborted(AbortCause.Hook))
    }

    @Test
    fun turnEndBannerCarriesAbortedDisposedReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.Aborted(AbortCause.Disposed))
    }

    @Test
    fun turnEndBannerCarriesAbortedLegacyReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.Aborted(AbortCause.Legacy))
    }

    @Test
    fun turnEndBannerCarriesBlockedReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.Blocked)
    }

    @Test
    fun turnEndBannerCarriesFailureReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.Failure("上游返回 500"))
    }

    @Test
    fun turnEndBannerCarriesMaxTokensReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.MaxTokens)
    }

    @Test
    fun turnEndBannerCarriesInterruptedReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.Interrupted)
    }

    @Test
    fun turnEndBannerCarriesForkedReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.Forked)
    }

    @Test
    fun turnEndBannerCarriesUnknownReasonVerbatim() {
        assertBannerCarriesReason(TurnEndReason.Unknown("loop-detected"))
    }

    @Test
    fun compactionSummaryBecomesCollapsedMarkerWithHistoryNote() {
        assertEquals(
            listOf(
                user(1, "问"),
                TimelineItem.CompactionMarker("compaction:cp1", EventSeq(4), "cp1"),
                TimelineItem.SystemNotice("compactionHistory:cp1", EventSeq(4), COMPACTION_HISTORY_NOTICE, false),
                TimelineItem.Assistant("assistant:5", EventSeq(5), "答", null, null, false, null),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.compactionStarted(2, "cp1"),
                Fx.compactionEnded(3, "cp1"),
                Fx.compactionSummary(4, "cp1", summary = "被压缩的摘要"),
                Fx.assistant(5, "答"),
            ),
        )
    }

    @Test
    fun duplicateCompactionSummaryYieldsSingleMarker() {
        assertEquals(
            listOf(
                user(1, "问"),
                TimelineItem.CompactionMarker("compaction:cp1", EventSeq(2), "cp1"),
                TimelineItem.SystemNotice("compactionHistory:cp1", EventSeq(2), COMPACTION_HISTORY_NOTICE, false),
            ),
            Fx.items(Fx.user(1, "问"), Fx.compactionSummary(2, "cp1"), Fx.compactionSummary(3, "cp1")),
        )
    }

    @Test
    fun staleCompactionEndKeepsNewerOperationOpen() {
        val stale = Fx.projection(
            Fx.user(1, "问"),
            Fx.compactionStarted(2, "cp-旧"),
            Fx.compactionStarted(3, "cp-新"),
            Fx.compactionEnded(4, "cp-旧"),
        )
        assertEquals("cp-新", stale.openCompactionId)
        assertEquals(listOf(user(1, "问")), stale.snapshot.items)

        val closed = Fx.projection(
            Fx.user(1, "问"),
            Fx.compactionStarted(2, "cp-旧"),
            Fx.compactionStarted(3, "cp-新"),
            Fx.compactionEnded(4, "cp-旧"),
            Fx.compactionEnded(5, "cp-新"),
        )
        assertNull(closed.openCompactionId)
    }

    @Test
    fun sessionEndSeedClosesTheOpenTurnWithoutAnItem() {
        val snapshot = Fx.snapshot(
            Fx.user(1, "问"),
            Fx.turnStart(2),
            Fx.call(3, "c1"),
            Fx.sessionEndSeed(4),
        )
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    3,
                    GroupStatus.Incomplete,
                    toolEntry("c1", ToolStatus.Incomplete, callSeq = 3),
                ),
            ),
            snapshot.items,
        )
        assertFalse(snapshot.runningTurn)
    }

    @Test
    fun pendingAnchorAttachesToToolCallItem() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(2, GroupStatus.Running, toolEntry("c1", ToolStatus.Called, callSeq = 2)),
                TimelineItem.PendingAnchor(
                    key = "pending:Approval:req-1",
                    primarySeq = EventSeq(2),
                    requestId = RequestId("req-1"),
                    kind = PendingKind.Approval,
                    anchoredToCallId = CallId("c1"),
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.call(2, "c1"),
                pending = listOf(Fx.pending("req-1", callId = "c1")),
            ),
        )
    }

    @Test
    fun pendingAnchorFallsBackToTurnEndOfItsSegment() {
        assertEquals(
            listOf(
                user(1, "问"),
                TimelineItem.Assistant("assistant:2", EventSeq(2), "答", null, null, false, null),
                TimelineItem.PendingAnchor(
                    key = "pending:Approval:req-2",
                    primarySeq = EventSeq(1),
                    requestId = RequestId("req-2"),
                    kind = PendingKind.Approval,
                    anchoredToCallId = null,
                ),
                user(3, "第二问"),
                TimelineItem.Assistant("assistant:4", EventSeq(4), "再答", null, null, false, null),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.assistant(2, "答"),
                Fx.user(3, "第二问"),
                Fx.assistant(4, "再答"),
                pending = listOf(Fx.pending("req-2", anchorSeq = 1)),
            ),
        )
    }

    @Test
    fun questionPendingAnchorUsesAskUserQuestionCallId() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Running,
                    toolEntry(
                        "ask-1",
                        ToolStatus.Called,
                        name = "ask_user_question",
                        args = "{\"questions\":[]}",
                        callSeq = 2,
                    ),
                ),
                TimelineItem.PendingAnchor(
                    key = "pending:Question:ask-1",
                    primarySeq = EventSeq(2),
                    requestId = RequestId("ask-1"),
                    kind = PendingKind.Question,
                    anchoredToCallId = CallId("ask-1"),
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.call(2, "ask-1", name = "ask_user_question", args = "{\"questions\":[]}"),
                pending = listOf(Fx.pending("ask-1", kind = PendingKind.Question, callId = "ask-1")),
            ),
        )
    }

    @Test
    fun questionAskedEventAnchorsByCallId() {
        assertEquals(
            listOf(
                user(1, "问"),
                TimelineItem.PendingAnchor(
                    key = "question:2",
                    primarySeq = EventSeq(2),
                    requestId = RequestId("ask-1"),
                    kind = PendingKind.Question,
                    anchoredToCallId = CallId("ask-1"),
                ),
                TimelineItem.SystemNotice(
                    key = "questionResolved:3",
                    primarySeq = EventSeq(3),
                    text = "user-question-reply",
                    developer = false,
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.questionAsked(2, "ask-1"),
                Fx.questionResolved(3, "ask-1", replySourceKind = "user-question-reply"),
            ),
        )
    }

    @Test
    fun approvalAskedEventAnchorsByRequestId() {
        assertEquals(
            listOf(
                user(1, "问"),
                TimelineItem.PendingAnchor(
                    key = "approval:2",
                    primarySeq = EventSeq(2),
                    requestId = RequestId("appr-1"),
                    kind = PendingKind.Approval,
                    anchoredToCallId = CallId("c9"),
                ),
                TimelineItem.SystemNotice(
                    key = "approvalDecided:3",
                    primarySeq = EventSeq(3),
                    text = "Rejected",
                    developer = false,
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.approvalAsked(2, "appr-1", toolName = "Bash", callId = "c9"),
                Fx.approvalDecided(3, "appr-1", outcome = ApprovalOutcome.Rejected),
            ),
        )
    }

    private fun assertBannerCarriesReason(reason: TurnEndReason) {
        assertEquals(
            listOf(
                user(1, "问"),
                TimelineItem.TurnEndBanner("turnEnd:2", EventSeq(2), reason),
            ),
            Fx.items(Fx.user(1, "问"), Fx.turnEnd(2, reason)),
        )
    }

    private fun user(seq: Long, text: String) = TimelineItem.User(
        key = "user:$seq",
        primarySeq = EventSeq(seq),
        text = text,
        wallClockMs = Fx.clock(seq),
    )

    private fun group(
        seq: Long,
        status: GroupStatus,
        vararg entries: ActivityEntry,
        turnOpen: Boolean = false,
    ) = TimelineItem.ActivityGroup(
        key = "activity:$seq",
        primarySeq = EventSeq(seq),
        entries = entries.toList(),
        status = status,
        turnOpen = turnOpen,
    )

    private fun toolEntry(
        id: String,
        status: ToolStatus,
        name: String = "Read",
        args: String? = "{}",
        result: String? = null,
        callSeq: Long? = null,
        resultSeq: Long? = null,
    ) = ActivityEntry.Tool(
        ToolEntry(
            callId = CallId(id),
            toolName = name,
            argumentsJson = args,
            status = status,
            resultJson = result,
            callSeq = callSeq?.let { EventSeq(it) },
            resultSeq = resultSeq?.let { EventSeq(it) },
        ),
    )
}
