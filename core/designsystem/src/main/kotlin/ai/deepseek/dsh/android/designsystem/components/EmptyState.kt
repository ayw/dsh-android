package ai.deepseek.dsh.android.designsystem.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.dshContentWidth

/**
 * 空态。标题必填，副标题与动作可选。
 * 文本一律走资源 id，特性模块不允许在这里传字面量（G14）。
 */
@Composable
fun EmptyState(
    @StringRes titleRes: Int,
    modifier: Modifier = Modifier,
    @StringRes subtitleRes: Int? = null,
    @StringRes actionLabelRes: Int? = null,
    onAction: (() -> Unit)? = null,
) {
    val type = LocalDshType.current
    Column(
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
    ) {
        Text(
            text = stringResource(titleRes),
            style = type.listTitle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (subtitleRes != null) {
            Text(
                text = stringResource(subtitleRes),
                style = type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (actionLabelRes != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(text = stringResource(actionLabelRes), style = type.label)
            }
        }
    }
}
