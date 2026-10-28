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
import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.ConnectionPhase

/**
 * 连接横幅（G10：诊断状态必须在 UI 可见）。
 *
 * 由 [ConnectionPhase] 与 [AuthState] 驱动，而不是由临时字符串驱动；
 * 文案必须诚实表达“后台同步不是实时推送”“审批可能等下一次同步”，
 * 不得暗示分钟级实时（计划 §10 已知限制、TUNING 末段）。
 *
 * @param backgroundSyncSupported 网关 `eventPolling` capability；0 时换成诚实降级文案（G08）
 * @param pollWaitSupported 最近一次响应的 `waitSupported`；false 时额外说明延迟更高（F3）
 * @param onReconnect 认证过期时“重新配对”的入口；为 null 则不显示动作
 */
@Composable
fun ConnectivityBanner(
    phase: ConnectionPhase,
    authState: AuthState,
    modifier: Modifier = Modifier,
    backgroundSyncSupported: Boolean = true,
    pollWaitSupported: Boolean? = null,
    onReconnect: (() -> Unit)? = null,
) {
    val type = LocalDshType.current
    val spec = bannerSpecOf(phase, authState, backgroundSyncSupported)
    val actionLabel = stringResource(R.string.states_repair_action)
    val action: (@Composable () -> Unit)? = if (spec.actionable && onReconnect != null) {
        @Composable {
            TextButton(onClick = onReconnect) {
                Text(text = actionLabel, style = type.label)
            }
        }
    } else {
        null
    }
    val waitDetail = if (pollWaitSupported == false) {
        stringResource(R.string.states_poll_wait_unsupported_detail)
    } else {
        null
    }

    NoticeBlock(
        title = stringResource(spec.titleRes),
        emphasis = spec.emphasis,
        modifier = modifier.dshContentWidth(),
        detail = stringResourceOr(spec.detailRes) ?: waitDetail,
        glyph = glyphFor(spec.emphasis),
        trailing = action,
    )
}

private data class BannerSpec(
    val emphasis: NoticeEmphasis,
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int?,
    val actionable: Boolean = false,
)

@StringRes
private fun bannerTitleOf(phase: ConnectionPhase): Int = when (phase) {
    ConnectionPhase.Idle -> R.string.states_idle
    ConnectionPhase.ForegroundPending -> R.string.states_foreground_pending
    ConnectionPhase.Live -> R.string.states_live
    ConnectionPhase.Polling -> R.string.states_polling
    ConnectionPhase.Suspended -> R.string.states_suspended
    ConnectionPhase.OneShotRunning -> R.string.states_one_shot
    ConnectionPhase.Unreachable -> R.string.states_unreachable
    ConnectionPhase.AuthExpired -> R.string.errors_auth_expired
}

private fun bannerSpecOf(
    phase: ConnectionPhase,
    authState: AuthState,
    backgroundSyncSupported: Boolean,
): BannerSpec = when {
    // 认证过期优先于一切：只有观察到的 401/403 能进入这里（G18）。
    phase == ConnectionPhase.AuthExpired || authState == AuthState.AuthExpired ->
        BannerSpec(NoticeEmphasis.Critical, R.string.errors_auth_expired, R.string.errors_auth_expired_detail, actionable = true)

    authState == AuthState.ResourceForbidden ->
        BannerSpec(NoticeEmphasis.Warning, bannerTitleOf(phase), R.string.errors_forbidden_detail)

    !backgroundSyncSupported && phase != ConnectionPhase.Unreachable ->
        BannerSpec(NoticeEmphasis.Info, R.string.states_background_sync_unsupported_title, R.string.states_background_sync_unsupported_detail)

    else -> when (phase) {
        ConnectionPhase.Idle -> BannerSpec(NoticeEmphasis.Quiet, R.string.states_idle, null)
        ConnectionPhase.Live -> BannerSpec(NoticeEmphasis.Quiet, R.string.states_live, null)
        ConnectionPhase.ForegroundPending ->
            BannerSpec(NoticeEmphasis.Quiet, R.string.states_foreground_pending, null)
        ConnectionPhase.OneShotRunning ->
            BannerSpec(NoticeEmphasis.Info, R.string.states_one_shot, R.string.states_not_realtime_detail)
        ConnectionPhase.Polling ->
            BannerSpec(NoticeEmphasis.Info, R.string.states_polling, R.string.states_not_realtime_detail)
        ConnectionPhase.Suspended ->
            BannerSpec(NoticeEmphasis.Neutral, R.string.states_suspended, R.string.states_suspended_detail)
        ConnectionPhase.Unreachable ->
            BannerSpec(NoticeEmphasis.Warning, R.string.states_unreachable, R.string.states_unreachable_detail)
        ConnectionPhase.AuthExpired ->
            BannerSpec(NoticeEmphasis.Critical, R.string.errors_auth_expired, R.string.errors_auth_expired_detail, actionable = true)
    }
}

@Composable
private fun stringResourceOr(@StringRes res: Int?): String? =
    if (res == null) null else stringResource(res)
