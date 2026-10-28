package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.ActivityEntry
import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.ContentPart
import ai.deepseek.dsh.android.model.DurableEvent
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.GroupStatus
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TimelineItem
import ai.deepseek.dsh.android.model.TimelineSnapshot
import ai.deepseek.dsh.android.model.ToolEntry
import ai.deepseek.dsh.android.model.ToolStatus

/**
 * 压缩会折叠掉部分原文：可见历史未必包含全部被压缩的消息。时间线只保证留痕，
 * 展示文案由 UI 用自己的语言资源覆盖。
 */
internal const val COMPACTION_HISTORY_NOTICE: String = "compaction-history-may-omit-messages"

/**
 * 活动条目归属哪一类调用事件。独立 tool/call 与 assistant/message 内嵌的 tool-call 块共用一个 id
 * 空间但字段名不同，同一个 callId 只能渲染一次：独立事件拥有条目，内嵌块只在没有独立事件时补位。
 */
internal enum class ActivitySource {
    StandaloneCall,
    EmbeddedBlock,

    /** 先看到 result、后补 call 的合并前状态。 */
    OrphanResult,
}

/** 一个分段的活动条目，渲染成一个 [TimelineItem.ActivityGroup]；条目按事件 seq 就地更新。 */
internal class Run(val segmentIndex: Int, val seq: Long) {
    val entries: MutableList<ActivityEntry> = ArrayList()
    var slot: TimelineSlot? = null
}

/** 输出槽位：要么带一个成品条目，要么是一个活动分组。同一 seq 上分组排在成品之前。 */
internal class TimelineSlot(
    val seq: Long,
    val segmentIndex: Int,
    val item: TimelineItem? = null,
    val run: Run? = null,
) {
    var outputIndex: Int = -1

    fun order(): Int = if (run != null) 0 else 1
}

/** 工具调用的可变化身：终态、迟到的第二份 result 都先记在这里，渲染时才冻结成 [ToolEntry]。 */
internal class ToolBuild(
    val callId: CallId,
    var toolName: String,
    var argumentsJson: String?,
    var status: ToolStatus,
    var resultJson: String?,
    var callSeq: EventSeq?,
    var resultSeq: EventSeq?,
    var conflictingResultSeq: EventSeq?,
    var resultIsError: Boolean,
    var source: ActivitySource,
) {
    var ownerRun: Run? = null

    fun toEntry(): ToolEntry = ToolEntry(
        callId = callId,
        toolName = toolName,
        argumentsJson = argumentsJson,
        status = status,
        resultJson = resultJson,
        callSeq = callSeq,
        resultSeq = resultSeq,
        conflictingResultSeq = conflictingResultSeq,
    )

    fun sync() {
        val entries = ownerRun?.entries ?: return
        val index = entries.indexOfFirst { it is ActivityEntry.Tool && it.tool.callId == callId }
        if (index >= 0) entries[index] = ActivityEntry.Tool(toEntry())
    }
}

/** assistant/message 内嵌的 tool-call 块：与独立 tool/call 共享 id 空间，供没有 callId 的 result 关联。 */
internal class EmbeddedBlock(
    val blockId: CallId,
    val toolName: String,
    val argumentsJson: String,
    val seq: EventSeq,
) {
    var usedByResult: Boolean = false
}

/** 连续一段可忽略的未知事件，折叠成一条计数条目。 */
internal class IgnorableRun(val segmentIndex: Int, val firstSeq: Long) {
    var count: Int = 0
        private set
    var lastSeq: Long = firstSeq
        private set

    fun contiguousWith(seq: Long): Boolean = seq == lastSeq + 1

    fun extend(seq: Long) {
        count += 1
        lastSeq = seq
    }

    fun toSlot(): TimelineSlot = TimelineSlot(
        seq = firstSeq,
        segmentIndex = segmentIndex,
        item = TimelineItem.UnsupportedCount(
            key = "unsupported:$firstSeq",
            primarySeq = EventSeq(firstSeq),
            count = count,
        ),
    )
}

/**
 * 一次投影的全部状态：扫描有序事件产出槽位，再按 seq 排序渲染。
 *
 * 分段（可视 turn）只由真人 user/message 开启；会话内没有任何真人消息时退回 turn/start，
 * 注入型 user/message 与 step 边界都不分段。一个分段只有一个活动 run（工具、推理、attempt 全部
 * 并进同一组），只有分段起点、turn/end、会话结束种子、空洞与历史不可信边界才收口，
 * 所以消息气泡不会把活动组切碎。turn/end 把仍未收到结果的调用标为 Incomplete（不是 Failed）。
 */
internal class TimelineBuilder(
    private val sessionId: SessionId,
    rawEvents: List<DurableEvent>,
    private val pending: List<PendingRequest>,
    private val position: SessionPosition,
) {
    private val events: List<DurableEvent> = lastEventPerSeq(rawEvents)
    private val fallbackToTurnStart: Boolean =
        events.none { it is DurableEvent.UserMessage && it.isHumanInitiated }

    private val slots: MutableList<TimelineSlot> = ArrayList()
    private val tools: LinkedHashMap<CallId, ToolBuild> = LinkedHashMap()
    private val embeddedBlocks: MutableList<EmbeddedBlock> = ArrayList()
    private val compactionIds: MutableSet<String> = LinkedHashSet()
    private val segmentRanges: MutableList<LongRange> = ArrayList()
    private val ignorableRuns: MutableList<IgnorableRun> = ArrayList()

    private var skippedEmptyAssistant = 0
    private var turnOpen = false
    private var sawHistoryUnsafe = false
    private var segmentIndex = -1
    private var activeRun: Run? = null
    private var currentIgnorableRun: IgnorableRun? = null
    private var openCompactionId: String? = null
    private var previousSeq: Long? = null
    private var forceSegmentBreak = false

    fun project(): Projection {
        for (event in events) {
            markGapBefore(event)
            process(event)
        }
        val contiguous = derivedContiguousSeq()
        val maxSeq = events.maxOfOrNull { it.seq.value }
        return Projection(
            snapshot = TimelineSnapshot(
                sessionId = sessionId,
                items = render(),
                contiguousSeq = contiguous,
                hasGap = maxSeq != null && contiguous != null && contiguous.value < maxSeq,
                historyUnsafe = sawHistoryUnsafe,
                runningTurn = turnOpen,
            ),
            skippedEmptyAssistant = skippedEmptyAssistant,
            openCompactionId = openCompactionId,
        )
    }

    // ── 扫描 ──────────────────────────────────────────────────

    private fun process(event: DurableEvent) {
        if (!(event is DurableEvent.Unknown && event.ignorable)) currentIgnorableRun = null
        if (isSegmentStart(event) || forceSegmentBreak) {
            forceSegmentBreak = false
            closeRun()
            segmentIndex += 1
            segmentRanges.add(event.seq.value..event.seq.value)
        } else if (segmentIndex >= 0) {
            val range = segmentRanges[segmentIndex]
            segmentRanges[segmentIndex] = range.first..maxOf(range.last, event.seq.value)
        }

        when (event) {
            is DurableEvent.UserMessage -> onUserMessage(event)
            is DurableEvent.AssistantMessage -> onAssistantMessage(event)
            is DurableEvent.AssistantAttempt -> onAssistantAttempt(event)
            is DurableEvent.ToolCall -> onToolCall(event)
            is DurableEvent.ToolResult -> onToolResult(event)
            // turn/step 的开合只驱动“本回合是否仍在进行”，不产出分隔线：
            // 同一 turn 的多个 step 的活动条目要能并进同一组。
            is DurableEvent.TurnStart -> turnOpen = true
            is DurableEvent.TurnEnd -> onTurnEnd(event)
            is DurableEvent.StepStart -> turnOpen = true
            is DurableEvent.StepEnd -> Unit
            is DurableEvent.ApprovalAsked -> onApprovalAsked(event)
            is DurableEvent.ApprovalDecided -> onApprovalDecided(event)
            is DurableEvent.QuestionAsked -> onQuestionAsked(event)
            is DurableEvent.QuestionResolved -> onQuestionResolved(event)
            is DurableEvent.CompactionStarted -> {
                openCompactionId = event.compactionId
            }

            is DurableEvent.CompactionEnded -> onCompactionEnded(event)
            is DurableEvent.CompactionSummary -> onCompactionSummary(event)
            // 会话结束种子只是 turn 的收口标记，不产出条目。
            is DurableEvent.SessionEndSeed -> {
                turnOpen = false
                markPendingIncomplete()
                closeRun()
            }

            is DurableEvent.SystemMessage -> onNotice(event.text, "system", event.seq, false)
            is DurableEvent.DeveloperMessage -> onNotice(event.text, "developer", event.seq, true)
            is DurableEvent.ModelSelection -> onNotice(event.modelId, "model", event.seq, false)
            is DurableEvent.GoalChange -> onNotice(
                if (event.objective == null) event.phase else "${event.phase} ${event.objective}",
                "goal",
                event.seq,
                false,
            )

            is DurableEvent.WorkingDirectoryChange -> onNotice(event.path, "cwd", event.seq, false)
            is DurableEvent.SessionTitle -> onNotice(event.title, "title", event.seq, false)
            is DurableEvent.Unknown -> onUnknown(event)
        }
    }

    /** 只有真人消息开启新的可视分段；注入型 user/message 与 turn/step 边界都不分段。 */
    private fun isSegmentStart(event: DurableEvent): Boolean = when (event) {
        is DurableEvent.UserMessage -> event.isHumanInitiated
        is DurableEvent.TurnStart -> fallbackToTurnStart
        else -> false
    }

    private fun onUserMessage(event: DurableEvent.UserMessage) {
        if (event.text.isBlank()) return
        addItem(event.seq, TimelineItem.User(
            key = "user:${event.seq.value}",
            primarySeq = event.seq,
            text = event.text,
            wallClockMs = event.wallClockMs,
        ))
    }

    private fun onAssistantMessage(event: DurableEvent.AssistantMessage) {
        val texts = ArrayList<String>()
        for (part in event.parts) {
            when (part) {
                is ContentPart.Text -> if (part.text.isNotBlank()) texts.add(part.text)

                is ContentPart.Reasoning -> if (part.text.isNotBlank()) {
                    ensureRun(event.seq).entries.add(ActivityEntry.Reasoning(part.text))
                }

                is ContentPart.ToolCallBlock -> onEmbeddedBlock(event.seq, part)

                is ContentPart.Unsupported ->
                    ensureRun(event.seq).entries.add(ActivityEntry.UnsupportedEvent(part.type))
            }
        }
        // usage 必须可见：正文为空也要留一条带 usage 的条目，否则计费/限额信息丢失。
        val text = texts.joinToString("\n")
        if (text.isEmpty() && event.usage == null) {
            skippedEmptyAssistant += 1
            return
        }
        addItem(event.seq, TimelineItem.Assistant(
            key = "assistant:${event.seq.value}",
            primarySeq = event.seq,
            text = text,
            modelId = event.modelId,
            usage = event.usage,
            interrupted = event.interrupted,
            finishReason = event.finishReason,
        ))
    }

    private fun onEmbeddedBlock(seq: EventSeq, part: ContentPart.ToolCallBlock) {
        val blockId = CallId(part.blockId)
        embeddedBlocks.add(EmbeddedBlock(blockId, part.toolName, part.argumentsJson, seq))
        // 独立 tool/call 事件与内嵌块是同一个调用：只保留独立事件那一条活动条目。
        if (tools.containsKey(blockId)) return
        val tool = ToolBuild(
            callId = blockId,
            toolName = part.toolName,
            argumentsJson = part.argumentsJson,
            status = ToolStatus.Called,
            resultJson = null,
            callSeq = seq,
            resultSeq = null,
            conflictingResultSeq = null,
            resultIsError = false,
            source = ActivitySource.EmbeddedBlock,
        )
        tools[blockId] = tool
        attachTool(tool, ensureRun(seq))
    }

    private fun onToolCall(event: DurableEvent.ToolCall) {
        val existing = tools[event.callId]
        if (existing == null) {
            val tool = ToolBuild(
                callId = event.callId,
                toolName = event.toolName,
                argumentsJson = event.argumentsJson,
                status = ToolStatus.Called,
                resultJson = null,
                callSeq = event.seq,
                resultSeq = null,
                conflictingResultSeq = null,
                resultIsError = false,
                source = ActivitySource.StandaloneCall,
            )
            tools[event.callId] = tool
            attachTool(tool, ensureRun(event.seq))
            return
        }
        if (existing.source == ActivitySource.OrphanResult) {
            // 先到的 result 已知道成败，补上的 call 只补齐身份与参数，不改变终态。
            existing.status = if (existing.resultIsError) ToolStatus.Failed else ToolStatus.Succeeded
        }
        existing.source = ActivitySource.StandaloneCall
        existing.toolName = event.toolName
        existing.argumentsJson = event.argumentsJson
        existing.callSeq = event.seq
        existing.sync()
    }

    private fun onToolResult(event: DurableEvent.ToolResult) {
        val target = resolveToolTarget(event)
        if (target.resultSeq == event.seq) return
        if (target.status.isTerminal || target.status == ToolStatus.OrphanResult) {
            target.conflictingResultSeq = target.conflictingResultSeq ?: event.seq
        } else {
            target.status = if (event.isError) ToolStatus.Failed else ToolStatus.Succeeded
            target.resultSeq = event.seq
            target.resultJson = event.contentJson
            target.resultIsError = event.isError
        }
        target.sync()
    }

    /**
     * 工具关联键的唯一实现点，优先级固定：
     * 1. result 自带的 callId —— 宿主写下的身份，唯一可靠；
     * 2. 该 result 之前最近的 assistant/message 内嵌 tool-call 块的 block.id —— 与 callId 同一 id 空间、不同字段名；
     * 3. toolName + seq 邻近 —— 早期 payload 两个身份字段都缺时的兜底，因此绝不参与前两级的匹配。
     * 三级都落空时用 result 的 seq 造临时 id，等 call 到达再合并。
     */
    private fun resolveToolTarget(event: DurableEvent.ToolResult): ToolBuild {
        event.callId?.let { found -> tools[found]?.let { return it } }

        if (event.callId == null) {
            val block = embeddedBlocks.lastOrNull {
                !it.usedByResult && it.seq.value <= event.seq.value &&
                    (event.toolName == null || it.toolName == event.toolName)
            }
            if (block != null) {
                block.usedByResult = true
                tools[block.blockId]?.let { return it }
                return newOrphanTool(block.blockId, block.toolName, block.argumentsJson, block.seq, event)
            }
            nearestUnansweredByName(event.toolName, event.seq)?.let { return it }
        }

        val id = event.callId ?: CallId("result:${event.seq.value}")
        return tools[id] ?: newOrphanTool(id, event.toolName ?: "", null, null, event)
    }

    private fun nearestUnansweredByName(toolName: String?, resultSeq: EventSeq): ToolBuild? =
        tools.values
            .filter { it.status == ToolStatus.Called && it.resultSeq == null && (toolName == null || it.toolName == toolName) }
            .minWithOrNull(
                compareBy(
                    { it.callSeq?.let { call -> kotlin.math.abs(resultSeq.value - call.value) } ?: Long.MAX_VALUE },
                    { it.callSeq?.value ?: Long.MAX_VALUE },
                ),
            )

    private fun newOrphanTool(
        callId: CallId,
        toolName: String,
        argumentsJson: String?,
        callSeq: EventSeq?,
        result: DurableEvent.ToolResult,
    ): ToolBuild {
        val tool = ToolBuild(
            callId = callId,
            toolName = toolName,
            argumentsJson = argumentsJson,
            status = ToolStatus.OrphanResult,
            resultJson = result.contentJson,
            callSeq = callSeq,
            resultSeq = result.seq,
            conflictingResultSeq = null,
            resultIsError = result.isError,
            source = ActivitySource.OrphanResult,
        )
        tools[callId] = tool
        attachTool(tool, ensureRun(result.seq))
        return tool
    }

    private fun onAssistantAttempt(event: DurableEvent.AssistantAttempt) {
        // attempt 是诊断行，不是气泡；没有可展示文本时整条跳过。
        val summary = event.text?.takeIf { it.isNotBlank() } ?: return
        ensureRun(event.seq).entries.add(
            ActivityEntry.Attempt(summary, event.errorKind, interrupted = false),
        )
    }

    private fun onTurnEnd(event: DurableEvent.TurnEnd) {
        turnOpen = false
        markPendingIncomplete()
        closeRun()
        addItem(event.seq, TimelineItem.TurnEndBanner(
            key = "turnEnd:${event.seq.value}",
            primarySeq = event.seq,
            reason = event.reason,
        ))
    }

    private fun onApprovalAsked(event: DurableEvent.ApprovalAsked) {
        addItem(event.seq, TimelineItem.PendingAnchor(
            key = "approval:${event.seq.value}",
            primarySeq = event.seq,
            requestId = event.requestId,
            kind = PendingKind.Approval,
            anchoredToCallId = event.callId,
        ))
    }

    private fun onApprovalDecided(event: DurableEvent.ApprovalDecided) {
        addItem(event.seq, TimelineItem.SystemNotice(
            key = "approvalDecided:${event.seq.value}",
            primarySeq = event.seq,
            text = event.outcome.name,
            developer = false,
        ))
    }

    private fun onQuestionAsked(event: DurableEvent.QuestionAsked) {
        addItem(event.seq, TimelineItem.PendingAnchor(
            key = "question:${event.seq.value}",
            primarySeq = event.seq,
            requestId = RequestId(event.callId.value),
            kind = PendingKind.Question,
            anchoredToCallId = event.callId,
        ))
    }

    private fun onQuestionResolved(event: DurableEvent.QuestionResolved) {
        addItem(event.seq, TimelineItem.SystemNotice(
            key = "questionResolved:${event.seq.value}",
            primarySeq = event.seq,
            text = event.replySourceKind ?: "resolved",
            developer = false,
        ))
    }

    private fun onCompactionEnded(event: DurableEvent.CompactionEnded) {
        // 带旧 id 的迟到 end 不能关掉正在进行的压缩。
        if (event.compactionId == openCompactionId) openCompactionId = null
    }

    private fun onCompactionSummary(event: DurableEvent.CompactionSummary) {
        if (event.compactionId == openCompactionId) openCompactionId = null
        if (!compactionIds.add(event.compactionId)) return
        addItem(event.seq, TimelineItem.CompactionMarker(
            key = "compaction:${event.compactionId}",
            primarySeq = event.seq,
            compactionId = event.compactionId,
        ))
        addItem(event.seq, TimelineItem.SystemNotice(
            key = "compactionHistory:${event.compactionId}",
            primarySeq = event.seq,
            text = COMPACTION_HISTORY_NOTICE,
            developer = false,
        ))
    }

    private fun onHistoryUnsafe(type: String, seq: EventSeq) {
        closeRun()
        sawHistoryUnsafe = true
        addItem(seq, TimelineItem.HistoryUnsafeMarker(
            key = "historyUnsafe:${seq.value}",
            primarySeq = seq,
            unknownType = type,
        ))
    }

    /**
     * 空洞判定只有一处入口：期望的下一个 seq 低于实际到达的 seq。
     * 首批看 [SessionPosition.contiguousSeq]，其后看前一条已投影事件的 seq；marker 停在期望位上，
     * 到达的事件另起分段，所以它同时是 turn 与活动分组的硬边界。
     */
    private fun markGapBefore(event: DurableEvent) {
        val expected = previousSeq?.plus(1) ?: position.contiguousSeq?.let { it.value + 1 }
        previousSeq = event.seq.value
        if (expected == null || event.seq.value <= expected) return
        closeRun()
        addItem(EventSeq(expected), TimelineItem.GapMarker(
            key = "gap:${expected}",
            primarySeq = EventSeq(expected),
            expectedSeq = EventSeq(expected),
            arrivedSeq = event.seq,
        ))
        forceSegmentBreak = true
    }

    private fun onUnknown(event: DurableEvent.Unknown) {
        if (!event.ignorable) {
            onHistoryUnsafe(event.type, event.seq)
            return
        }
        val last = currentIgnorableRun
        if (last != null && last.contiguousWith(event.seq.value)) {
            last.extend(event.seq.value)
        } else {
            val run = IgnorableRun(segmentIndex.coerceAtLeast(0), event.seq.value)
            run.extend(event.seq.value)
            ignorableRuns.add(run)
            currentIgnorableRun = run
        }
    }

    private fun onNotice(text: String, prefix: String, seq: EventSeq, developer: Boolean) {
        if (text.isBlank()) return
        addItem(seq, TimelineItem.SystemNotice(
            key = "$prefix:${seq.value}",
            primarySeq = seq,
            text = text,
            developer = developer,
        ))
    }

    private fun markPendingIncomplete() {
        for (tool in tools.values) {
            if (tool.status == ToolStatus.Called) {
                tool.status = ToolStatus.Incomplete
                tool.sync()
            }
        }
    }

    // ── 槽位维护 ──────────────────────────────────────────────

    private fun ensureRun(seq: EventSeq): Run {
        activeRun?.let { return it }
        val segment = segmentIndex.coerceAtLeast(0)
        val run = Run(segment, seq.value)
        val slot = TimelineSlot(seq.value, segment, run = run)
        run.slot = slot
        slots.add(slot)
        activeRun = run
        return run
    }

    private fun attachTool(tool: ToolBuild, run: Run) {
        tool.ownerRun = run
        run.entries.add(ActivityEntry.Tool(tool.toEntry()))
    }

    private fun closeRun() {
        activeRun = null
    }

    private fun addItem(seq: EventSeq, item: TimelineItem) {
        slots.add(TimelineSlot(seq.value, segmentIndex.coerceAtLeast(0), item = item))
    }

    // ── 渲染 ──────────────────────────────────────────────────

    private fun render(): List<TimelineItem> {
        val ordered = (slots + ignorableRuns.map { it.toSlot() }).sortedWith(compareBy({ it.seq }, { it.order() }))
        val items = ArrayList<TimelineItem>(ordered.size + pending.size)
        val segmentLastIndex = HashMap<Int, Int>()
        var tailRun: Run? = null
        for (slot in ordered) {
            val run = slot.run
            if (run == null) {
                val item = slot.item ?: continue
                slot.outputIndex = items.size
                items.add(item)
                segmentLastIndex[slot.segmentIndex] = items.size - 1
                continue
            }
            if (run.entries.isEmpty()) continue
            slot.outputIndex = items.size
            items.add(groupOf(run, open = false))
            segmentLastIndex[run.segmentIndex] = items.size - 1
            tailRun = run
        }
        // 回合还开着时，最后一个活动组就是“正在进行”的那一段。
        val tail = tailRun
        if (turnOpen && tail != null) {
            val index = tail.slot?.outputIndex ?: -1
            if (index >= 0) items[index] = groupOf(tail, open = true)
        }
        return placePendingAnchors(items, segmentLastIndex)
    }

    private fun groupOf(run: Run, open: Boolean): TimelineItem.ActivityGroup = TimelineItem.ActivityGroup(
        key = "activity:${run.seq}",
        primarySeq = EventSeq(run.seq),
        entries = run.entries.toList(),
        status = if (open) GroupStatus.Running else groupStatus(run.entries),
        turnOpen = open,
    )

    private fun groupStatus(entries: List<ActivityEntry>): GroupStatus {
        var running = false
        var incomplete = false
        for (entry in entries) {
            when ((entry as? ActivityEntry.Tool)?.tool?.status) {
                ToolStatus.Failed -> return GroupStatus.Failed
                ToolStatus.Called, ToolStatus.OrphanResult -> running = true
                ToolStatus.Incomplete -> incomplete = true
                else -> Unit
            }
        }
        return when {
            running -> GroupStatus.Running
            incomplete -> GroupStatus.Incomplete
            else -> GroupStatus.Complete
        }
    }

    /** pending 锚点：对得上 callId 就贴在调用所在条目之后，否则贴在所属 turn 最后产出的条目之后。 */
    private fun placePendingAnchors(
        items: List<TimelineItem>,
        segmentLastIndex: Map<Int, Int>,
    ): List<TimelineItem> {
        if (pending.isEmpty()) return items
        val callTargets: Map<String, Int> = tools.values
            .mapNotNull { tool -> tool.ownerRun?.slot?.outputIndex?.takeIf { it >= 0 }?.let { tool.callId.value to it } }
            .toMap()

        val byTarget = LinkedHashMap<Int, MutableList<TimelineItem>>()
        for (request in pending) {
            val anchored = request.callId?.let { callTargets[it.value] }
            val target = anchored ?: turnIndex(segmentLastIndex, request.anchorSeq?.value)
            byTarget.getOrPut(target) { ArrayList() }.add(
                TimelineItem.PendingAnchor(
                    key = "pending:${request.kind}:${request.requestId.value}",
                    primarySeq = request.anchorSeq
                        ?: items.getOrNull(target)?.primarySeq
                        ?: position.contiguousSeq
                        ?: EventSeq(0),
                    kind = request.kind,
                    requestId = request.requestId,
                    anchoredToCallId = request.callId?.takeIf { callTargets.containsKey(it.value) },
                ),
            )
        }
        val out = ArrayList<TimelineItem>(items.size + pending.size)
        byTarget.remove(-1)?.let { out.addAll(it) }
        items.forEachIndexed { index, item ->
            out.add(item)
            byTarget[index]?.let { out.addAll(it) }
        }
        return out
    }

    private fun turnIndex(segmentLastIndex: Map<Int, Int>, anchorSeq: Long?): Int {
        val segment = if (anchorSeq == null) segmentRanges.size - 1 else segmentContaining(anchorSeq)
        if (segment < 0) return -1
        return segmentLastIndex[segment] ?: segmentLastIndex.values.maxOrNull() ?: -1
    }

    /** 取最后一个起始 seq 不大于该 seq 的分段；seq 落在所有分段之前时返回 -1。 */
    private fun segmentContaining(seq: Long): Int {
        var best = -1
        for ((index, range) in segmentRanges.withIndex()) {
            if (range.first <= seq) best = index
        }
        return best
    }

    private fun derivedContiguousSeq(): EventSeq? {
        if (events.isEmpty()) return position.contiguousSeq
        var contiguous = position.contiguousSeq?.value ?: events.first().seq.value
        for (event in events) {
            if (event.seq.value == contiguous + 1) contiguous = event.seq.value
        }
        return EventSeq(contiguous)
    }

    private companion object {
        fun lastEventPerSeq(raw: List<DurableEvent>): List<DurableEvent> {
            val bySeq = LinkedHashMap<Long, DurableEvent>()
            for (event in raw) bySeq[event.seq.value] = event
            return bySeq.values.sortedBy { it.seq.value }
        }
    }
}
