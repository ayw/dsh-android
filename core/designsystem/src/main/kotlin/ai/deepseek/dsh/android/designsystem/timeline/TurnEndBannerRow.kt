package ai.deepseek.dsh.android.designsystem.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.components.NoticeBlock
import ai.deepseek.dsh.android.designsystem.components.glyphFor
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.dshContentWidth
import ai.deepseek.dsh.android.model.TimelineItem
import ai.deepseek.dsh.android.model.TurnEndReason

/**
 * turn/end 横幅：五种视觉面（计划 §7.3，映射见 [turnEndEmphasis]）。
 *
 * [TurnEndReason.Failure] 的 `summary` 是投影器给出的数据，不是异常对象：
 * 它只出现在内容区，并明确标注“来自服务端、未本地化”，
 * 标题永远是本地化过的 `conversation_turn_end_failure`。
 */
@Composable
fun TurnEndBannerRow(
    item: TimelineItem.TurnEndBanner,
    modifier: Modifier = Modifier,
) {
    val reason = item.reason
    val emphasis = turnEndEmphasis(reason)
    val summary = (reason as? TurnEndReason.Failure)?.summary.orEmpty()
    val type = LocalDshType.current

    val body: (@Composable () -> Unit)? = if (summary.isEmpty()) {
        null
    } else {
        @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
                Text(
                    text = stringResource(R.string.errors_server_summary_note),
                    style = type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = summary,
                    style = type.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    NoticeBlock(
        title = turnEndTitle(reason),
        emphasis = emphasis,
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        glyph = glyphFor(emphasis),
        body = body,
    )
}
