package ai.deepseek.dsh.android.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.DshTone
import ai.deepseek.dsh.android.designsystem.theme.LocalDshShapes
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.LocalStatusPalette
import ai.deepseek.dsh.android.designsystem.theme.statusFaint
import ai.deepseek.dsh.android.designsystem.theme.toneColors

/**
 * 五种视觉强度。turn-end 原因、连接横幅、诚实降级提示都归到这五档，
 * 特性模块不需要也不应该自造第六档。
 *
 * - [Quiet]：不画容器，只有一行次要文本（turn completed）。
 * - [Neutral]：中性容器 + 竖条（aborted / interrupted / forked、seq gap）。
 * - [Warning]：警告容器（blocked）。
 * - [Critical]：错误横幅，标题加粗并带操作位（failure）。
 * - [Info]：提示条，不抢注意力（max-tokens、上下文已压缩、“后台同步不是实时”）。
 */
enum class NoticeEmphasis {
    Quiet,
    Neutral,
    Warning,
    Critical,
    Info,
}

/** 强度到语义色调的映射；[NoticeEmphasis.Quiet] 不承载状态。 */
fun NoticeEmphasis.tone(): DshTone? = when (this) {
    NoticeEmphasis.Quiet -> null
    NoticeEmphasis.Neutral -> DshTone.Neutral
    NoticeEmphasis.Warning -> DshTone.Warning
    NoticeEmphasis.Critical -> DshTone.Negative
    NoticeEmphasis.Info -> DshTone.Info
}

/**
 * 一切“条 / 标记 / 说明”的唯一实现。
 *
 * 文本全部由调用方以资源字符串传入；本组件不接受异常对象，也不拼接任何字面量（G14）。
 *
 * @param title 主句
 * @param detail 次要说明；诚实降级文案放这里
 * @param glyph 字形。有标题文本时它只作装饰，因此不要求 contentDescription
 * @param trailing 操作区（按钮等），由调用方提供
 * @param body 内容区（服务端摘要、事件原文等数据）
 */
@Composable
fun NoticeBlock(
    title: String,
    emphasis: NoticeEmphasis,
    modifier: Modifier = Modifier,
    detail: String? = null,
    glyph: DshGlyph? = null,
    trailing: (@Composable () -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val type = LocalDshType.current
    val tone = emphasis.tone()
    val palette = LocalStatusPalette.current
    val barColor = if (tone == null) palette.faint else toneColors(tone).accent

    if (emphasis == NoticeEmphasis.Quiet) {
        Row(
            modifier = modifier.padding(vertical = DshSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glyph != null) {
                DshGlyphIcon(glyph = glyph, contentDescription = null, tint = statusFaint(), size = 14.dp)
            }
            Text(text = title, style = type.meta, color = statusFaint())
            if (detail != null) {
                Text(
                    text = detail,
                    modifier = Modifier.weight(1f, fill = false),
                    style = type.meta,
                    color = statusFaint(),
                )
            }
            if (trailing != null) trailing()
        }
        if (body != null) body()
        return
    }

    val colors = toneColors(tone ?: DshTone.Neutral)
    val shapes = LocalDshShapes.current
    val filled = emphasis == NoticeEmphasis.Critical || emphasis == NoticeEmphasis.Warning

    Surface(
        modifier = modifier,
        shape = shapes.banner,
        color = if (filled) colors.container else MaterialTheme.colorScheme.surface,
        contentColor = if (filled) colors.onContainer else MaterialTheme.colorScheme.onSurface,
        border = if (filled) null else BorderStroke(DshDimens.Stroke, colors.accent),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(DshDimens.AccentBar)
                    .fillMaxHeight()
                    .background(barColor),
            )
            Row(
                modifier = Modifier.padding(DshSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                if (glyph != null) {
                    DshGlyphIcon(
                        glyph = glyph,
                        contentDescription = null,
                        tint = if (filled) colors.accent else colors.onContainer,
                        size = 18.dp,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
                ) {
                    Text(
                        text = title,
                        style = if (emphasis == NoticeEmphasis.Critical) type.listTitle else type.label,
                        color = if (filled) colors.onContainer else MaterialTheme.colorScheme.onSurface,
                    )
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = type.meta,
                            color = if (filled) colors.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (body != null) body()
                }
                if (trailing != null) trailing()
            }
        }
    }
}
