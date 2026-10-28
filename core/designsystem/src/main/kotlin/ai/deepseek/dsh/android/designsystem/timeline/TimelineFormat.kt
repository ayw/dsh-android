package ai.deepseek.dsh.android.designsystem.timeline

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.components.NoticeEmphasis
import ai.deepseek.dsh.android.designsystem.theme.DshTone
import ai.deepseek.dsh.android.model.AbortCause
import ai.deepseek.dsh.android.model.GroupStatus
import ai.deepseek.dsh.android.model.ToolStatus
import ai.deepseek.dsh.android.model.TurnEndReason
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * 模型状态 -> 视觉语义的唯一映射表。
 *
 * 这里**不做投影决策**（不分组、不合并、不判断是否≥2 项）：那些是 `:core:domain` 的职责。
 * 本文件只回答“这一条该长成什么样”。
 */

/**
 * `Incomplete` 走 [DshTone.Neutral]，**不是** [DshTone.Negative]（G05：
 * INCOMPLETE 不能看起来像红色失败）。`OrphanResult` 同样是中性：它是待合并，不是错误。
 */
fun toolStatusTone(status: ToolStatus): DshTone = when (status) {
    ToolStatus.Succeeded -> DshTone.Positive
    ToolStatus.Called -> DshTone.Info
    ToolStatus.Failed -> DshTone.Negative
    ToolStatus.Incomplete -> DshTone.Neutral
    ToolStatus.OrphanResult -> DshTone.Neutral
}

@Composable
fun toolStatusLabel(status: ToolStatus): String = stringResource(
    when (status) {
        ToolStatus.Called -> R.string.conversation_tool_called
        ToolStatus.Succeeded -> R.string.conversation_tool_succeeded
        ToolStatus.Failed -> R.string.conversation_tool_failed
        ToolStatus.Incomplete -> R.string.conversation_tool_incomplete
        ToolStatus.OrphanResult -> R.string.conversation_tool_orphan_result
    },
)

/** 组状态优先级 failed > running > incomplete > complete 由投影器决定，UI 只渲染结果。 */
fun groupStatusTone(status: GroupStatus): DshTone = when (status) {
    GroupStatus.Failed -> DshTone.Negative
    GroupStatus.Running -> DshTone.Info
    GroupStatus.Incomplete -> DshTone.Neutral
    GroupStatus.Complete -> DshTone.Positive
}

@Composable
fun groupStatusLabel(status: GroupStatus): String = stringResource(
    when (status) {
        GroupStatus.Failed -> R.string.conversation_group_failed
        GroupStatus.Running -> R.string.conversation_group_running
        GroupStatus.Incomplete -> R.string.conversation_group_incomplete
        GroupStatus.Complete -> R.string.conversation_group_complete
    },
)

/**
 * turn/end 原因 -> 五种视觉面（计划 §7.3）：
 * completed 安静；aborted/interrupted/forked 中性；blocked 警告；failure 错误横幅；
 * max-tokens 提示；未知 kind 走通用文案且不静默归并（G05/D08 同族规则）。
 */
fun turnEndEmphasis(reason: TurnEndReason): NoticeEmphasis = when (reason) {
    TurnEndReason.Completed -> NoticeEmphasis.Quiet
    is TurnEndReason.Aborted -> NoticeEmphasis.Neutral
    TurnEndReason.Interrupted -> NoticeEmphasis.Neutral
    TurnEndReason.Forked -> NoticeEmphasis.Neutral
    TurnEndReason.Blocked -> NoticeEmphasis.Warning
    is TurnEndReason.Failure -> NoticeEmphasis.Critical
    TurnEndReason.MaxTokens -> NoticeEmphasis.Info
    is TurnEndReason.Unknown -> NoticeEmphasis.Neutral
}

@Composable
fun turnEndTitle(reason: TurnEndReason): String = when (reason) {
    TurnEndReason.Completed -> stringResource(R.string.conversation_turn_end_completed)
    is TurnEndReason.Aborted -> stringResource(abortCauseRes(reason.cause))
    TurnEndReason.Interrupted -> stringResource(R.string.conversation_turn_end_interrupted)
    TurnEndReason.Forked -> stringResource(R.string.conversation_turn_end_forked)
    TurnEndReason.Blocked -> stringResource(R.string.conversation_turn_end_blocked)
    is TurnEndReason.Failure -> stringResource(R.string.conversation_turn_end_failure)
    TurnEndReason.MaxTokens -> stringResource(R.string.conversation_turn_end_max_tokens)
    is TurnEndReason.Unknown -> stringResource(R.string.conversation_turn_end_unknown, reason.kind)
}

private fun abortCauseRes(cause: AbortCause): Int = when (cause) {
    AbortCause.User -> R.string.conversation_turn_end_aborted_user
    AbortCause.Parent -> R.string.conversation_turn_end_aborted_parent
    AbortCause.Hook -> R.string.conversation_turn_end_aborted_hook
    AbortCause.Disposed -> R.string.conversation_turn_end_aborted_disposed
    AbortCause.Legacy -> R.string.conversation_turn_end_aborted_legacy
}

/** 墙钟时间按本机区域格式化：Room 只存 wall clock（不变量 9）。 */
@Composable
fun formatEventTime(wallClockMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(wallClockMs))

/** 计数一律带单位与单复数，不出现裸数字（G14 的诚实性要求）。 */
@Composable
fun formatTokenCount(count: Long): String =
    pluralStringResource(R.plurals.units_tokens, count.toInt(), formatNumber(count))

@Composable
fun formatNumber(value: Long): String = NumberFormat.getNumberInstance().format(value)

@Composable
fun formatNumber(value: Int): String = NumberFormat.getNumberInstance().format(value.toLong())

/** 序号显示：`#123`，用资源占位符避免字面量。 */
@Composable
fun formatSeq(value: Long): String = stringResource(R.string.units_seq, value)
