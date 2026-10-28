package ai.deepseek.dsh.android.designsystem.timeline

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ai.deepseek.dsh.android.model.TimelineItem

/**
 * 时间线条目的唯一分发点。
 *
 * `when` **不带 `else`**：给 `TimelineItem` 加一个新变体，这里立刻编译失败，
 * 逼着新条目在 UI 侧显式表态（这是设计意图，由 TimelineDispatcherExhaustivenessTest 兜底）。
 *
 * 参数都带默认值：只读 UI（M4）只需要 `expandedGroups` / `onToggleGroup`，
 * 写路径（M5）再补 pending 一组参数。
 *
 * @param expandedGroups 已展开的活动组 key 集合（`TimelineItem.key`）
 * @param pendingContent payload 解码结果；只有 [TimelineItem.PendingAnchor] 会读它
 */
@Composable
fun TimelineItemRow(
    item: TimelineItem,
    modifier: Modifier = Modifier,
    expandedGroups: Set<String> = emptySet(),
    onToggleGroup: (String) -> Unit = {},
    pendingContent: PendingAnchorContent? = null,
    pendingPhase: SubmitPhase = SubmitPhase.Idle,
    pendingSubmitting: Boolean = false,
    @StringRes pendingErrorRes: Int? = null,
    pendingNeedsSecondConfirm: Boolean = false,
    pendingSessionAvailable: Boolean = true,
    onPendingDecision: ((PendingDecision) -> Unit)? = null,
    onPendingDismiss: (() -> Unit)? = null,
) {
    when (item) {
        is TimelineItem.User -> UserMessageRow(item = item, modifier = modifier)

        is TimelineItem.Assistant -> AssistantMessageRow(item = item, modifier = modifier)

        is TimelineItem.ActivityGroup -> ActivityGroupRow(
            item = item,
            expanded = expandedGroups.contains(item.key),
            onToggle = { onToggleGroup(item.key) },
            modifier = modifier,
        )

        is TimelineItem.SystemNotice -> SystemNoticeRow(item = item, modifier = modifier)

        is TimelineItem.CompactionMarker -> CompactionMarkerRow(item = item, modifier = modifier)

        is TimelineItem.TurnEndBanner -> TurnEndBannerRow(item = item, modifier = modifier)

        is TimelineItem.GapMarker -> GapMarkerRow(item = item, modifier = modifier)

        is TimelineItem.HistoryUnsafeMarker -> HistoryUnsafeMarkerRow(item = item, modifier = modifier)

        is TimelineItem.PendingAnchor -> PendingAnchorCard(
            item = item,
            content = pendingContent,
            phase = pendingPhase,
            modifier = modifier,
            submitting = pendingSubmitting,
            errorRes = pendingErrorRes,
            needsSecondConfirm = pendingNeedsSecondConfirm,
            sessionAvailable = pendingSessionAvailable,
            onDecision = onPendingDecision,
            onDismiss = onPendingDismiss,
        )

        is TimelineItem.UnsupportedCount -> UnsupportedCountFooter(item = item, modifier = modifier)
    }
}
