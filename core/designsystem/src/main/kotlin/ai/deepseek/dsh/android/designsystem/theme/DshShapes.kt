package ai.deepseek.dsh.android.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 形状 token。四种，每种都有组件在用：
 * [bubble] 消息气泡、[card] 活动组/待处理卡片、[banner] turn 结束横幅与提示条、[chip] 状态胶囊。
 */
@Immutable
class DshShapes(
    val bubble: Shape,
    val card: Shape,
    val banner: Shape,
    val chip: Shape,
)

internal val DshShapeDefaults = DshShapes(
    bubble = RoundedCornerShape(16.dp),
    card = RoundedCornerShape(12.dp),
    banner = RoundedCornerShape(10.dp),
    chip = RoundedCornerShape(999.dp),
)

val LocalDshShapes = staticCompositionLocalOf { DshShapeDefaults }

/** 尺寸与间距 token：间距刻度 + 触控目标 + 宽屏内容栏。 */
object DshSpacing {
    val none = 0.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

object DshDimens {
    /** 任何纯图标控件的最小边长（无障碍硬性要求）。 */
    val TouchTarget = 48.dp

    /** 列表/卡片左右内边距。 */
    val Gutter = 16.dp

    /** 左侧状态竖条。 */
    val AccentBar = 3.dp

    /** 状态圆点。 */
    val Dot = 8.dp

    /** 描边宽度。 */
    val Stroke = 1.dp

    /** 骨架行的文字占位高度。 */
    val SkeletonLine = 12.dp

    /** 折叠区最大可视高度，超出内部滚动（工具参数/结果原文）。 */
    val CollapsedBlockHeight = 160.dp

    /** D15：手机优先，但宽屏不得铺满崩版。 */
    val ContentMaxWidth = 640.dp
}
