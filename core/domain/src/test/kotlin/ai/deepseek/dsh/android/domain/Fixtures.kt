package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.ApprovalOutcome
import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.ContentPart
import ai.deepseek.dsh.android.model.DurableEvent
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TimelineItem
import ai.deepseek.dsh.android.model.TimelineSnapshot
import ai.deepseek.dsh.android.model.TurnEndReason
import ai.deepseek.dsh.android.model.UsageInfo

/** 构造事件与快照的样板，测试里只留下真正决定行为的部分。 */
internal object Fx {
    val session = SessionId("s-1")
    val noFrontier = SessionPosition(null, null)
    val usage = UsageInfo(11L, 7L, 3L)

    fun seq(n: Long): EventSeq = EventSeq(n)

    fun clock(n: Long): Long = n * 100L

    fun frontierAt(n: Long): SessionPosition = SessionPosition(EventSeq(n), EventSeq(n))

    fun user(n: Long, text: String, sourceKind: String = "user"): DurableEvent =
        DurableEvent.UserMessage(seq(n), clock(n), text, sourceKind, null)

    fun assistant(
        n: Long,
        text: String = "",
        usage: UsageInfo? = null,
        interrupted: Boolean = false,
        parts: List<ContentPart>? = null,
        model: String? = null,
        finish: String? = null,
    ): DurableEvent = DurableEvent.AssistantMessage(
        seq(n),
        clock(n),
        parts ?: listOf(ContentPart.Text(text)),
        usage,
        interrupted,
        model,
        finish,
    )

    fun attempt(n: Long, text: String? = null, errorKind: String? = null): DurableEvent =
        DurableEvent.AssistantAttempt(seq(n), clock(n), text, errorKind)

    fun call(n: Long, id: String, name: String = "Read", args: String = "{}"): DurableEvent =
        DurableEvent.ToolCall(seq(n), clock(n), CallId(id), name, args, null)

    fun result(
        n: Long,
        id: String?,
        name: String? = null,
        content: String = "输出",
        error: Boolean = false,
    ): DurableEvent = DurableEvent.ToolResult(seq(n), clock(n), id?.let { CallId(it) }, name, content, error, null)

    fun turnStart(n: Long): DurableEvent = DurableEvent.TurnStart(seq(n), clock(n))

    fun turnEnd(n: Long, reason: TurnEndReason): DurableEvent =
        DurableEvent.TurnEnd(seq(n), clock(n), reason)

    fun stepStart(n: Long): DurableEvent = DurableEvent.StepStart(seq(n), clock(n))

    fun stepEnd(n: Long): DurableEvent = DurableEvent.StepEnd(seq(n), clock(n))

    fun approvalAsked(n: Long, requestId: String, toolName: String = "Bash", callId: String? = null): DurableEvent =
        DurableEvent.ApprovalAsked(seq(n), clock(n), RequestId(requestId), toolName, callId?.let { CallId(it) }, null)

    fun approvalDecided(n: Long, requestId: String, outcome: ApprovalOutcome = ApprovalOutcome.AllowedOnce): DurableEvent =
        DurableEvent.ApprovalDecided(seq(n), clock(n), RequestId(requestId), outcome)

    fun questionAsked(n: Long, id: String): DurableEvent =
        DurableEvent.QuestionAsked(seq(n), clock(n), CallId(id), "[{\"q\":1}]")

    fun questionResolved(n: Long, id: String, replySourceKind: String? = "user"): DurableEvent =
        DurableEvent.QuestionResolved(seq(n), clock(n), CallId(id), "{\"a\":1}", replySourceKind)

    fun compactionStarted(n: Long, id: String): DurableEvent =
        DurableEvent.CompactionStarted(seq(n), clock(n), id, 3L)

    fun compactionEnded(n: Long, id: String, error: String? = null): DurableEvent =
        DurableEvent.CompactionEnded(seq(n), clock(n), id, error)

    fun compactionSummary(n: Long, id: String, summary: String = "摘要"): DurableEvent =
        DurableEvent.CompactionSummary(seq(n), clock(n), id, summary)

    fun sessionEndSeed(n: Long): DurableEvent = DurableEvent.SessionEndSeed(seq(n), clock(n))

    fun system(n: Long, text: String): DurableEvent = DurableEvent.SystemMessage(seq(n), clock(n), text, null)

    fun developer(n: Long, text: String): DurableEvent = DurableEvent.DeveloperMessage(seq(n), clock(n), text, null)

    fun modelSelection(n: Long, model: String): DurableEvent = DurableEvent.ModelSelection(seq(n), clock(n), model)

    fun goalChange(n: Long, phase: String, objective: String? = null): DurableEvent =
        DurableEvent.GoalChange(seq(n), clock(n), phase, objective)

    fun workingDir(n: Long, path: String): DurableEvent = DurableEvent.WorkingDirectoryChange(seq(n), clock(n), path)

    fun sessionTitle(n: Long, title: String): DurableEvent = DurableEvent.SessionTitle(seq(n), clock(n), title)

    fun unknown(n: Long, type: String, ignorable: Boolean): DurableEvent =
        DurableEvent.Unknown(seq(n), clock(n), type, ignorable)

    fun textPart(text: String): ContentPart = ContentPart.Text(text)

    fun reasoningPart(text: String): ContentPart = ContentPart.Reasoning(text)

    fun blockPart(id: String, name: String = "Read", args: String = "{}"): ContentPart =
        ContentPart.ToolCallBlock(id, name, args)

    fun unsupportedPart(type: String): ContentPart = ContentPart.Unsupported(type)

    fun callId(id: String): CallId = CallId(id)

    fun requestId(id: String): RequestId = RequestId(id)

    fun pending(
        id: String,
        kind: PendingKind = PendingKind.Approval,
        callId: String? = null,
        anchorSeq: Long? = null,
        status: PendingStatus = PendingStatus.Pending,
    ): PendingRequest = PendingRequest(
        serverId = ServerId("srv-1"),
        kind = kind,
        requestId = RequestId(id),
        sessionId = session,
        status = status,
        completeness = PendingCompleteness.Complete,
        payloadJson = "{}",
        firstSeenAtMs = 0L,
        createdAtMs = 0L,
        anchorSeq = anchorSeq?.let { EventSeq(it) },
        callId = callId?.let { CallId(it) },
        toolName = null,
    )

    fun snapshotOf(
        events: List<DurableEvent>,
        pending: List<PendingRequest> = emptyList(),
        position: SessionPosition = noFrontier,
    ): TimelineSnapshot = Projector().project(session, events, pending, position)

    fun snapshot(
        vararg events: DurableEvent,
        pending: List<PendingRequest> = emptyList(),
        position: SessionPosition = noFrontier,
    ): TimelineSnapshot = snapshotOf(events.toList(), pending, position)

    fun items(
        vararg events: DurableEvent,
        pending: List<PendingRequest> = emptyList(),
        position: SessionPosition = noFrontier,
    ): List<TimelineItem> = snapshotOf(events.toList(), pending, position).items

    fun projection(
        vararg events: DurableEvent,
        pending: List<PendingRequest> = emptyList(),
        position: SessionPosition = noFrontier,
    ): Projection = Projector().projectWithStats(session, events.toList(), pending, position)
}
