package ai.deepseek.dsh.android.designsystem.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.components.Badge
import ai.deepseek.dsh.android.designsystem.components.ConnectivityBanner
import ai.deepseek.dsh.android.designsystem.components.DisclosureRow
import ai.deepseek.dsh.android.designsystem.components.DshGlyph
import ai.deepseek.dsh.android.designsystem.components.EmptyState
import ai.deepseek.dsh.android.designsystem.components.ErrorState
import ai.deepseek.dsh.android.designsystem.components.ListRowSkeleton
import ai.deepseek.dsh.android.designsystem.components.ListSkeleton
import ai.deepseek.dsh.android.designsystem.components.NoticeBlock
import ai.deepseek.dsh.android.designsystem.components.NoticeEmphasis
import ai.deepseek.dsh.android.designsystem.components.PendingCountBadge
import ai.deepseek.dsh.android.designsystem.components.StatusChip
import ai.deepseek.dsh.android.designsystem.components.glyphFor
import ai.deepseek.dsh.android.designsystem.timeline.formatNumber
import ai.deepseek.dsh.android.designsystem.timeline.formatSeq
import ai.deepseek.dsh.android.designsystem.timeline.groupStatusLabel
import ai.deepseek.dsh.android.designsystem.timeline.toolStatusLabel
import ai.deepseek.dsh.android.designsystem.timeline.toolStatusTone
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.DshTheme
import ai.deepseek.dsh.android.designsystem.theme.DshTone
import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.ConnectionPhase
import ai.deepseek.dsh.android.model.GroupStatus
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingCounts
import ai.deepseek.dsh.android.model.ToolStatus

/*
 * 组件预览。界面文案一律走 `:core:designsystem` 的字符串资源（G14）：
 * 预览里出现的少数英文字串都是**伪造的技术数据**（异常正文、工具类型名），
 * 它们走的是 `detail` / `label` 这类“原样显示的数据”通道，不是 UI 文案。
 */

@Composable
private fun Shell(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    DshTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = DshSpacing.md, horizontal = DshDimens.Gutter),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
            ) {
                content()
            }
        }
    }
}

@Preview(name = "Badges and chips", showBackground = true)
@Composable
private fun BadgePreview() = Shell {
    val statuses = listOf(
        ToolStatus.Called,
        ToolStatus.Succeeded,
        ToolStatus.Failed,
        ToolStatus.Incomplete,
        ToolStatus.OrphanResult,
    )
    statuses.forEach { status ->
        StatusChip(
            label = toolStatusLabel(status),
            tone = toolStatusTone(status),
            glyph = DshGlyph.Dot,
        )
    }
    Badge(text = groupStatusLabel(GroupStatus.Incomplete), tone = DshTone.Neutral)
    Badge(text = stringResource(R.string.conversation_group_open_turn))
    Badge(text = stringResource(R.string.states_partial))
}

@Preview(name = "Pending badge: unknown never becomes a fake zero", showBackground = true)
@Composable
private fun PendingCountBadgePreview() = Shell {
    PendingCountBadge(
        counts = PendingCounts(badge = null, dismissed = 2, completeness = PendingCompleteness.Unknown),
    )
    PendingCountBadge(
        counts = PendingCounts(badge = 3, dismissed = 1, completeness = PendingCompleteness.Complete),
    )
    PendingCountBadge(
        counts = PendingCounts(badge = 0, dismissed = 0, completeness = PendingCompleteness.Complete),
        hideWhenZero = false,
    )
    PendingCountBadge(
        counts = PendingCounts(badge = 4, dismissed = 0, completeness = PendingCompleteness.Stale),
        onClick = {},
    )
}

@Preview(name = "Five notice surfaces", showBackground = true, heightDp = 600)
@Composable
private fun NoticeSurfacePreview() = Shell {
    NoticeBlock(
        title = stringResource(R.string.conversation_turn_end_completed),
        emphasis = NoticeEmphasis.Quiet,
        detail = stringResource(R.string.states_visible_history_not_context),
        glyph = glyphFor(NoticeEmphasis.Quiet),
    )
    NoticeBlock(
        title = stringResource(R.string.conversation_gap_range, formatSeq(71), formatSeq(78)),
        emphasis = NoticeEmphasis.Neutral,
        detail = stringResource(R.string.states_history_may_be_incomplete),
        glyph = glyphFor(NoticeEmphasis.Neutral),
    )
    NoticeBlock(
        title = stringResource(R.string.conversation_turn_end_blocked),
        emphasis = NoticeEmphasis.Warning,
        detail = stringResource(R.string.pending_wait_next_sync),
        glyph = glyphFor(NoticeEmphasis.Warning),
    )
    NoticeBlock(
        title = stringResource(R.string.conversation_turn_end_failure),
        emphasis = NoticeEmphasis.Critical,
        detail = stringResource(R.string.errors_server_summary_note),
        glyph = glyphFor(NoticeEmphasis.Critical),
        trailing = {
            TextButton(onClick = {}) {
                Text(
                    text = stringResource(R.string.errors_retry),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        body = {
            // 服务端正文：原样显示，不本地化。
            StatusChip(label = "upstream reset while streaming", tone = DshTone.Neutral)
        },
    )
    NoticeBlock(
        title = stringResource(R.string.conversation_compaction_marker),
        emphasis = NoticeEmphasis.Info,
        detail = stringResource(R.string.conversation_compaction_hint),
        glyph = glyphFor(NoticeEmphasis.Info),
    )
}

@Preview(name = "Tone surfaces stay semantic under dynamic color", showBackground = true)
@Composable
private fun TonePreview() = Shell {
    listOf(
        Triple(R.string.conversation_tool_succeeded, DshTone.Positive, ToolStatus.Succeeded),
        Triple(R.string.conversation_tool_called, DshTone.Info, ToolStatus.Called),
        Triple(R.string.conversation_tool_incomplete, DshTone.Neutral, ToolStatus.Incomplete),
        Triple(R.string.conversation_turn_end_blocked, DshTone.Warning, ToolStatus.Failed),
        Triple(R.string.conversation_tool_failed, DshTone.Negative, ToolStatus.Failed),
    ).forEach { (res, tone, status) ->
        StatusChip(
            label = stringResource(res),
            tone = tone,
            glyph = if (status == ToolStatus.Incomplete) DshGlyph.Dash else DshGlyph.Dot,
        )
    }
}

@Preview(name = "Empty state", showBackground = true)
@Composable
private fun EmptyStatePreview() = Shell {
    EmptyState(
        titleRes = R.string.sessions_empty_title,
        subtitleRes = R.string.sessions_empty_subtitle,
        actionLabelRes = R.string.states_refresh_action,
        onAction = {},
    )
    EmptyState(titleRes = R.string.inbox_empty_title)
    EmptyState(
        titleRes = R.string.conversation_no_messages_title,
        subtitleRes = R.string.conversation_readonly_hint,
    )
}

@Preview(name = "Error state: the title is always a reason resource", showBackground = true)
@Composable
private fun ErrorStatePreview() = Shell {
    ErrorState(reasonRes = R.string.errors_generic, onRetry = {})
    ErrorState(reasonRes = R.string.errors_timeout, detail = "java.net.SocketTimeoutException")
    ErrorState(
        reasonRes = R.string.errors_attachment_too_large,
        reasonArgs = listOf(formatNumber(0x100000)),
    )
    ErrorState(reasonRes = R.string.errors_session_gone)
    ErrorState(reasonRes = R.string.errors_unknown_event_kind, reasonArgs = listOf("workspace/hint"))
}

@Preview(name = "Connectivity banner phases", showBackground = true, heightDp = 800)
@Composable
private fun ConnectivityBannerPreview() = Shell {
    ConnectivityBanner(phase = ConnectionPhase.Live, authState = AuthState.Connected)
    ConnectivityBanner(phase = ConnectionPhase.ForegroundPending, authState = AuthState.Connected)
    ConnectivityBanner(phase = ConnectionPhase.Polling, authState = AuthState.Connected)
    ConnectivityBanner(
        phase = ConnectionPhase.OneShotRunning,
        authState = AuthState.Connected,
        pollWaitSupported = false,
    )
    ConnectivityBanner(phase = ConnectionPhase.Suspended, authState = AuthState.Unknown)
    ConnectivityBanner(
        phase = ConnectionPhase.Unreachable,
        authState = AuthState.Unreachable,
        onReconnect = {},
    )
    ConnectivityBanner(
        phase = ConnectionPhase.AuthExpired,
        authState = AuthState.AuthExpired,
        onReconnect = {},
    )
    ConnectivityBanner(phase = ConnectionPhase.Idle, authState = AuthState.ResourceForbidden)
    ConnectivityBanner(
        phase = ConnectionPhase.Idle,
        authState = AuthState.Connected,
        backgroundSyncSupported = false,
    )
}

@Preview(name = "Disclosure row", showBackground = true)
@Composable
private fun DisclosureRowPreview() = Shell {
    var expanded by remember { mutableStateOf(true) }
    DisclosureRow(
        title = stringResource(R.string.settings_background_sync),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        summary = stringResource(R.string.settings_content_cap_note),
        value = formatNumber(0xEA60),
        leading = { StatusChip(label = stringResource(R.string.states_partial), tone = DshTone.Info) },
    ) {
        ErrorState(reasonRes = R.string.errors_generic)
    }
    DisclosureRow(
        title = stringResource(R.string.diagnostics_title),
        expanded = false,
        onToggle = {},
    ) {
        Badge(text = stringResource(R.string.diagnostics_nominal), tone = DshTone.Positive)
    }
}

@Preview(name = "Skeletons (first frame, G12)", showBackground = true)
@Composable
private fun SkeletonPreview() = Shell {
    ListSkeleton(rows = 3, linesPerRow = 2)
    ListRowSkeleton(lines = 3)
}

@Preview(name = "Dark components", showBackground = true, heightDp = 600)
@Composable
private fun DarkComponentsPreview() = Shell(darkTheme = true) {
    StatusChip(
        label = toolStatusLabel(ToolStatus.Incomplete),
        tone = toolStatusTone(ToolStatus.Incomplete),
    )
    PendingCountBadge(
        counts = PendingCounts(badge = null, dismissed = 0, completeness = PendingCompleteness.Unknown),
    )
    ConnectivityBanner(phase = ConnectionPhase.Unreachable, authState = AuthState.Unreachable)
    EmptyState(titleRes = R.string.pairing_title, subtitleRes = R.string.pairing_dedupe_note)
}
