package ai.deepseek.dsh.android.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 文字 token。只保留组件真正用到的五种：
 * [listTitle]（卡片标题）、[body]（正文气泡）、[meta]（时间/序号/次要说明）、
 * [mono]（工具参数与结果原文）、[label]（压缩标记等小节标签）。
 * 新增字号必须先有组件使用它。
 */
@Immutable
class DshTypography(
    val listTitle: TextStyle,
    val body: TextStyle,
    val meta: TextStyle,
    val mono: TextStyle,
    val label: TextStyle,
)

internal val DshTypographyDefaults = DshTypography(
    listTitle = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    body = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    meta = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    ),
    mono = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
    ),
    label = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
)

val LocalDshType = staticCompositionLocalOf { DshTypographyDefaults }
