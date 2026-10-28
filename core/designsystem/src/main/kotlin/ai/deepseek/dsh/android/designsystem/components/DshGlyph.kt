package ai.deepseek.dsh.android.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 自绘字形。本模块不引入 material-icons 依赖（D03：镜像里可能没有的构件一律不引入），
 * 需要的图形画在这里，名称一律来自字符串资源。
 */
enum class DshGlyph {
    ChevronDown,
    ChevronRight,
    Dot,
    Ring,
    Exclaim,
    Cross,
    Check,
    Dash,
}

/**
 * 纯图标控件必须传非空 [contentDescription]（值取自 `stringResource(...)`）；
 * 只作装饰时传 null，此时不进入无障碍树。
 */
@Composable
fun DshGlyphIcon(
    glyph: DshGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 20.dp,
) {
    val content = LocalContentColor.current
    val resolved = if (tint == Color.Unspecified) content else tint
    val density = LocalDensity.current
    val stroke = with(density) { (size * 0.11f).coerceAtLeast(1.5.dp).toPx() }
    val semanticModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }

    Canvas(modifier = semanticModifier.size(size)) {
        // `size` 形参（Dp）在这里遮蔽了 DrawScope.size（Size），必须显式走接收者取画布像素尺寸。
        val w = this@Canvas.size.width
        val h = this@Canvas.size.height
        val cx = w / 2f
        val cy = h / 2f
        when (glyph) {
            DshGlyph.ChevronDown -> {
                drawLine(resolved, Offset(w * 0.28f, h * 0.40f), Offset(cx, h * 0.62f), stroke, StrokeCap.Round)
                drawLine(resolved, Offset(cx, h * 0.62f), Offset(w * 0.72f, h * 0.40f), stroke, StrokeCap.Round)
            }

            DshGlyph.ChevronRight -> {
                drawLine(resolved, Offset(w * 0.40f, h * 0.28f), Offset(w * 0.62f, cy), stroke, StrokeCap.Round)
                drawLine(resolved, Offset(w * 0.62f, cy), Offset(w * 0.40f, h * 0.72f), stroke, StrokeCap.Round)
            }

            DshGlyph.Dot -> drawCircle(resolved, radius = w * 0.22f, center = Offset(cx, cy))

            DshGlyph.Ring -> drawCircle(
                color = resolved,
                radius = w * 0.28f,
                center = Offset(cx, cy),
                style = Stroke(width = stroke),
            )

            DshGlyph.Exclaim -> {
                drawLine(resolved, Offset(cx, h * 0.22f), Offset(cx, h * 0.58f), stroke, StrokeCap.Round)
                drawCircle(resolved, radius = stroke * 0.95f, center = Offset(cx, h * 0.74f))
            }

            DshGlyph.Cross -> {
                drawLine(resolved, Offset(w * 0.30f, h * 0.30f), Offset(w * 0.70f, h * 0.70f), stroke, StrokeCap.Round)
                drawLine(resolved, Offset(w * 0.70f, h * 0.30f), Offset(w * 0.30f, h * 0.70f), stroke, StrokeCap.Round)
            }

            DshGlyph.Check -> {
                drawLine(resolved, Offset(w * 0.26f, h * 0.54f), Offset(w * 0.44f, h * 0.70f), stroke, StrokeCap.Round)
                drawLine(resolved, Offset(w * 0.44f, h * 0.70f), Offset(w * 0.76f, h * 0.32f), stroke, StrokeCap.Round)
            }

            DshGlyph.Dash -> drawLine(resolved, Offset(w * 0.28f, cy), Offset(w * 0.72f, cy), stroke, StrokeCap.Round)
        }
    }
}
