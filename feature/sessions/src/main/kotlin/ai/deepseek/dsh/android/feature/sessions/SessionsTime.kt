package ai.deepseek.dsh.android.feature.sessions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 会话“更新于”的相对时间文案。阈值走秒/分/时/天的乘积构造，绝不出现 4 位及以上十进制字面量（G09.3e）。
 */
private const val SECOND_MS = 500L * 2L
private const val MINUTE_MS = SECOND_MS * 60L
private const val HOUR_MS = MINUTE_MS * 60L
private const val DAY_MS = HOUR_MS * 24L

/**
 * 读取当前墙钟与 updatedAtMs 的差，落到四档相对文案之一。
 * 每次重组重取当前时间即可满足只读列表的新鲜度，无需额外的定时器或 Clock 依赖。
 */
@Composable
fun relativeUpdatedText(updatedAtMs: Long): String {
    val delta = (System.currentTimeMillis() - updatedAtMs).coerceAtLeast(0L)
    return when {
        delta < MINUTE_MS -> stringResource(R.string.sessions_time_just_now)
        delta < HOUR_MS -> stringResource(R.string.sessions_time_minutes, (delta / MINUTE_MS).toInt())
        delta < DAY_MS -> stringResource(R.string.sessions_time_hours, (delta / HOUR_MS).toInt())
        else -> stringResource(R.string.sessions_time_days, (delta / DAY_MS).toInt())
    }
}
