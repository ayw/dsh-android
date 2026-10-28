package ai.deepseek.dsh.android.designsystem.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.components.Badge
import ai.deepseek.dsh.android.designsystem.components.DshGlyph
import ai.deepseek.dsh.android.designsystem.components.NoticeEmphasis
import ai.deepseek.dsh.android.designsystem.components.NoticeBlock
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.DshTone
import ai.deepseek.dsh.android.designsystem.theme.LocalDshShapes
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.dshContentWidth
import ai.deepseek.dsh.android.designsystem.theme.statusFaint
import ai.deepseek.dsh.android.model.TimelineItem

/**
 * 用户消息行。正文只来自投影器的 `text`（人类发起的 `user/message`），
 * 注入消息由投影器归到 [TimelineItem.SystemNotice]，本组件不判断来源。
 */
@Composable
fun UserMessageRow(
    item: TimelineItem.User,
    modifier: Modifier = Modifier,
) {
    val type = LocalDshType.current
    Column(
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.conversation_role_user),
                style = type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = formatEventTime(item.wallClockMs), style = type.meta, color = statusFaint())
        }
        MessageBubble(text = item.text, alignEnd = true)
    }
}

/**
 * 助手消息行。正文唯一来源是 `assistant/message`（G05）；
 * `interrupted` 显示“已中断”标记但保留正文（F11）；
 * 空正文不生成空气泡，只留 usage / 上限提示（计划 §7.1）。
 *
 * reasoning 不在这里出现：它由 [ActivityGroupRow] 作为叠加层渲染（F12）。
 */
@Composable
fun AssistantMessageRow(
    item: TimelineItem.Assistant,
    modifier: Modifier = Modifier,
) {
    val type = LocalDshType.current
    val usage = item.usage
    val modelId = item.modelId

    Column(
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.conversation_role_assistant),
                style = type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.interrupted) {
                Badge(
                    text = stringResource(R.string.conversation_interrupted),
                    tone = DshTone.Neutral,
                )
            }
        }

        if (item.text.isNotEmpty()) {
            MessageBubble(text = item.text, alignEnd = false)
        } else {
            Text(
                text = stringResource(R.string.conversation_empty_assistant),
                style = type.meta,
                color = statusFaint(),
            )
        }

        if (modelId != null || usage != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm)) {
                if (modelId != null) {
                    Text(
                        text = stringResource(R.string.conversation_model_label, modelId),
                        style = type.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (usage != null) {
                    val unknown = stringResource(R.string.states_unknown)
                    Text(
                        text = stringResource(
                            R.string.conversation_usage_in_out,
                            usage.inputTokens?.let { formatNumber(it) } ?: unknown,
                            usage.outputTokens?.let { formatNumber(it) } ?: unknown,
                        ),
                        style = type.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val finishReason = item.finishReason
        if (finishReason != null) {
            Text(
                text = stringResource(R.string.conversation_finish_reason, finishReason),
                style = type.meta,
                color = statusFaint(),
            )
        }
    }
}

/**
 * 系统 / 开发者通知行。`developer = true` 时明确标注来源，
 * 不与助手气泡混同（正文仍是数据，不做本地化）。
 */
@Composable
fun SystemNoticeRow(
    item: TimelineItem.SystemNotice,
    modifier: Modifier = Modifier,
) {
    val label = if (item.developer) {
        stringResource(R.string.conversation_role_developer)
    } else {
        stringResource(R.string.conversation_role_system)
    }
    NoticeBlock(
        title = label,
        emphasis = if (item.developer) NoticeEmphasis.Info else NoticeEmphasis.Quiet,
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        detail = item.text,
        glyph = DshGlyph.Ring,
    )
}

/**
 * 气泡本体：用户 = primaryContainer，助手 = surfaceVariant。
 * 两者靠角色标签区分，颜色只作层级提示（无障碍：颜色不承载唯一语义）。
 */
@Composable
private fun MessageBubble(
    text: String,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val shapes = LocalDshShapes.current
    val type = LocalDshType.current
    Surface(
        modifier = modifier
            .widthIn(max = DshDimens.ContentMaxWidth)
            .fillMaxWidth(if (alignEnd) 0.92f else 0.94f),
        shape = shapes.bubble,
        color = if (alignEnd) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (alignEnd) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = DshSpacing.md, vertical = DshSpacing.sm),
            style = type.body,
            overflow = TextOverflow.Visible,
        )
    }
}
