package ai.deepseek.dsh.android.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 状态色调，只有五种，且必须是语义的：
 * `negative`=失败、`warning`=阻塞、`neutral`=不完整/中断/未知、`info`=进行中、`positive`=成功。
 *
 * **`ToolStatus.Incomplete` 与 `GroupStatus.Incomplete` 一律走 [DshTone.Neutral]**，
 * 绝不能画成红色失败（G05 投影语义在 UI 侧的落点）。
 */
enum class DshTone {
    Positive,
    Info,
    Neutral,
    Warning,
    Negative,
}

/**
 * 一个 tone 的成套颜色。组件永远通过 [LocalDshToneColors] 取，不直接引用 [Color] 常量，
 * 这样动态取色也不会改变状态语义。
 */
@Immutable
data class DshToneColors(
    val accent: Color,
    val container: Color,
    val onContainer: Color,
)

@Immutable
class DshStatusPalette(
    val positive: DshToneColors,
    val info: DshToneColors,
    val neutral: DshToneColors,
    val warning: DshToneColors,
    val negative: DshToneColors,
    /** 分隔线 / 幽灵文本，不属于任何状态。 */
    val faint: Color,
    val outline: Color,
) {
    fun colorsOf(tone: DshTone): DshToneColors = when (tone) {
        DshTone.Positive -> positive
        DshTone.Info -> info
        DshTone.Neutral -> neutral
        DshTone.Warning -> warning
        DshTone.Negative -> negative
    }
}

internal val LightStatus = DshStatusPalette(
    positive = DshToneColors(Color(0x1F6B45), Color(0xE1F2E8), Color(0x0E3B25)),
    info = DshToneColors(Color(0x2C5AA6), Color(0xE3EDFA), Color(0x123160)),
    neutral = DshToneColors(Color(0x5D5C56), Color(0xEDEBE4), Color(0x2B2B27)),
    warning = DshToneColors(Color(0x8A5A00), Color(0xFBEEDA), Color(0x4A2E00)),
    negative = DshToneColors(Color(0xA31F1F), Color(0xF9E3E1), Color(0x601010)),
    faint = Color(0x8A8880),
    outline = Color(0xD6D2C8),
)

internal val DarkStatus = DshStatusPalette(
    positive = DshToneColors(Color(0x7FD3A6), Color(0x1C3B2C), Color(0xD2F5E4)),
    info = DshToneColors(Color(0x9CC2F5), Color(0x1E3550), Color(0xDCEBFB)),
    neutral = DshToneColors(Color(0xB9B5AA), Color(0x33322D), Color(0xE6E2D8)),
    warning = DshToneColors(Color(0xF2C777), Color(0x453112), Color(0xF9E6C4)),
    negative = DshToneColors(Color(0xF1A09A), Color(0x4A1C1A), Color(0xF8DED9)),
    faint = Color(0x96928A),
    outline = Color(0x45443E),
)

val LocalStatusPalette = staticCompositionLocalOf { LightStatus }

/**
 * 内置调色板（Material 3 基线）。Android 12+ 由 [DshTheme] 决定是否用动态取色替换它；
 * 无论哪种，状态色都来自 [LocalStatusPalette]，不会被动态色改写成语义不明的红/绿。
 */
internal val DshLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0x2C5AA6),
    onPrimary = Color(0xFFFFFF),
    primaryContainer = Color(0xE3EDFA),
    onPrimaryContainer = Color(0x123160),
    secondary = Color(0x5D5C56),
    onSecondary = Color(0xFFFFFF),
    secondaryContainer = Color(0xEDEBE4),
    onSecondaryContainer = Color(0x2B2B27),
    tertiary = Color(0x1F6B45),
    onTertiary = Color(0xFFFFFF),
    tertiaryContainer = Color(0xE1F2E8),
    onTertiaryContainer = Color(0x0E3B25),
    background = Color(0xFFFCFAF5),
    onBackground = Color(0x1B1A17),
    surface = Color(0xFFFCFAF5),
    onSurface = Color(0x1B1A17),
    surfaceVariant = Color(0xF0EDE6),
    onSurfaceVariant = Color(0x45443E),
    outline = Color(0xD6D2C8),
    inverseSurface = Color(0x302F2A),
    inverseOnSurface = Color(0xF2EFE7),
    inversePrimary = Color(0x9CC2F5),
    error = Color(0xA31F1F),
    onError = Color(0xFFFFFF),
    errorContainer = Color(0xF9E3E1),
    onErrorContainer = Color(0x601010),
)

internal val DshDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0x9CC2F5),
    onPrimary = Color(0x123160),
    primaryContainer = Color(0x1E3550),
    onPrimaryContainer = Color(0xDCEBFB),
    secondary = Color(0xB9B5AA),
    onSecondary = Color(0x2B2B27),
    secondaryContainer = Color(0x33322D),
    onSecondaryContainer = Color(0xE6E2D8),
    tertiary = Color(0x7FD3A6),
    onTertiary = Color(0x0E3B25),
    tertiaryContainer = Color(0x1C3B2C),
    onTertiaryContainer = Color(0xD2F5E4),
    background = Color(0x141311),
    onBackground = Color(0xE6E2D8),
    surface = Color(0x141311),
    onSurface = Color(0xE6E2D8),
    surfaceVariant = Color(0x302F2A),
    onSurfaceVariant = Color(0xB9B5AA),
    outline = Color(0x45443E),
    inverseSurface = Color(0xE6E2D8),
    inverseOnSurface = Color(0x302F2A),
    inversePrimary = Color(0x2C5AA6),
    error = Color(0xF1A09A),
    onError = Color(0x4A1C1A),
    errorContainer = Color(0x4A1C1A),
    onErrorContainer = Color(0xF8DED9),
)
