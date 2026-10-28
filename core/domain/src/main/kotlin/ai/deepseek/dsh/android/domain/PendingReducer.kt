package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.DurableEvent
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingCounts
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.QUESTION_TOOL_NAME
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.WriteResult

/** 主键：同一 requestId 在两台 server 上是两条互不相干的记录。 */
data class PendingIdentity(val serverId: ServerId, val kind: PendingKind, val requestId: RequestId)

/** 409 三分类只服务于 BFF v1：v0 网关对已结算、未知 id、上游失败一律 200 `{accepted:false}`，v0 路径不产生分类。 */
enum class BffConflict { AlreadySettled, StaleRevision, UnlabelledConflict }

data class ResolveOutcome(
    val request: PendingRequest,
    val retryAllowed: Boolean,
    val refreshBaseline: Boolean,
    val receiptConfirmed: Boolean,
    val conflict: BffConflict?,
)

/**
 * 待处理身份进端口时保持类型：`kind`/`requestId` 的字符串形态只是存储与线格式细节，
 * 由 `:core:database` 的转换器与 `PendingKind.wirePrefix`（冻结的通知 tag 前缀）各自负责，
 * 不在 domain 端口上出现第三种拼法（D27）。
 */
fun pendingKeyOf(kind: PendingKind, requestId: RequestId): DurableStore.PendingKey =
    DurableStore.PendingKey(kind, requestId)

fun PendingIdentity.toPendingKey(): DurableStore.PendingKey = pendingKeyOf(kind, requestId)

fun PendingRequest.toIdentity(): PendingIdentity = PendingIdentity(serverId, kind, requestId)

/** 一帧待处理相关事件：身份之外还需要会话与原文 payload。 */
data class PendingEvent(val sessionId: SessionId, val event: DurableEvent, val payloadJson: String)

data class PendingWrite(val identity: PendingIdentity, val request: PendingRequest)

data class PendingDelta(val serverId: ServerId, val writes: List<PendingWrite>) {
    /** 成为 tombstone 的行要取消通知；`PendingRequest` 本身仍留在表里。 */
    val tombstones: List<DurableStore.PendingKey>
        get() = writes.filter { it.request.status == PendingStatus.Stale }.map { it.identity.toPendingKey() }
}

enum class PendingEdgeSource { LocalSubmit, ServerSettled, LocalResolve, BaselineSweep }

data class PendingTransition(val from: PendingStatus, val to: PendingStatus, val source: PendingEdgeSource)

/** 待处理事务状态机：转移面与 `PendingStatus` 的声明一致，非法转移一律保留原状态。 */
object PendingReducer {

    private val edgeTable: List<PendingTransition> = listOf(
        PendingTransition(PendingStatus.Pending, PendingStatus.Answering, PendingEdgeSource.LocalSubmit),
        PendingTransition(PendingStatus.Pending, PendingStatus.Resolved, PendingEdgeSource.ServerSettled),
        PendingTransition(PendingStatus.Pending, PendingStatus.Stale, PendingEdgeSource.BaselineSweep),
        PendingTransition(PendingStatus.Pending, PendingStatus.Unknown, PendingEdgeSource.LocalResolve),
        PendingTransition(PendingStatus.Answering, PendingStatus.Resolved, PendingEdgeSource.ServerSettled),
        PendingTransition(PendingStatus.Answering, PendingStatus.Stale, PendingEdgeSource.LocalResolve),
        PendingTransition(PendingStatus.Answering, PendingStatus.Unknown, PendingEdgeSource.LocalResolve),
        PendingTransition(PendingStatus.Unknown, PendingStatus.Pending, PendingEdgeSource.BaselineSweep),
        PendingTransition(PendingStatus.Unknown, PendingStatus.Answering, PendingEdgeSource.LocalSubmit),
        PendingTransition(PendingStatus.Unknown, PendingStatus.Resolved, PendingEdgeSource.ServerSettled),
        PendingTransition(PendingStatus.Unknown, PendingStatus.Stale, PendingEdgeSource.BaselineSweep),
    )

    private val legalEdges: Map<PendingStatus, Set<PendingStatus>> =
        edgeTable.groupBy { it.from }.mapValues { entry -> entry.value.map { it.to }.toSet() }

    fun canTransition(from: PendingStatus?, to: PendingStatus): Boolean =
        from == null || to == from || to in legalEdges[from].orEmpty()

    /** 转移表快照，`operations=0` 时本地提交边被撤掉。 */
    fun transitionEdges(gate: CapabilityGates): List<PendingTransition> =
        edgeTable.filter { gate.permitsEdge(it.source) }

    /** 转移表的机器快照：门禁把它与源码逐行比对，防止悄悄加边。 */
    fun transitionTableSnapshot(): List<String> =
        edgeTable.map { "${it.from.name}->${it.to.name}@${it.source.name}" }.sorted()

    /** approval 身份来自 `approval/asked` 的 data.id；question 身份来自 `ask_user_question` 的 callId。 */
    fun identityOf(serverId: ServerId, event: DurableEvent): PendingIdentity? = when (event) {
        is DurableEvent.ApprovalAsked -> PendingIdentity(serverId, PendingKind.Approval, event.requestId)
        is DurableEvent.ApprovalDecided -> PendingIdentity(serverId, PendingKind.Approval, event.requestId)
        is DurableEvent.QuestionAsked -> PendingIdentity(serverId, PendingKind.Question, RequestId(event.callId.value))
        is DurableEvent.QuestionResolved ->
            PendingIdentity(serverId, PendingKind.Question, RequestId(event.callId.value))

        is DurableEvent.ToolCall ->
            if (event.toolName == QUESTION_TOOL_NAME) questionIdentity(serverId, event.callId) else null

        is DurableEvent.ToolResult ->
            if (event.toolName == QUESTION_TOOL_NAME) questionIdentity(serverId, event.callId) else null

        else -> null
    }

    fun reduce(
        serverId: ServerId,
        sessionId: SessionId,
        event: DurableEvent,
        payloadJson: String,
        existing: PendingRequest?,
        nowMs: Long,
        tuning: SyncTuning,
    ): PendingRequest? = when (event) {
        is DurableEvent.ApprovalAsked -> onApprovalAsked(serverId, sessionId, event, payloadJson, existing, nowMs, tuning)
        is DurableEvent.ApprovalDecided -> onApprovalDecided(serverId, sessionId, event, payloadJson, existing, nowMs)
        is DurableEvent.QuestionAsked -> onQuestionAsked(serverId, sessionId, event, payloadJson, existing, nowMs, tuning)
        is DurableEvent.QuestionResolved -> onQuestionResolved(serverId, sessionId, event, payloadJson, existing, nowMs)
        is DurableEvent.ToolCall ->
            if (event.toolName == QUESTION_TOOL_NAME) {
                onQuestionToolCall(serverId, sessionId, event, payloadJson, existing, nowMs, tuning)
            } else {
                null
            }

        is DurableEvent.ToolResult ->
            if (event.toolName == QUESTION_TOOL_NAME) {
                onQuestionToolResult(serverId, sessionId, event, payloadJson, existing, nowMs)
            } else {
                null
            }

        else -> null
    }

    /** 重放的 asked 帧不重开已结算的行；只有 TTL 过期的 tombstone 才回到 PENDING。 */
    fun onApprovalAsked(
        serverId: ServerId,
        sessionId: SessionId,
        event: DurableEvent.ApprovalAsked,
        payloadJson: String,
        existing: PendingRequest?,
        nowMs: Long,
        tuning: SyncTuning,
    ): PendingRequest {
        if (existing != null) return keepOrRevive(existing, nowMs, tuning)
        return newRequest(
            identity = PendingIdentity(serverId, PendingKind.Approval, event.requestId),
            sessionId = sessionId,
            status = PendingStatus.Pending,
            payloadJson = payloadJson,
            nowMs = nowMs,
            createdAtMs = event.wallClockMs,
            anchorSeq = event.seq,
            callId = event.callId,
            toolName = event.toolName,
        )
    }

    fun onQuestionAsked(
        serverId: ServerId,
        sessionId: SessionId,
        event: DurableEvent.QuestionAsked,
        payloadJson: String,
        existing: PendingRequest?,
        nowMs: Long,
        tuning: SyncTuning,
    ): PendingRequest = upsertQuestion(
        serverId = serverId,
        sessionId = sessionId,
        callId = event.callId,
        seq = event.seq,
        createdAtMs = event.wallClockMs,
        payloadJson = payloadJson,
        existing = existing,
        nowMs = nowMs,
        tuning = tuning,
    )

    fun onQuestionToolCall(
        serverId: ServerId,
        sessionId: SessionId,
        event: DurableEvent.ToolCall,
        payloadJson: String,
        existing: PendingRequest?,
        nowMs: Long,
        tuning: SyncTuning,
    ): PendingRequest? {
        if (event.toolName != QUESTION_TOOL_NAME) return null
        return upsertQuestion(
            serverId = serverId,
            sessionId = sessionId,
            callId = event.callId,
            seq = event.seq,
            createdAtMs = event.wallClockMs,
            payloadJson = payloadJson,
            existing = existing,
            nowMs = nowMs,
            tuning = tuning,
        )
    }

    /** 服务端结算帧先到也要落一行：否则“已结算”在本地凭空消失。 */
    fun onApprovalDecided(
        serverId: ServerId,
        sessionId: SessionId,
        event: DurableEvent.ApprovalDecided,
        payloadJson: String,
        existing: PendingRequest?,
        nowMs: Long,
    ): PendingRequest = if (existing == null) {
        newRequest(
            identity = PendingIdentity(serverId, PendingKind.Approval, event.requestId),
            sessionId = sessionId,
            status = PendingStatus.Resolved,
            payloadJson = payloadJson,
            nowMs = nowMs,
            createdAtMs = event.wallClockMs,
            anchorSeq = event.seq,
            resolvedAtMs = nowMs,
        )
    } else {
        settle(existing, PendingStatus.Resolved, nowMs, confirmed = true)
    }

    fun onQuestionResolved(
        serverId: ServerId,
        sessionId: SessionId,
        event: DurableEvent.QuestionResolved,
        payloadJson: String,
        existing: PendingRequest?,
        nowMs: Long,
    ): PendingRequest = if (existing == null) {
        newRequest(
            identity = PendingIdentity(serverId, PendingKind.Question, RequestId(event.callId.value)),
            sessionId = sessionId,
            status = PendingStatus.Resolved,
            payloadJson = payloadJson,
            nowMs = nowMs,
            createdAtMs = event.wallClockMs,
            anchorSeq = event.seq,
            callId = event.callId,
            resolvedAtMs = nowMs,
        )
    } else {
        settle(existing, PendingStatus.Resolved, nowMs, confirmed = true)
    }

    /** `tool/result` 就是提问已回答；其它工具结果不改变待处理状态。 */
    fun onQuestionToolResult(
        serverId: ServerId,
        sessionId: SessionId,
        event: DurableEvent.ToolResult,
        payloadJson: String,
        existing: PendingRequest?,
        nowMs: Long,
    ): PendingRequest? {
        val callId = event.callId ?: return null
        if (event.toolName != QUESTION_TOOL_NAME) return null
        return if (existing == null) {
            newRequest(
                identity = PendingIdentity(serverId, PendingKind.Question, RequestId(callId.value)),
                sessionId = sessionId,
                status = PendingStatus.Resolved,
                payloadJson = payloadJson,
                nowMs = nowMs,
                createdAtMs = event.wallClockMs,
                anchorSeq = event.seq,
                callId = callId,
                resolvedAtMs = nowMs,
            )
        } else {
            settle(existing, PendingStatus.Resolved, nowMs, confirmed = true)
        }
    }

    /** 本地提交：Pending → Answering；`operations=0` 时没有回执，这条边被能力门整条挡下。 */
    fun onAnswering(existing: PendingRequest, nowMs: Long, receiptsAvailable: Boolean): PendingRequest =
        if (!receiptsAvailable) existing else step(existing, PendingStatus.Answering)

    /**
     * v0 只有两分支：`accepted:true` 是乐观 RESOLVED 等确认，`accepted:false` 是 STALE 且不重试，
     * 超时/无结论是 UNKNOWN 并刷新基线，任何分支都不重发。
     */
    fun onResolveResult(existing: PendingRequest, result: WriteResult, nowMs: Long): ResolveOutcome {
        val accepted = result.accepted
        return when {
            accepted == true -> ResolveOutcome(
                request = settle(existing, PendingStatus.Resolved, nowMs, confirmed = false),
                retryAllowed = false,
                refreshBaseline = false,
                receiptConfirmed = result.outcome == WriteResult.WriteOutcome.ReceiptPending,
                conflict = null,
            )

            accepted == false -> ResolveOutcome(
                request = step(existing, PendingStatus.Stale),
                retryAllowed = false,
                refreshBaseline = true,
                receiptConfirmed = false,
                conflict = null,
            )

            else -> ResolveOutcome(
                request = step(existing, PendingStatus.Unknown),
                retryAllowed = false,
                refreshBaseline = true,
                receiptConfirmed = false,
                conflict = null,
            )
        }
    }

    /**
     * 基线 sweep：present 把 UNKNOWN 重新确认为 PENDING（TTL 到期的 tombstone 同样复活），
     * absent 把仍计数的行 tombstone 成 STALE。skipped 时整段不执行。
     */
    fun onBaselineSweep(
        requests: List<PendingRequest>,
        presentKeys: Set<DurableStore.PendingKey>,
        sweepSkipped: Boolean,
        nowMs: Long,
        tuning: SyncTuning,
    ): List<PendingRequest> {
        if (sweepSkipped) return requests
        return requests.map { request ->
            if (request.toIdentity().toPendingKey() in presentKeys) {
                onBaselinePresent(request, nowMs, tuning)
            } else if (request.status.countsTowardBadge) {
                request.copy(status = PendingStatus.Stale)
            } else {
                request
            }
        }
    }

    fun onBaselinePresent(request: PendingRequest, nowMs: Long, tuning: SyncTuning): PendingRequest =
        when (request.status) {
            PendingStatus.Unknown -> request.copy(
                status = PendingStatus.Pending,
                completeness = PendingCompleteness.Stale,
            )

            PendingStatus.Stale, PendingStatus.Resolved -> keepOrRevive(request, nowMs, tuning)
            else -> request
        }

    /** 手动划掉不是 tombstone：状态不动、resolvedAt 保持 null。 */
    fun onDismiss(existing: PendingRequest, nowMs: Long): PendingRequest = existing.copy(dismissedAtMs = nowMs)

    /** 迟滞带：跳过条件严格大于 skip 阈值，恢复条件严格低于 resume 阈值，落在中间带保持原判。 */
    fun sweepSkipped(keyCount: Int, previouslySkipped: Boolean, tuning: SyncTuning): Boolean =
        if (previouslySkipped) {
            keyCount >= tuning.baselineKeySweepResumeThreshold
        } else {
            keyCount > tuning.baselineKeySweepSkipThreshold
        }

    /** sweep 的计数对象是待处理 key 数，不是 baseline 行数。 */
    fun baselineKeyCount(requests: List<PendingRequest>, nowMs: Long, tuning: SyncTuning): Int =
        requests.count { it.status.countsTowardBadge && !tombstoneExpired(it, nowMs, tuning) }

    /** tombstone 只在没有真正 resolve 的行上计时，TTL 来自 SyncTuning。 */
    fun tombstoneExpired(request: PendingRequest, nowMs: Long, tuning: SyncTuning): Boolean {
        val tombstoned = request.status == PendingStatus.Stale && request.resolvedAtMs == null
        return tombstoned && nowMs - request.firstSeenAtMs >= tuning.tombstoneTtlMs
    }

    /** sweep 未执行时 badge 只能是 null（未知），不能显示一个假 0。 */
    fun counts(
        requests: List<PendingRequest>,
        sweepSkipped: Boolean,
        verifiedBaseline: Boolean,
    ): PendingCounts {
        val badge = if (sweepSkipped) null else requests.count { it.visibleInBadge }
        val dismissed = requests.count { it.dismissedAtMs != null && it.status.countsTowardBadge }
        val completeness = when {
            sweepSkipped -> PendingCompleteness.Unknown
            verifiedBaseline -> PendingCompleteness.Complete
            else -> PendingCompleteness.Stale
        }
        return PendingCounts(badge = badge, dismissed = dismissed, completeness = completeness)
    }

    fun reduceEvents(
        serverId: ServerId,
        events: List<PendingEvent>,
        existingByIdentity: Map<PendingIdentity, PendingRequest>,
        nowMs: Long,
        tuning: SyncTuning,
    ): PendingDelta {
        val rows = LinkedHashMap(existingByIdentity)
        val touched = linkedMapOf<PendingIdentity, PendingRequest>()
        for (pending in events) {
            val identity = identityOf(serverId, pending.event) ?: continue
            val next = reduce(
                serverId = serverId,
                sessionId = pending.sessionId,
                event = pending.event,
                payloadJson = pending.payloadJson,
                existing = rows[identity],
                nowMs = nowMs,
                tuning = tuning,
            )
            if (next != null) {
                rows[identity] = next
                touched[identity] = next
            }
        }
        return PendingDelta(serverId, touched.map { PendingWrite(it.key, it.value) })
    }

    private fun upsertQuestion(
        serverId: ServerId,
        sessionId: SessionId,
        callId: CallId,
        seq: EventSeq,
        createdAtMs: Long,
        payloadJson: String,
        existing: PendingRequest?,
        nowMs: Long,
        tuning: SyncTuning,
    ): PendingRequest {
        if (existing != null) return keepOrRevive(existing, nowMs, tuning)
        return newRequest(
            identity = PendingIdentity(serverId, PendingKind.Question, RequestId(callId.value)),
            sessionId = sessionId,
            status = PendingStatus.Pending,
            payloadJson = payloadJson,
            nowMs = nowMs,
            createdAtMs = createdAtMs,
            anchorSeq = seq,
            callId = callId,
            toolName = QUESTION_TOOL_NAME,
        )
    }

    private fun questionIdentity(serverId: ServerId, callId: CallId?): PendingIdentity? =
        if (callId == null) null else PendingIdentity(serverId, PendingKind.Question, RequestId(callId.value))

    private fun keepOrRevive(existing: PendingRequest, nowMs: Long, tuning: SyncTuning): PendingRequest =
        if (tombstoneExpired(existing, nowMs, tuning)) {
            existing.copy(status = PendingStatus.Pending, dismissedAtMs = null)
        } else {
            existing
        }

    private fun step(existing: PendingRequest, to: PendingStatus): PendingRequest =
        if (canTransition(existing.status, to)) existing.copy(status = to) else existing

    private fun settle(
        existing: PendingRequest,
        to: PendingStatus,
        nowMs: Long,
        confirmed: Boolean,
    ): PendingRequest {
        val status = if (canTransition(existing.status, to)) to else existing.status
        val resolvedAt = existing.resolvedAtMs ?: if (status == PendingStatus.Resolved) nowMs else null
        return existing.copy(
            status = status,
            dismissedAtMs = null,
            resolvedAtMs = resolvedAt,
            completeness = if (confirmed) PendingCompleteness.Complete else existing.completeness,
        )
    }

    private fun newRequest(
        identity: PendingIdentity,
        sessionId: SessionId,
        status: PendingStatus,
        payloadJson: String,
        nowMs: Long,
        createdAtMs: Long,
        anchorSeq: EventSeq?,
        resolvedAtMs: Long? = null,
        callId: CallId? = null,
        toolName: String? = null,
    ): PendingRequest = PendingRequest(
        serverId = identity.serverId,
        kind = identity.kind,
        requestId = identity.requestId,
        sessionId = sessionId,
        status = status,
        completeness = PendingCompleteness.Stale,
        payloadJson = payloadJson,
        firstSeenAtMs = nowMs,
        createdAtMs = createdAtMs,
        resolvedAtMs = resolvedAtMs,
        anchorSeq = anchorSeq,
        callId = callId,
        toolName = toolName,
    )
}
