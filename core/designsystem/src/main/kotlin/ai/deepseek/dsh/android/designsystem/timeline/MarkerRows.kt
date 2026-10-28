package ai.deepseek.dsh.android.designsystem.timeline

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.components.DshGlyph
import ai.deepseek.dsh.android.designsystem.components.NoticeBlock
import ai.deepseek.dsh.android.designsystem.components.NoticeEmphasis
import ai.deepseek.dsh.android.designsystem.components.glyphFor
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.dshContentWidth
import ai.deepseek.dsh.android.designsystem.theme.statusFaint
import ai.deepseek.dsh.android.model.TimelineItem

/**
 * 压缩标记：折叠成一行“上下文已压缩”，**不伪装成助手气泡**（计划 §7.4）。
 * 同时说明可见历史可能包含已被压缩的旧消息。
 */
@Composable
fun CompactionMarkerRow(
    item: TimelineItem.CompactionMarker,
    modifier: Modifier = Modifier,
) {
    val type = LocalDshType.current
    NoticeBlock(
        title = stringResource(R.string.conversation_compaction_marker),
        emphasis = NoticeEmphasis.Info,
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        detail = stringResource(R.string.conversation_compaction_hint),
        glyph = DshGlyph.Dash,
        body = {
            Text(
                text = formatSeq(item.primarySeq.value),
                style = type.meta,
                color = statusFaint(),
            )
        },
    )
}

/** seq 空洞：turn/activity 的硬边界，中性显示并说明历史可能不完整。 */
@Composable
fun GapMarkerRow(
    item: TimelineItem.GapMarker,
    modifier: Modifier = Modifier,
) {
    NoticeBlock(
        title = stringResource(
            R.string.conversation_gap_range,
            formatSeq(item.expectedSeq.value),
            formatSeq(item.arrivedSeq.value),
        ),
        emphasis = NoticeEmphasis.Neutral,
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        detail = stringResource(R.string.states_history_may_be_incomplete),
        glyph = DshGlyph.Ring,
    )
}

/**
 * `historyUnsafe`：未知且不可忽略的事件（D08 第三档）。
 * 这是硬边界，用警告面而不是错误面——它不是操作失败，是历史不可重建。
 */
@Composable
fun HistoryUnsafeMarkerRow(
    item: TimelineItem.HistoryUnsafeMarker,
    modifier: Modifier = Modifier,
) {
    NoticeBlock(
        title = stringResource(R.string.conversation_history_unsafe_title),
        emphasis = NoticeEmphasis.Warning,
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        detail = stringResource(R.string.conversation_history_unsafe_detail, item.unknownType),
        glyph = DshGlyph.Exclaim,
    )
}

/** 未登记事件类型的聚合行：只计数，不猜测语义（D08 第二档）。 */
@Composable
fun UnsupportedCountFooter(
    item: TimelineItem.UnsupportedCount,
    modifier: Modifier = Modifier,
) {
    val label = pluralStringResource(
        R.plurals.conversation_unsupported_count,
        item.count,
        item.count,
    )
    NoticeBlock(
        title = label,
        emphasis = NoticeEmphasis.Quiet,
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        detail = stringResource(R.string.conversation_unsupported_hint),
        glyph = glyphFor(NoticeEmphasis.Quiet),
    )
}

/**
 * “历史可能不完整”独立说明条（G05/G10 里 HistoryIncomplete 的可见落点）。
 * 会话时间线顶部用一次即可，由特性模块决定挂哪里。
 */
@Composable
fun HistoryIncompleteNotice(
    modifier: Modifier = Modifier,
) {
    NoticeBlock(
        title = stringResource(R.string.states_history_may_be_incomplete),
        emphasis = NoticeEmphasis.Info,
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        detail = stringResource(R.string.conversation_history_incomplete_hint),
        glyph = DshGlyph.Ring,
    )
}

/** “可见历史 ≠ 当前上下文”说明条（计划 §7.4 要求 UI 必须说明）。 */
@Composable
fun VisibleHistoryNotice(
    modifier: Modifier = Modifier,
) {
    NoticeBlock(
        title = stringResource(R.string.states_visible_history_not_context),
        emphasis = NoticeEmphasis.Quiet,
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        glyph = DshGlyph.Dash,
    )
}
