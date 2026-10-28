package ai.deepseek.dsh.android.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType

/**
 * 设置页展开行：标题 + 当前值 + 可选说明，展开后再渲染 [content]。
 *
 * 状态由调用方持有（计划 §11：展开状态用 `rememberSaveable`），本组件不藏状态。
 * `onClickLabel` 取模块字符串资源，纯图标箭头因此在无障碍里也有名字。
 *
 * @param value 右侧当前值文本，例如主题“跟随系统”
 * @param trailing 右侧控件插槽，例如 Material3 `Switch`
 */
@Composable
fun DisclosureRow(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val type = LocalDshType.current
    val actionLabel = stringResource(if (expanded) R.string.states_collapse else R.string.states_expand)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = DshDimens.TouchTarget)
                .clickable(onClickLabel = actionLabel, onClick = onToggle)
                .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) leading()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
                Text(
                    text = title,
                    style = type.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        style = type.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    style = type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) trailing()
            DshGlyphIcon(
                glyph = if (expanded) DshGlyph.ChevronDown else DshGlyph.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(
                    start = DshDimens.Gutter,
                    end = DshDimens.Gutter,
                    bottom = DshSpacing.md,
                ),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
            ) {
                content()
            }
        }
    }
}

/** 设置分组标题：只承载层级，不承载状态。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        modifier = modifier.padding(
            start = DshDimens.Gutter,
            end = DshDimens.Gutter,
            top = DshSpacing.lg,
            bottom = DshSpacing.xs,
        ),
        style = LocalDshType.current.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
