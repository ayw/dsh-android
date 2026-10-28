package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.ActivityEntry
import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.GroupStatus
import ai.deepseek.dsh.android.model.TimelineItem
import ai.deepseek.dsh.android.model.TimelineSnapshot
import ai.deepseek.dsh.android.model.ToolEntry
import ai.deepseek.dsh.android.model.ToolStatus
import ai.deepseek.dsh.android.model.TurnEndReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectorTest {

    @Test
    fun assistantTextOnlyComesFromAssistantMessageEvents() {
        assertEquals(
            listOf(
                TimelineItem.User(
                    key = "user:1",
                    primarySeq = EventSeq(1),
                    text = "问题",
                    wallClockMs = Fx.clock(1),
                ),
                TimelineItem.SystemNotice(
                    key = "system:2",
                    primarySeq = EventSeq(2),
                    text = "系统提示",
                    developer = false,
                ),
                TimelineItem.SystemNotice(
                    key = "developer:3",
                    primarySeq = EventSeq(3),
                    text = "开发者提示",
                    developer = true,
                ),
                TimelineItem.Assistant(
                    key = "assistant:4",
                    primarySeq = EventSeq(4),
                    text = "回答",
                    modelId = null,
                    usage = null,
                    interrupted = false,
                    finishReason = null,
                ),
            ),
            Fx.items(
                Fx.user(1, "问题"),
                Fx.system(2, "系统提示"),
                Fx.developer(3, "开发者提示"),
                Fx.assistant(4, "回答"),
            ),
        )
    }

    @Test
    fun assistantMessageWithUsageOnlyStillEmitsEmptyBubble() {
        val projection = Fx.projection(Fx.user(1, "问"), Fx.assistant(2, usage = Fx.usage))
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                TimelineItem.Assistant("assistant:2", EventSeq(2), "", null, Fx.usage, false, null),
            ),
            projection.snapshot.items,
        )
        assertEquals(0, projection.skippedEmptyAssistant)
    }

    @Test
    fun emptyAssistantMessageIsSkippedAndCounted() {
        val projection = Fx.projection(
            Fx.user(1, "问"),
            Fx.assistant(2, parts = emptyList()),
            Fx.assistant(3, parts = listOf(Fx.textPart("   "))),
        )
        assertEquals(
            listOf(TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1))),
            projection.snapshot.items,
        )
        assertEquals(2, projection.skippedEmptyAssistant)
    }

    @Test
    fun duplicateAssistantMessageAtSameSeqReplacesBySeq() {
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                TimelineItem.Assistant("assistant:2", EventSeq(2), "第二版", null, null, false, null),
            ),
            Fx.items(Fx.user(1, "问"), Fx.assistant(2, "第一版"), Fx.assistant(2, "第二版")),
        )
    }

    @Test
    fun replayedEventsAreIdempotent() {
        val stream = listOf(
            Fx.user(1, "问"),
            Fx.assistant(2, "答"),
            Fx.call(3, "c1"),
            Fx.result(4, "c1"),
            Fx.turnEnd(5, TurnEndReason.Completed),
        )
        assertEquals(Fx.snapshotOf(stream), Fx.snapshotOf(stream + stream))
    }

    @Test
    fun interruptedAssistantKeepsPrefixTextAndFlag() {
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                TimelineItem.Assistant("assistant:2", EventSeq(2), "已生成的前半段", null, null, true, null),
            ),
            Fx.items(Fx.user(1, "问"), Fx.assistant(2, "已生成的前半段", interrupted = true)),
        )
    }

    @Test
    fun assistantAttemptFoldsIntoActivityAttemptEntry() {
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                TimelineItem.ActivityGroup(
                    key = "activity:2",
                    primarySeq = EventSeq(2),
                    entries = listOf(ActivityEntry.Attempt("超时后重试", "timeout", interrupted = false)),
                    status = GroupStatus.Complete,
                    turnOpen = false,
                ),
                TimelineItem.Assistant("assistant:3", EventSeq(3), "答", null, null, false, null),
            ),
            Fx.items(Fx.user(1, "问"), Fx.attempt(2, "超时后重试", errorKind = "timeout"), Fx.assistant(3, "答")),
        )
    }

    @Test
    fun humanUserMessageStartsNewSegment() {
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "第一问", Fx.clock(1)),
                activityGroup(2, calledTool("c1", 2)),
                TimelineItem.User("user:3", EventSeq(3), "第二问", Fx.clock(3)),
                activityGroup(4, calledTool("c2", 4)),
            ),
            Fx.items(Fx.user(1, "第一问"), Fx.call(2, "c1"), Fx.user(3, "第二问"), Fx.call(4, "c2")),
        )
    }

    @Test
    fun injectedUserMessageDoesNotStartNewSegment() {
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "第一问", Fx.clock(1)),
                TimelineItem.ActivityGroup(
                    key = "activity:2",
                    primarySeq = EventSeq(2),
                    entries = listOf(calledTool("c1", 2), calledTool("c2", 4)),
                    status = GroupStatus.Running,
                    turnOpen = false,
                ),
                TimelineItem.User("user:3", EventSeq(3), "系统注入的提示", Fx.clock(3)),
            ),
            Fx.items(
                Fx.user(1, "第一问"),
                Fx.call(2, "c1"),
                Fx.user(3, "系统注入的提示", sourceKind = "system-reminder"),
                Fx.call(4, "c2"),
            ),
        )
    }

    @Test
    fun turnStartSegmentsOnlyWhenSessionHasNoHumanMessage() {
        val snapshot = Fx.snapshot(
            Fx.turnStart(1),
            Fx.call(2, "c1"),
            Fx.turnStart(3),
            Fx.call(4, "c2"),
        )
        assertEquals(
            listOf(
                activityGroup(2, calledTool("c1", 2)),
                TimelineItem.ActivityGroup(
                    key = "activity:4",
                    primarySeq = EventSeq(4),
                    entries = listOf(calledTool("c2", 4)),
                    status = GroupStatus.Running,
                    turnOpen = true,
                ),
            ),
            snapshot.items,
        )
        assertTrue(snapshot.runningTurn)
    }

    @Test
    fun turnStartDoesNotSegmentWhenSessionHasHumanMessage() {
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                TimelineItem.ActivityGroup(
                    key = "activity:3",
                    primarySeq = EventSeq(3),
                    entries = listOf(calledTool("c1", 3), calledTool("c2", 5)),
                    status = GroupStatus.Running,
                    turnOpen = true,
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.turnStart(2),
                Fx.call(3, "c1"),
                Fx.turnStart(4),
                Fx.call(5, "c2"),
            ),
        )
    }

    @Test
    fun stepEventsProduceNoTimelineItems() {
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                TimelineItem.ActivityGroup(
                    key = "activity:3",
                    primarySeq = EventSeq(3),
                    entries = listOf(incompleteTool("c1", 3), incompleteTool("c2", 6)),
                    status = GroupStatus.Incomplete,
                    turnOpen = false,
                ),
                TimelineItem.TurnEndBanner("turnEnd:8", EventSeq(8), TurnEndReason.Completed),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.stepStart(2),
                Fx.call(3, "c1"),
                Fx.stepEnd(4),
                Fx.stepStart(5),
                Fx.call(6, "c2"),
                Fx.stepEnd(7),
                Fx.turnEnd(8, TurnEndReason.Completed),
            ),
        )
    }

    @Test
    fun contiguousIgnorableUnknownsCollapseIntoOneCount() {
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                TimelineItem.UnsupportedCount("unsupported:2", EventSeq(2), 3),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.unknown(2, "帧类型甲", ignorable = true),
                Fx.unknown(3, "帧类型乙", ignorable = true),
                Fx.unknown(4, "帧类型丙", ignorable = true),
            ),
        )
    }

    @Test
    fun nonContiguousIgnorableUnknownsStartSeparateCounts() {
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                TimelineItem.UnsupportedCount("unsupported:2", EventSeq(2), 2),
                TimelineItem.SystemNotice("system:4", EventSeq(4), "分隔事件", false),
                TimelineItem.UnsupportedCount("unsupported:5", EventSeq(5), 1),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.unknown(2, "帧类型甲", ignorable = true),
                Fx.unknown(3, "帧类型乙", ignorable = true),
                Fx.system(4, "分隔事件"),
                Fx.unknown(5, "帧类型丙", ignorable = true),
            ),
        )
    }

    @Test
    fun nonIgnorableUnknownMarksHistoryUnsafeAndStillAdvancesFrontier() {
        val snapshot = Fx.snapshot(
            Fx.user(1, "问"),
            Fx.unknown(2, "帧类型甲", ignorable = false),
            Fx.assistant(3, "答"),
        )
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                TimelineItem.HistoryUnsafeMarker("historyUnsafe:2", EventSeq(2), "帧类型甲"),
                TimelineItem.Assistant("assistant:3", EventSeq(3), "答", null, null, false, null),
            ),
            snapshot.items,
        )
        assertTrue(snapshot.historyUnsafe)
        assertEquals(EventSeq(3), snapshot.contiguousSeq)
        assertFalse(snapshot.hasGap)
    }

    @Test
    fun gapMarkerRecordsExpectedAndArrivedSeq() {
        val snapshot = Fx.snapshot(
            Fx.user(8, "问"),
            Fx.assistant(9, "答"),
            position = Fx.frontierAt(5),
        )
        assertEquals(
            listOf(
                TimelineItem.GapMarker("gap:6", EventSeq(6), EventSeq(6), EventSeq(8)),
                TimelineItem.User("user:8", EventSeq(8), "问", Fx.clock(8)),
                TimelineItem.Assistant("assistant:9", EventSeq(9), "答", null, null, false, null),
            ),
            snapshot.items,
        )
        assertEquals(EventSeq(5), snapshot.contiguousSeq)
        assertTrue(snapshot.hasGap)
    }

    @Test
    fun gapMarkerSplitsActivityGroups() {
        val snapshot = Fx.snapshot(
            Fx.user(1, "问"),
            Fx.call(2, "c1"),
            Fx.call(4, "c2"),
        )
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                activityGroup(2, calledTool("c1", 2)),
                TimelineItem.GapMarker("gap:3", EventSeq(3), EventSeq(3), EventSeq(4)),
                activityGroup(4, calledTool("c2", 4)),
            ),
            snapshot.items,
        )
        assertEquals(2, snapshot.items.count { it is TimelineItem.ActivityGroup })
        assertEquals(EventSeq(2), snapshot.contiguousSeq)
        assertTrue(snapshot.hasGap)
    }

    @Test
    fun snapshotPositionIsDerivedFromEvents() {
        assertEquals(
            TimelineSnapshot(
                sessionId = Fx.session,
                items = listOf(
                    TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                    TimelineItem.Assistant("assistant:2", EventSeq(2), "答", null, null, false, null),
                ),
                contiguousSeq = EventSeq(2),
                hasGap = false,
                historyUnsafe = false,
                runningTurn = false,
            ),
            Fx.snapshot(Fx.user(1, "问"), Fx.assistant(2, "答")),
        )
    }

    @Test
    fun runningTurnStaysOpenUntilTurnEnd() {
        assertTrue(
            Fx.snapshot(Fx.user(1, "问"), Fx.turnStart(2), Fx.call(3, "c1")).runningTurn,
        )
        assertTrue(
            Fx.snapshot(Fx.user(1, "问"), Fx.turnStart(2), Fx.user(3, "第二问")).runningTurn,
        )
        assertFalse(
            Fx.snapshot(
                Fx.user(1, "问"),
                Fx.turnStart(2),
                Fx.call(3, "c1"),
                Fx.turnEnd(4, TurnEndReason.Completed),
            ).runningTurn,
        )
    }

    @Test
    fun sessionStatusEventsBecomeSystemNotices() {
        assertEquals(
            listOf(
                TimelineItem.User("user:1", EventSeq(1), "问", Fx.clock(1)),
                TimelineItem.SystemNotice("system:2", EventSeq(2), "系统", false),
                TimelineItem.SystemNotice("developer:3", EventSeq(3), "开发者", true),
                TimelineItem.SystemNotice("model:4", EventSeq(4), "模型X", false),
                TimelineItem.SystemNotice("goal:5", EventSeq(5), "active 目标", false),
                TimelineItem.SystemNotice("cwd:6", EventSeq(6), "/tmp", false),
                TimelineItem.SystemNotice("title:7", EventSeq(7), "标题", false),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.system(2, "系统"),
                Fx.developer(3, "开发者"),
                Fx.modelSelection(4, "模型X"),
                Fx.goalChange(5, "active", "目标"),
                Fx.workingDir(6, "/tmp"),
                Fx.sessionTitle(7, "标题"),
                Fx.system(8, "   "),
            ),
        )
    }

    private fun activityGroup(seq: Long, vararg entries: ActivityEntry) = TimelineItem.ActivityGroup(
        key = "activity:$seq",
        primarySeq = EventSeq(seq),
        entries = entries.toList(),
        status = GroupStatus.Running,
        turnOpen = false,
    )

    private fun calledTool(id: String, callSeq: Long) = ActivityEntry.Tool(
        ToolEntry(
            callId = CallId(id),
            toolName = "Read",
            argumentsJson = "{}",
            status = ToolStatus.Called,
            resultJson = null,
            callSeq = EventSeq(callSeq),
            resultSeq = null,
        ),
    )

    private fun incompleteTool(id: String, callSeq: Long) = ActivityEntry.Tool(
        ToolEntry(
            callId = CallId(id),
            toolName = "Read",
            argumentsJson = "{}",
            status = ToolStatus.Incomplete,
            resultJson = null,
            callSeq = EventSeq(callSeq),
            resultSeq = null,
        ),
    )
}
