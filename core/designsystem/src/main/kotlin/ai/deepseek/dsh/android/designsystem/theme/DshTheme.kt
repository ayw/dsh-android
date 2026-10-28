package ai.deepseek.dsh.android.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 主题入口。特性模块的每个屏幕都包一层它。
 *
 * 动态取色只在 Android 12+ 生效，以下回退内置调色板（D15）；
 * 无论是否动态，**状态语义色**都由 [LocalStatusPalette] 提供，
 * 于是 `Incomplete` 永远落在 neutral，而不是被动态调色板的 error 染色。
 */
@Composable
fun DshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        DshDarkScheme
    } else {
        DshLightScheme
    }
    val palette = if (darkTheme) DarkStatus else LightStatus

    CompositionLocalProvider(
        LocalStatusPalette provides palette,
        LocalDshType provides DshTypographyDefaults,
        LocalDshShapes provides DshShapeDefaults,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** 一个 tone 的成套颜色（强调色 / 容器底色 / 容器上的前景色）。 */
@Composable
fun toneColors(tone: DshTone): DshToneColors = LocalStatusPalette.current.colorsOf(tone)

/** 中性描边：不承载状态语义。 */
@Composable
fun statusOutline(): Color = LocalStatusPalette.current.outline

/** 次要文本色：只承载层级，不承载状态。 */
@Composable
fun statusFaint(): Color = LocalStatusPalette.current.faint

/**
 * 单行内容宽度：手机铺满，宽屏最多 [DshDimens.ContentMaxWidth]（D15）。
 * 时间线每一行、空态与骨架屏都套它。
 */
fun Modifier.dshContentWidth(): Modifier = this
    .widthIn(max = DshDimens.ContentMaxWidth)
    .fillMaxWidth()
