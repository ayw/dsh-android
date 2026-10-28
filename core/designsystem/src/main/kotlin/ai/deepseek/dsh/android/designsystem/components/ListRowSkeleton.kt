package ai.deepseek.dsh.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.dshContentWidth

/**
 * 首帧骨架行（G12：会话页首屏 <=30 行，先画骨架再谈内容）。
 *
 * 不做呼吸动画：本模块只依赖 Compose BOM 里确定存在的构件，
 * `animation` 系列是传递依赖，不引入（D03）。静态占位同样是“正在加载”的诚实表达。
 */
@Composable
fun ListRowSkeleton(
    modifier: Modifier = Modifier,
    lines: Int = 2,
) {
    val loadingLabel = stringResource(R.string.states_loading)
    val block = MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(DshSpacing.sm)
    Row(
        modifier = modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.sm)
            .semantics { this.contentDescription = loadingLabel },
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(block, RoundedCornerShape(DshSpacing.md)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
        ) {
            SkeletonLine(widthFraction = 0.62f, color = block, shape = shape)
            if (lines > 1) {
                SkeletonLine(widthFraction = 0.4f, color = block, shape = shape)
            }
            if (lines > 2) {
                SkeletonLine(widthFraction = 0.28f, color = block, shape = shape)
            }
        }
    }
}

/** 整屏骨架：行数由特性模块按首屏预算传入。 */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 3,
    linesPerRow: Int = 2,
) {
    Column(modifier = modifier) {
        repeat(rows) {
            ListRowSkeleton(lines = linesPerRow)
        }
    }
}

@Composable
private fun SkeletonLine(
    widthFraction: Float,
    color: Color,
    shape: Shape,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(DshDimens.SkeletonLine)
            .background(color, shape),
    )
}
