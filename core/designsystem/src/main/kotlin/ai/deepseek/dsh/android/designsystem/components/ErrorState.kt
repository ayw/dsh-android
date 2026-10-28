package ai.deepseek.dsh.android.designsystem.components

import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.dshContentWidth

/**
 * 错误态。**只接受字符串资源 id**：异常 message、HTTP body、原始 URL 一律不许传进来
 * （G09.2 脱敏在 UI 侧的落点）。要展示服务端结构化摘要就放 [detail]，
 * 内容必须是已经脱敏的短标签（例如 error kind），不是异常正文。
 *
 * @param reasonRes 例如 `R.string.errors_network_unreachable`
 * @param reasonArgs 占位符参数；数量必须与两个 locale 文件里的占位符一致（StringParityTest 校验）
 * @param onRetry 为 null 时不渲染重试，例如 STALE 不重试的分支（D13）
 */
@Composable
fun ErrorState(
    @StringRes reasonRes: Int,
    modifier: Modifier = Modifier,
    reasonArgs: List<Any> = emptyList(),
    detail: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val type = LocalDshType.current
    val title = if (reasonArgs.isEmpty()) {
        stringResource(reasonRes)
    } else {
        stringResource(reasonRes, *reasonArgs.toTypedArray())
    }
    val retryLabel = stringResource(R.string.errors_retry)
    val action: (@Composable () -> Unit)? = if (onRetry == null) null else {
        @Composable {
            TextButton(onClick = onRetry) {
                Text(text = retryLabel, style = type.label)
            }
        }
    }

    NoticeBlock(
        title = title,
        emphasis = NoticeEmphasis.Critical,
        modifier = modifier.dshContentWidth(),
        glyph = DshGlyph.Cross,
        detail = detail,
        trailing = action,
    )
}

/** 行内提示条：诚实降级 / 非实时 / 历史不完整 / 可见历史不等于当前上下文。 */
@Composable
fun InlineNotice(
    title: String,
    emphasis: NoticeEmphasis,
    modifier: Modifier = Modifier,
    detail: String? = null,
    body: (@Composable () -> Unit)? = null,
) {
    NoticeBlock(
        title = title,
        emphasis = emphasis,
        modifier = modifier,
        detail = detail,
        body = body,
        glyph = glyphFor(emphasis),
    )
}

/** 每种强度对应的字形，保持全站一致。 */
fun glyphFor(emphasis: NoticeEmphasis): DshGlyph = when (emphasis) {
    NoticeEmphasis.Quiet -> DshGlyph.Dash
    NoticeEmphasis.Neutral -> DshGlyph.Ring
    NoticeEmphasis.Warning -> DshGlyph.Exclaim
    NoticeEmphasis.Critical -> DshGlyph.Cross
    NoticeEmphasis.Info -> DshGlyph.Dot
}
