package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.ActivityEntry
import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.GroupStatus
import ai.deepseek.dsh.android.model.TimelineItem
import ai.deepseek.dsh.android.model.ToolEntry
import ai.deepseek.dsh.android.model.ToolStatus
import ai.deepseek.dsh.android.model.TurnEndReason
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectorToolsTest {

    @Test
    fun toolCallThenResultBecomesSucceededEntry() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Complete,
                    toolEntry("c1", ToolStatus.Succeeded, args = "{\"path\":\"a.txt\"}", result = "输出", callSeq = 2, resultSeq = 3),
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.call(2, "c1", args = "{\"path\":\"a.txt\"}"),
                Fx.result(3, "c1"),
            ),
        )
    }

    @Test
    fun failedToolBeatsOthersInGroupStatus() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Failed,
                    toolEntry("c1", ToolStatus.Failed, result = "出错了", callSeq = 2, resultSeq = 3),
                    toolEntry("c2", ToolStatus.Succeeded, result = "输出", callSeq = 4, resultSeq = 5),
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.call(2, "c1"),
                Fx.result(3, "c1", content = "出错了", error = true),
                Fx.call(4, "c2"),
                Fx.result(5, "c2"),
            ),
        )
    }

    @Test
    fun orphanResultMergesWithLateCall() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Complete,
                    toolEntry("c1", ToolStatus.Succeeded, result = "先到的结果", callSeq = 3, resultSeq = 2),
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.result(2, "c1", name = "Read", content = "先到的结果"),
                Fx.call(3, "c1"),
            ),
        )
    }

    @Test
    fun resultWithoutAnyCallStaysOrphanResult() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Running,
                    toolEntry("cX", ToolStatus.OrphanResult, name = "", args = null, result = "没人调用过", resultSeq = 2),
                ),
            ),
            Fx.items(Fx.user(1, "问"), Fx.result(2, "cX", content = "没人调用过")),
        )
    }

    @Test
    fun resultJoinsEmbeddedToolCallBlockById() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Complete,
                    toolEntry(
                        "b1",
                        ToolStatus.Succeeded,
                        name = "Write",
                        args = "{\"k\":1}",
                        result = "块结果",
                        callSeq = 2,
                        resultSeq = 3,
                    ),
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.assistant(2, parts = listOf(Fx.blockPart("b1", name = "Write", args = "{\"k\":1}"))),
                Fx.result(3, null, content = "块结果"),
            ),
        )
    }

    @Test
    fun resultWithoutIdsJoinsNearestCallByToolName() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Running,
                    toolEntry("c1", ToolStatus.Called, callSeq = 2),
                    toolEntry("c2", ToolStatus.Succeeded, name = "Bash", result = "Bash 结果", callSeq = 3, resultSeq = 4),
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.call(2, "c1", name = "Read"),
                Fx.call(3, "c2", name = "Bash"),
                Fx.result(4, null, name = "Bash", content = "Bash 结果"),
            ),
        )
    }

    @Test
    fun secondResultAtSameSeqIsIdempotent() {
        val expected = listOf(
            user(1, "问"),
            group(
                2,
                GroupStatus.Complete,
                toolEntry("c1", ToolStatus.Succeeded, result = "第二份", callSeq = 2, resultSeq = 3),
            ),
        )
        assertEquals(
            expected,
            Fx.items(
                Fx.user(1, "问"),
                Fx.call(2, "c1"),
                Fx.result(3, "c1", content = "第一份"),
                Fx.result(3, "c1", content = "第二份"),
            ),
        )
        assertEquals(
            expected,
            Fx.items(Fx.user(1, "问"), Fx.call(2, "c1"), Fx.result(3, "c1", content = "第二份")),
        )
    }

    @Test
    fun secondResultAtLaterSeqRecordsConflict() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Complete,
                    toolEntry(
                        "c1",
                        ToolStatus.Succeeded,
                        result = "正确结果",
                        callSeq = 2,
                        resultSeq = 3,
                        conflict = 4,
                    ),
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.call(2, "c1"),
                Fx.result(3, "c1", content = "正确结果"),
                Fx.result(4, "c1", content = "冲突结果"),
            ),
        )
    }

    @Test
    fun unansweredToolBecomesIncompleteAtTurnEnd() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(2, GroupStatus.Incomplete, toolEntry("c1", ToolStatus.Incomplete, callSeq = 2)),
                TimelineItem.TurnEndBanner("turnEnd:3", EventSeq(3), TurnEndReason.Completed),
            ),
            Fx.items(Fx.user(1, "问"), Fx.call(2, "c1"), Fx.turnEnd(3, TurnEndReason.Completed)),
        )
    }

    @Test
    fun repairResultPromotesIncompleteTool() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Complete,
                    toolEntry("c1", ToolStatus.Succeeded, result = "补到的结果", callSeq = 2, resultSeq = 4),
                ),
                TimelineItem.TurnEndBanner("turnEnd:3", EventSeq(3), TurnEndReason.Completed),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.call(2, "c1"),
                Fx.turnEnd(3, TurnEndReason.Completed),
                Fx.result(4, "c1", content = "补到的结果"),
            ),
        )
    }

    @Test
    fun standaloneToolCallWinsOverEmbeddedBlockWithSameId() {
        val items = Fx.items(
            Fx.user(1, "问"),
            Fx.call(2, "c1", args = "{\"standalone\":true}"),
            Fx.assistant(
                3,
                parts = listOf(Fx.blockPart("c1", args = "{\"embedded\":true}"), Fx.textPart("我调用了 Read")),
            ),
        )
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Running,
                    toolEntry("c1", ToolStatus.Called, args = "{\"standalone\":true}", callSeq = 2),
                ),
                TimelineItem.Assistant("assistant:3", EventSeq(3), "我调用了 Read", null, null, false, null),
            ),
            items,
        )
        assertEquals(1, groupEntryCount(items, "activity:2"))
    }

    @Test
    fun embeddedBlockThenStandaloneCallRendersOneEntry() {
        val items = Fx.items(
            Fx.user(1, "问"),
            Fx.assistant(2, parts = listOf(Fx.blockPart("c1", args = "{\"embedded\":true}"))),
            Fx.call(3, "c1", args = "{\"standalone\":true}"),
        )
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Running,
                    toolEntry("c1", ToolStatus.Called, args = "{\"standalone\":true}", callSeq = 3),
                ),
            ),
            items,
        )
        assertEquals(1, groupEntryCount(items, "activity:2"))
    }

    @Test
    fun reasoningToolAndAttemptShareOneGroup() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Running,
                    ActivityEntry.Reasoning("先想一想"),
                    toolEntry("c1", ToolStatus.Called, callSeq = 3),
                    ActivityEntry.Attempt("重试一次", "timeout", interrupted = false),
                ),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.assistant(2, parts = listOf(Fx.reasoningPart("先想一想"))),
                Fx.call(3, "c1"),
                Fx.attempt(4, "重试一次", errorKind = "timeout"),
            ),
        )
    }

    @Test
    fun groupStatusRunningBeatsIncomplete() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    2,
                    GroupStatus.Running,
                    toolEntry(
                        "result:2",
                        ToolStatus.OrphanResult,
                        name = "Ghost",
                        args = null,
                        result = "孤立结果",
                        resultSeq = 2,
                    ),
                    toolEntry("c1", ToolStatus.Incomplete, callSeq = 3),
                ),
                TimelineItem.TurnEndBanner("turnEnd:4", EventSeq(4), TurnEndReason.Completed),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.result(2, null, name = "Ghost", content = "孤立结果"),
                Fx.call(3, "c1"),
                Fx.turnEnd(4, TurnEndReason.Completed),
            ),
        )
    }

    @Test
    fun lastGroupShowsRunningWhileTurnOpen() {
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    3,
                    GroupStatus.Running,
                    toolEntry("c1", ToolStatus.Succeeded, result = "输出", callSeq = 3, resultSeq = 4),
                    turnOpen = true,
                ),
            ),
            Fx.items(Fx.user(1, "问"), Fx.turnStart(2), Fx.call(3, "c1"), Fx.result(4, "c1")),
        )
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    3,
                    GroupStatus.Complete,
                    toolEntry("c1", ToolStatus.Succeeded, result = "输出", callSeq = 3, resultSeq = 4),
                ),
                TimelineItem.TurnEndBanner("turnEnd:5", EventSeq(5), TurnEndReason.Completed),
            ),
            Fx.items(
                Fx.user(1, "问"),
                Fx.turnStart(2),
                Fx.call(3, "c1"),
                Fx.result(4, "c1"),
                Fx.turnEnd(5, TurnEndReason.Completed),
            ),
        )
    }

    @Test
    fun stepsInOneTurnMergeIntoSameGroup() {
        val items = Fx.items(
            Fx.user(1, "问"),
            Fx.turnStart(2),
            Fx.stepStart(3),
            Fx.call(4, "c1"),
            Fx.stepEnd(5),
            Fx.assistant(6, parts = listOf(Fx.reasoningPart("再想想"))),
            Fx.stepStart(7),
            Fx.call(8, "c2"),
            Fx.stepEnd(9),
            Fx.turnEnd(10, TurnEndReason.Completed),
        )
        assertEquals(
            listOf(
                user(1, "问"),
                group(
                    4,
                    GroupStatus.Incomplete,
                    toolEntry("c1", ToolStatus.Incomplete, callSeq = 4),
                    ActivityEntry.Reasoning("再想想"),
                    toolEntry("c2", ToolStatus.Incomplete, callSeq = 8),
                ),
                TimelineItem.TurnEndBanner("turnEnd:10", EventSeq(10), TurnEndReason.Completed),
            ),
            items,
        )
        assertEquals(1, items.count { it is TimelineItem.ActivityGroup })
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
        conflict: Long? = null,
    ) = ActivityEntry.Tool(
        ToolEntry(
            callId = CallId(id),
            toolName = name,
            argumentsJson = args,
            status = status,
            resultJson = result,
            callSeq = callSeq?.let { EventSeq(it) },
            resultSeq = resultSeq?.let { EventSeq(it) },
            conflictingResultSeq = conflict?.let { EventSeq(it) },
        ),
    )

    private fun groupEntryCount(items: List<TimelineItem>, key: String): Int =
        items.filterIsInstance<TimelineItem.ActivityGroup>()
            .single { it.key == key }
            .entries
            .size
}
