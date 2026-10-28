package ai.deepseek.dsh.android.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.deepseek.dsh.android.designsystem.theme.DshTone
import ai.deepseek.dsh.android.designsystem.theme.LocalDshShapes
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.toneColors

/**
 * 工具状态胶囊：字形只是装饰，状态名靠文本承载（颜色不作为唯一语义载体）。
 *
 * `ToolStatus.Incomplete` 的 tone 必须是 [DshTone.Neutral]，
 * 由 `toolStatusTone()` 保证，调用方不需要也不应该自己挑颜色。
 */
@Composable
fun StatusChip(
    label: String,
    tone: DshTone,
    modifier: Modifier = Modifier,
    glyph: DshGlyph? = null,
    onClick: (() -> Unit)? = null,
) {
    val shapes = LocalDshShapes.current
    val colors = toneColors(tone)
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 24.dp)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        shape = shapes.chip,
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glyph != null) {
                DshGlyphIcon(
                    glyph = glyph,
                    contentDescription = null,
                    tint = colors.accent,
                    size = 14.dp,
                )
            }
            Text(
                text = label,
                style = LocalDshType.current.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 与 [StatusChip] 同级的小标签，用于“模型”“必填”这类无状态说明。 */
@Composable
fun MetaLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = LocalDshType.current.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
