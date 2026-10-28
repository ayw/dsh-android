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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.theme.DshTone
import ai.deepseek.dsh.android.designsystem.theme.LocalDshShapes
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.LocalStatusPalette
import ai.deepseek.dsh.android.designsystem.theme.toneColors
import ai.deepseek.dsh.android.model.PendingCounts

/**
 * 通用小胶囊。文案由调用方给出（特性模块自己 `stringResource(...)`）；
 * 本模块的组件一律不把字面量塞进来（G14）。
 *
 * @param tone null 表示“无状态语义”，只用中性容器色。
 */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    tone: DshTone? = null,
    maxLines: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    val shapes = LocalDshShapes.current
    val type = LocalDshType.current
    val palette = LocalStatusPalette.current
    val container = if (tone == null) MaterialTheme.colorScheme.surfaceVariant else toneColors(tone).container
    val onContainer = if (tone == null) palette.neutral.onContainer else toneColors(tone).onContainer
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 22.dp)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        shape = shapes.chip,
        color = container,
        contentColor = onContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = type.label,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 待处理计数胶囊（G07 / D13 / 不变量 10）。
 *
 * 文案全部由本组件从 `:core:designsystem` 的资源里取，特性模块只需传 [PendingCounts]，
 * 拿不到字符串资源也就无法把“未知”错写成 0：
 * - `badge == null` 或 `completeness == Unknown` → `pending_badge_unknown`（“待处理数未知”），**绝不显示 0**；
 * - 已知为 0 → `pending_badge_zero`，且默认不渲染（避免用绿色 0 冒充空收件箱），需要显式 0 就传 [hideWhenZero] = false；
 * - 已知 > 0 → `R.plurals.pending_badge_count`；
 * - `dismissed` 不计入 badge，单独用 `R.plurals.pending_dismissed_count` 呈现
 *   （计划 §8.2：手动划掉不减 `resolvedAt IS NULL` 集合），[showDismissed] = false 可隐藏。
 */
@Composable
fun PendingCountBadge(
    counts: PendingCounts,
    modifier: Modifier = Modifier,
    hideWhenZero: Boolean = true,
    showDismissed: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val unknown = counts.showsUnknown
    val badge = counts.badge
    val isZero = !unknown && badge == 0
    val renderCount = unknown || !(isZero && hideWhenZero)

    val unknownLabel = stringResource(R.string.pending_badge_unknown)
    val zeroLabel = stringResource(R.string.pending_badge_zero)
    val countLabel = badge?.let { pluralStringResource(R.plurals.pending_badge_count, it, it) }
    val dismissedLabel = if (showDismissed && counts.dismissed > 0) {
        pluralStringResource(R.plurals.pending_dismissed_count, counts.dismissed, counts.dismissed)
    } else {
        null
    }

    if (!renderCount && dismissedLabel == null) return

    val label = when {
        unknown -> unknownLabel
        isZero -> zeroLabel
        else -> countLabel ?: unknownLabel
    }
    val tone = when {
        unknown -> DshTone.Neutral
        isZero -> DshTone.Neutral
        else -> DshTone.Warning
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (renderCount) {
            Badge(text = label, tone = tone, onClick = onClick)
        }
        if (dismissedLabel != null) {
            Badge(text = dismissedLabel, tone = DshTone.Neutral)
        }
    }
}
