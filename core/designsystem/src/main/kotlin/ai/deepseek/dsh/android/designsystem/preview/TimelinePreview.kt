package ai.deepseek.dsh.android.designsystem.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.timeline.ActivityGroupRow
import ai.deepseek.dsh.android.designsystem.timeline.AssistantMessageRow
import ai.deepseek.dsh.android.designsystem.timeline.CompactionMarkerRow
import ai.deepseek.dsh.android.designsystem.timeline.GapMarkerRow
import ai.deepseek.dsh.android.designsystem.timeline.HistoryUnsafeMarkerRow
import ai.deepseek.dsh.android.designsystem.timeline.PendingAnchorCard
import ai.deepseek.dsh.android.designsystem.timeline.PendingAnchorContent
import ai.deepseek.dsh.android.designsystem.timeline.PendingField
import ai.deepseek.dsh.android.designsystem.timeline.PendingOption
import ai.deepseek.dsh.android.designsystem.timeline.SubmitPhase
import ai.deepseek.dsh.android.designsystem.timeline.SystemNoticeRow
import ai.deepseek.dsh.android.designsystem.timeline.TimelineItemRow
import ai.deepseek.dsh.android.designsystem.timeline.TurnEndBannerRow
import ai.deepseek.dsh.android.designsystem.timeline.UserMessageRow
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.DshTheme
import ai.deepseek.dsh.android.model.AbortCause
import ai.deepseek.dsh.android.model.ActivityEntry
import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.GroupStatus
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.TimelineItem
import ai.deepseek.dsh.android.model.ToolEntry
import ai.deepseek.dsh.android.model.ToolStatus
import ai.deepseek.dsh.android.model.TurnEndReason
import ai.deepseek.dsh.android.model.UsageInfo

/*
 * 预览本体。文件名含 "Preview"，因此 G14 的字面量扫描刻意跳过它；
 * 这里的字符串是**伪造的服务端负载**（消息正文、工具名、题目文本），
 * 属于数据而不是 UI 文案：所有界面文案仍一律来自 `:core:designsystem` 的字符串资源。
 *
 * 数字刻意只用三位十进制或十六进制：G09.3 会把 >=4 位十进制字面量当魔数
 * （见 docs/TUNING.md），时间戳也一律取 `System.currentTimeMillis()`。
 */

private const val GroupKey = "group-1"

private fun seq(value: Int): EventSeq = EventSeq(value.toLong())

private fun sampleUser(): TimelineItem.User = TimelineItem.User(
    key = "user-1",
    primarySeq = seq(40),
    text = "帮我把 sync worker 的退避基数从 2 秒调到 3 秒，并把最大轮次写进 Tuning 表。",
    wallClockMs = System.currentTimeMillis() - 0x2DC6C0L,
)

private fun sampleAssistant(): TimelineItem.Assistant = TimelineItem.Assistant(
    key = "assistant-1",
    primarySeq = seq(52),
    text = "已定位到退避常量。改动落在两处：基数与上限说明；测试用例里的期望值也要跟着调。",
    modelId = "deepseek-v4",
    usage = UsageInfo(inputTokens = 860L, outputTokens = 210L, cacheReadTokens = 0L),
    interrupted = false,
    finishReason = "stop",
)

private fun sampleAssistantInterrupted(): TimelineItem.Assistant = TimelineItem.Assistant(
    key = "assistant-2",
    primarySeq = seq(53),
    text = "",
    modelId = null,
    usage = UsageInfo(inputTokens = null, outputTokens = null, cacheReadTokens = null),
    interrupted = true,
    finishReason = null,
)

private fun tool(
    id: String,
    name: String,
    status: ToolStatus,
    arguments: String?,
    result: String?,
    callSeq: Int?,
    resultSeq: Int?,
    conflict: Int? = null,
): ActivityEntry.Tool = ActivityEntry.Tool(
    ToolEntry(
        callId = CallId(id),
        toolName = name,
        argumentsJson = arguments,
        status = status,
        resultJson = result,
        callSeq = callSeq?.let { seq(it) },
        resultSeq = resultSeq?.let { seq(it) },
        conflictingResultSeq = conflict?.let { seq(it) },
    ),
)

private fun sampleGroup(turnOpen: Boolean, status: GroupStatus): TimelineItem.ActivityGroup =
    TimelineItem.ActivityGroup(
        key = GroupKey,
        primarySeq = seq(41),
        entries = listOf(
            tool(
                id = "call-1",
                name = "read_file",
                status = ToolStatus.Succeeded,
                arguments = "{\"path\":\"core/sync/Backoff.kt\"}",
                result = "{\"bytes\":4096,\"lines\":120}",
                callSeq = 41,
                resultSeq = 44,
            ),
            ActivityEntry.Reasoning("先确认退避基数是不是唯一入口，再检查 worker 的最大轮次是否被别处覆盖。"),
            tool(
                id = "call-2",
                name = "grep",
                status = ToolStatus.Incomplete,
                arguments = "{\"pattern\":\"backoffBase\"}",
                result = null,
                callSeq = 45,
                resultSeq = null,
            ),
            tool(
                id = "call-3",
                name = "write_file",
                status = ToolStatus.Failed,
                arguments = "{\"path\":\"docs/TUNING.md\"}",
                result = "{\"error\":\"permission_denied\"}",
                callSeq = 46,
                resultSeq = 47,
                conflict = 51,
            ),
            tool(
                id = "call-4",
                name = "run_tests",
                status = ToolStatus.Called,
                arguments = "{}",
                result = null,
                callSeq = 48,
                resultSeq = null,
            ),
            tool(
                id = "call-5",
                name = "list_dir",
                status = ToolStatus.OrphanResult,
                arguments = null,
                result = "{\"entries\":12}",
                callSeq = null,
                resultSeq = 49,
            ),
            ActivityEntry.Attempt(summary = "attempt 2/3 failed before final message", errorKind = "timeout", interrupted = true),
            ActivityEntry.UnsupportedEvent("workspace/hint"),
        ),
        status = status,
        turnOpen = turnOpen,
    )

private fun sampleApprovalAnchor(): TimelineItem.PendingAnchor = TimelineItem.PendingAnchor(
    key = "pending-1",
    primarySeq = seq(50),
    requestId = RequestId("req-approve-1"),
    kind = PendingKind.Approval,
    anchoredToCallId = CallId("call-3"),
)

private fun sampleQuestionAnchor(): TimelineItem.PendingAnchor = TimelineItem.PendingAnchor(
    key = "pending-2",
    primarySeq = seq(51),
    requestId = RequestId("req-question-1"),
    kind = PendingKind.Question,
    anchoredToCallId = null,
)

private val approvalContent = PendingAnchorContent(
    title = "write_file 想修改 docs/TUNING.md",
    detail = "该文件由验收清单引用，改动会影响门限说明。",
    argumentsPreview = "{\"path\":\"docs/TUNING.md\",\"append\":false}",
)

private val questionContent = PendingAnchorContent(
    title = "要把上限一起改掉吗？",
    detail = "questionsJson 解码出来的两个字段。",
    fields = listOf(
        PendingField(
            id = "q1",
            label = "退避基数",
            options = listOf(
                PendingOption("2", "保持 2 秒"),
                PendingOption("3", "改成 3 秒"),
            ),
            multiSelect = false,
            allowFreeText = true,
            required = true,
            placeholder = "也可以直接填秒数",
        ),
        PendingField(
            id = "q2",
            label = "要同步更新的位置",
            options = listOf(
                PendingOption("docs", "docs/TUNING.md"),
                PendingOption("test", "SyncTuning 测试"),
                PendingOption("changelog", "变更说明"),
            ),
            multiSelect = true,
            allowFreeText = false,
            required = false,
        ),
    ),
)

private fun sampleSystem(): TimelineItem.SystemNotice = TimelineItem.SystemNotice(
    key = "system-1",
    primarySeq = seq(39),
    text = "会话在后台被 worker 接管，本机只读。",
    developer = false,
)

private fun sampleDeveloper(): TimelineItem.SystemNotice = sampleSystem().copy(
    key = "dev-1",
    text = "developer: projection family = Reasoning",
    developer = true,
)

private fun sampleFailure(): TimelineItem.TurnEndBanner = TimelineItem.TurnEndBanner(
    key = "turnend-1",
    primarySeq = seq(58),
    reason = TurnEndReason.Failure("HTTP 500 upstream reset while streaming"),
)

@Composable
private fun PreviewShell(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    DshTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = DshSpacing.md),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
            ) {
                content()
            }
        }
    }
}

@Preview(name = "User message", showBackground = true)
@Composable
private fun UserMessagePreview() = PreviewShell { UserMessageRow(item = sampleUser()) }

@Preview(name = "Assistant message", showBackground = true)
@Composable
private fun AssistantMessagePreview() = PreviewShell { AssistantMessageRow(item = sampleAssistant()) }

@Preview(name = "Assistant interrupted / empty body", showBackground = true)
@Composable
private fun AssistantInterruptedPreview() = PreviewShell {
    AssistantMessageRow(item = sampleAssistantInterrupted())
}

@Preview(name = "Activity group collapsed", showBackground = true)
@Composable
private fun ActivityGroupCollapsedPreview() = PreviewShell {
    ActivityGroupRow(
        item = sampleGroup(turnOpen = true, status = GroupStatus.Running),
        expanded = false,
        onToggle = {},
    )
}

@Preview(name = "Activity group expanded", showBackground = true)
@Composable
private fun ActivityGroupExpandedPreview() = PreviewShell {
    ActivityGroupRow(
        item = sampleGroup(turnOpen = false, status = GroupStatus.Incomplete),
        expanded = true,
        onToggle = {},
    )
}

@Preview(name = "System and developer notice", showBackground = true)
@Composable
private fun SystemNoticePreview() = PreviewShell {
    SystemNoticeRow(item = sampleSystem())
    SystemNoticeRow(item = sampleDeveloper())
}

@Preview(name = "Compaction marker", showBackground = true)
@Composable
private fun CompactionMarkerPreview() = PreviewShell {
    CompactionMarkerRow(
        item = TimelineItem.CompactionMarker(
            key = "compact-1",
            primarySeq = seq(30),
            compactionId = "cmp-1",
        ),
    )
}

@Preview(name = "Turn end: all five surfaces", showBackground = true, heightDp = 900)
@Composable
private fun TurnEndPreview() = PreviewShell {
    val reasons = listOf(
        TurnEndReason.Completed,
        TurnEndReason.Aborted(AbortCause.User),
        TurnEndReason.Blocked,
        TurnEndReason.Failure("upstream reset while streaming"),
        TurnEndReason.MaxTokens,
        TurnEndReason.Interrupted,
        TurnEndReason.Forked,
        TurnEndReason.Unknown("legacy"),
    )
    reasons.forEachIndexed { index, reason ->
        TurnEndBannerRow(
            item = TimelineItem.TurnEndBanner(
                key = "turnend-$index",
                primarySeq = seq(60 + index),
                reason = reason,
            ),
        )
    }
}

@Preview(name = "Gap marker", showBackground = true)
@Composable
private fun GapMarkerPreview() = PreviewShell {
    GapMarkerRow(
        item = TimelineItem.GapMarker(
            key = "gap-1",
            primarySeq = seq(70),
            expectedSeq = seq(71),
            arrivedSeq = seq(78),
        ),
    )
}

@Preview(name = "History unsafe marker", showBackground = true)
@Composable
private fun HistoryUnsafePreview() = PreviewShell {
    HistoryUnsafeMarkerRow(
        item = TimelineItem.HistoryUnsafeMarker(
            key = "unsafe-1",
            primarySeq = seq(80),
            unknownType = "session/mutation",
        ),
    )
}

@Preview(name = "Pending approval", showBackground = true)
@Composable
private fun PendingApprovalPreview() = PreviewShell {
    PendingAnchorCard(
        item = sampleApprovalAnchor(),
        content = approvalContent,
        phase = SubmitPhase.Idle,
        needsSecondConfirm = true,
        onDecision = {},
        onDismiss = {},
    )
}

@Preview(name = "Pending question", showBackground = true)
@Composable
private fun PendingQuestionPreview() = PreviewShell {
    PendingAnchorCard(
        item = sampleQuestionAnchor(),
        content = questionContent,
        phase = SubmitPhase.Idle,
        onDecision = {},
    )
}

@Preview(name = "Pending submitting", showBackground = true)
@Composable
private fun PendingSubmittingPreview() = PreviewShell {
    PendingAnchorCard(
        item = sampleApprovalAnchor(),
        content = approvalContent,
        phase = SubmitPhase.Submitting,
        submitting = true,
        onDecision = {},
    )
}

@Preview(name = "Pending stale: no retry offered", showBackground = true)
@Composable
private fun PendingStalePreview() = PreviewShell {
    PendingAnchorCard(
        item = sampleApprovalAnchor(),
        content = approvalContent,
        phase = SubmitPhase.Stale,
        sessionAvailable = false,
        onDecision = {},
        onDismiss = {},
    )
}

@Preview(name = "Pending result unknown with error", showBackground = true)
@Composable
private fun PendingUnknownPreview() = PreviewShell {
    PendingAnchorCard(
        item = sampleQuestionAnchor(),
        content = questionContent,
        phase = SubmitPhase.Unknown,
        errorRes = R.string.errors_pending_unknown,
        onDecision = {},
    )
}

@Preview(name = "Undecodable payload still renders", showBackground = true)
@Composable
private fun PendingUndecodablePreview() = PreviewShell {
    PendingAnchorCard(
        item = sampleApprovalAnchor(),
        content = null,
        phase = SubmitPhase.Idle,
        onDecision = {},
    )
}

@Preview(name = "Unsupported footer", showBackground = true)
@Composable
private fun UnsupportedFooterPreview() = PreviewShell {
    TimelineItemRow(
        item = TimelineItem.UnsupportedCount(
            key = "unsupported-1",
            primarySeq = seq(90),
            count = 7,
        ),
    )
}

private fun sampleTimeline(): List<TimelineItem> = listOf(
    sampleUser(),
    sampleGroup(turnOpen = false, status = GroupStatus.Complete),
    sampleAssistant(),
    TimelineItem.CompactionMarker(key = "compact-1", primarySeq = seq(30), compactionId = "cmp-1"),
    TimelineItem.GapMarker(
        key = "gap-1",
        primarySeq = seq(70),
        expectedSeq = seq(71),
        arrivedSeq = seq(78),
    ),
    sampleSystem(),
    sampleApprovalAnchor(),
    sampleFailure(),
    TimelineItem.UnsupportedCount(key = "unsupported-1", primarySeq = seq(90), count = 7),
)

@Preview(name = "Dispatcher (light)", showBackground = true, heightDp = 900)
@Composable
private fun TimelineDispatcherLightPreview() = PreviewShell { DispatcherContent() }

@Preview(name = "Dispatcher (dark)", showBackground = true, heightDp = 900)
@Composable
private fun TimelineDispatcherDarkPreview() = PreviewShell(darkTheme = true) { DispatcherContent() }

@Composable
private fun DispatcherContent() {
    var expanded by remember { mutableStateOf(setOf(GroupKey)) }
    sampleTimeline().forEach { item ->
        TimelineItemRow(
            item = item,
            expandedGroups = expanded,
            onToggleGroup = { key ->
                expanded = if (expanded.contains(key)) expanded - key else expanded + key
            },
            pendingContent = if (item is TimelineItem.PendingAnchor) approvalContent else null,
            pendingPhase = SubmitPhase.Idle,
            onPendingDecision = {},
            onPendingDismiss = {},
        )
    }
}

@Preview(name = "Wide layout keeps the 640dp column", showBackground = true, widthDp = 720)
@Composable
private fun WideLayoutPreview() = PreviewShell {
    Column(modifier = Modifier.fillMaxWidth()) {
        AssistantMessageRow(item = sampleAssistant())
        NestedBannerColumn()
    }
}

@Composable
private fun NestedBannerColumn() {
    val gutter = DshDimens.Gutter
    Column(modifier = Modifier.padding(horizontal = gutter)) {
        TurnEndBannerRow(item = sampleFailure())
    }
}
