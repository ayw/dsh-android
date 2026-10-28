package ai.deepseek.dsh.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.deepseek.dsh.android.designsystem.components.Badge
import ai.deepseek.dsh.android.designsystem.components.DisclosureRow
import ai.deepseek.dsh.android.designsystem.components.EmptyState
import ai.deepseek.dsh.android.designsystem.components.ErrorState
import ai.deepseek.dsh.android.designsystem.components.InlineNotice
import ai.deepseek.dsh.android.designsystem.components.ListSkeleton
import ai.deepseek.dsh.android.designsystem.components.MetaLabel
import ai.deepseek.dsh.android.designsystem.components.NoticeBlock
import ai.deepseek.dsh.android.designsystem.components.NoticeEmphasis
import ai.deepseek.dsh.android.designsystem.components.SectionHeader
import ai.deepseek.dsh.android.designsystem.components.StatusChip
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.DshTheme
import ai.deepseek.dsh.android.designsystem.theme.DshTone
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.dshContentWidth
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.ServerId
import java.util.concurrent.TimeUnit

/** 设置路由：从 ViewModel 取唯一状态，版本号由调用方注入。 */
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    versionName: String,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DshTheme {
        SettingsScreen(
            uiState = uiState,
            versionName = versionName,
            onProbe = viewModel::probe,
            modifier = modifier,
        )
    }
}

/**
 * 设置屏的只读内容。服务器只显示标签、脱敏地址、认证状态与能力事实；
 * 诊断只渲染 [DiagnosticsUiState] 里的本机计数。
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    versionName: String,
    onProbe: (ServerId) -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureScreen {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .dshContentWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = DshSpacing.xl),
            ) {
                ServersSection(state = uiState.servers, onProbe = onProbe)
                BackgroundSyncSection(state = uiState.backgroundSync)
                DiagnosticsSection(state = uiState.diagnostics)
                AboutSection(versionName = versionName)
            }
        }
    }
}

@Composable
private fun ServersSection(
    state: ServersUiState,
    onProbe: (ServerId) -> Unit,
) {
    SectionHeader(title = stringResource(R.string.settings_section_servers))
    when (state) {
        ServersUiState.Loading -> ListSkeleton(rows = 2)
        ServersUiState.Empty -> EmptyState(
            titleRes = R.string.settings_servers_empty,
            subtitleRes = R.string.settings_servers_empty_detail,
        )
        ServersUiState.Unavailable -> ErrorState(reasonRes = R.string.settings_servers_unavailable)
        is ServersUiState.Content -> state.items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = DshDimens.Gutter))
            }
            ServerRow(item = item, onProbe = onProbe)
        }
    }
    AddServerRow()
}

@Composable
private fun ServerRow(
    item: ServerUiItem,
    onProbe: (ServerId) -> Unit,
) {
    val type = LocalDshType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
            ) {
                Text(
                    text = if (item.label.isBlank()) {
                        stringResource(R.string.settings_server_unnamed)
                    } else {
                        item.label
                    },
                    style = type.listTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (item.displayBase.isNotBlank()) {
                    MetaLabel(text = item.displayBase)
                }
            }
            StatusChip(
                label = stringResource(authLabelRes(item.authState)),
                tone = authTone(item.authState),
            )
        }
        if (item.capabilities.isNotEmpty()) {
            val separator = stringResource(R.string.settings_list_separator)
            val labels = item.capabilities.map { stringResource(capabilityLabelRes(it)) }
            Text(
                text = stringResource(R.string.settings_server_capabilities, labels.joinToString(separator)),
                style = type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = { onProbe(item.serverId) },
            enabled = item.probe != ProbeUiState.Running,
        ) {
            Text(
                text = stringResource(
                    if (item.probe == ProbeUiState.Running) R.string.settings_probe_running else R.string.settings_probe,
                ),
                style = type.label,
            )
        }
        when (val probe = item.probe) {
            ProbeUiState.Idle, ProbeUiState.Running -> Unit
            ProbeUiState.Success -> InlineNotice(
                title = stringResource(R.string.settings_probe_ok),
                emphasis = NoticeEmphasis.Info,
            )
            is ProbeUiState.Failed -> InlineNotice(
                title = stringResource(R.string.settings_probe_failed),
                emphasis = NoticeEmphasis.Warning,
                detail = stringResource(probeErrorRes(probe.error)),
            )
        }
    }
}

@Composable
private fun AddServerRow() {
    val type = LocalDshType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.settings_add_server),
                style = type.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetaLabel(text = stringResource(R.string.settings_add_server_unavailable))
        }
        Badge(text = stringResource(R.string.settings_badge_unavailable), tone = DshTone.Neutral)
    }
}

@Composable
private fun BackgroundSyncSection(state: BackgroundSyncUiState) {
    SectionHeader(title = stringResource(R.string.settings_section_background_sync))
    val type = LocalDshType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        when (state) {
            BackgroundSyncUiState.Loading -> MetaLabel(
                text = stringResource(R.string.settings_background_sync_loading),
            )
            BackgroundSyncUiState.Unavailable -> MetaLabel(
                text = stringResource(R.string.settings_background_sync_unavailable),
            )
            BackgroundSyncUiState.Enabled -> {
                BackgroundSyncHeadline(
                    titleRes = R.string.settings_background_sync_enabled,
                    chipRes = R.string.settings_background_sync_state_on,
                    chipTone = DshTone.Positive,
                )
                Text(
                    text = stringResource(R.string.settings_background_sync_enabled_detail),
                    style = type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BackgroundSyncUiState.Disabled -> {
                BackgroundSyncHeadline(
                    titleRes = R.string.settings_background_sync_disabled,
                    chipRes = R.string.settings_background_sync_state_off,
                    chipTone = DshTone.Neutral,
                )
                Text(
                    text = stringResource(R.string.settings_background_sync_disabled_detail),
                    style = type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state == BackgroundSyncUiState.Enabled || state == BackgroundSyncUiState.Disabled) {
            MetaLabel(text = stringResource(R.string.settings_background_sync_cadence_note))
        }
    }
}

@Composable
private fun BackgroundSyncHeadline(
    titleRes: Int,
    chipRes: Int,
    chipTone: DshTone,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleRes),
            style = LocalDshType.current.label,
            color = MaterialTheme.colorScheme.onSurface,
        )
        StatusChip(label = stringResource(chipRes), tone = chipTone)
    }
}

@Composable
private fun DiagnosticsSection(state: DiagnosticsUiState) {
    SectionHeader(title = stringResource(R.string.settings_section_diagnostics))
    var expanded by rememberSaveable { mutableStateOf(false) }
    DisclosureRow(
        title = stringResource(R.string.settings_diagnostics_title),
        summary = stringResource(R.string.settings_diagnostics_summary),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        InlineNotice(
            title = stringResource(R.string.settings_diagnostics_local_only),
            emphasis = NoticeEmphasis.Info,
        )
        when (state) {
            DiagnosticsUiState.Loading -> ListSkeleton(rows = 2)
            DiagnosticsUiState.Empty -> EmptyState(
                titleRes = R.string.settings_diagnostics_empty,
                subtitleRes = R.string.settings_diagnostics_empty_detail,
            )
            is DiagnosticsUiState.Content -> DiagnosticsBody(state)
        }
    }
}

@Composable
private fun DiagnosticsBody(state: DiagnosticsUiState.Content) {
    if (state.degraded) {
        NoticeBlock(
            title = stringResource(R.string.settings_diagnostics_degraded),
            emphasis = NoticeEmphasis.Warning,
        )
    }
    DiagnosticGroup(titleRes = R.string.settings_diagnostics_section_transport) {
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_cursors),
            value = state.cursors.size.toString(),
        )
        state.cursors.forEach { cursor ->
            DiagnosticRow(
                label = stringResource(
                    R.string.settings_diagnostics_cursor,
                    channelLabel(cursor.channel),
                    cursor.sinceSeq,
                ),
            )
        }
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_poll_wait),
            value = yesNoUnknown(state.lastPollWaitSupported),
        )
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_auth_state),
            value = stringResource(authLabelRes(state.authState)),
        )
    }
    DiagnosticGroup(titleRes = R.string.settings_diagnostics_section_history) {
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_lagging_sessions),
            value = state.laggingSessions.toString(),
        )
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_max_lag),
            value = state.maxSeqLag.toString(),
        )
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_needs_resync),
            value = state.needsResyncCount.toString(),
        )
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_history_incomplete),
            value = state.historyIncompleteSessions.toString(),
        )
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_history_unsafe),
            value = state.historyUnsafeSessions.toString(),
        )
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_retention_trimmed),
            value = state.retentionTrimmedCount.toString(),
        )
    }
    DiagnosticGroup(titleRes = R.string.settings_diagnostics_section_pending) {
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_pending_completeness),
            value = completenessLabel(state.pendingCompleteness),
        )
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_baseline_skipped),
            value = yesNo(state.baselineSweepSkipped),
        )
    }
    DiagnosticGroup(titleRes = R.string.settings_diagnostics_section_storage) {
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_bad_json),
            value = state.badJsonCount.toString(),
        )
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_ws_downgrade),
            value = state.wsDowngradeCount.toString(),
        )
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_retired_purge),
            value = state.identityRetiredPendingPurge.toString(),
        )
        DiagnosticRow(
            label = stringResource(R.string.settings_diagnostics_roster_age),
            value = rosterAgeText(state.rosterAgeMs),
        )
    }
    DiagnosticGroup(titleRes = R.string.settings_diagnostics_section_capabilities) {
        when {
            !state.capabilitiesKnown -> DiagnosticRow(
                label = stringResource(R.string.settings_diagnostics_capabilities_none),
            )
            state.capabilities.isEmpty() -> DiagnosticRow(
                label = stringResource(R.string.settings_diagnostics_capabilities_empty),
            )
            else -> {
                val separator = stringResource(R.string.settings_list_separator)
                val labels = state.capabilities.map { stringResource(capabilityLabelRes(it)) }
                DiagnosticRow(
                    label = stringResource(
                        R.string.settings_diagnostics_capabilities,
                        labels.joinToString(separator),
                    ),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticGroup(
    titleRes: Int,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
        Text(
            text = stringResource(titleRes),
            style = LocalDshType.current.label,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = LocalDshType.current.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (value != null) {
            Text(
                text = value,
                style = LocalDshType.current.meta,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AboutSection(versionName: String) {
    SectionHeader(title = stringResource(R.string.settings_section_about))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.settings_app_name),
            style = LocalDshType.current.listTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MetaLabel(text = stringResource(R.string.settings_about_version, versionName))
        Text(
            text = stringResource(R.string.settings_about_summary),
            style = LocalDshType.current.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun authLabelRes(state: AuthState): Int = when (state) {
    AuthState.Connected -> R.string.settings_auth_connected
    AuthState.ResourceForbidden -> R.string.settings_auth_forbidden
    AuthState.AuthExpired -> R.string.settings_auth_expired
    AuthState.Unreachable -> R.string.settings_auth_unreachable
    AuthState.Unknown -> R.string.settings_auth_unknown
}

private fun authTone(state: AuthState): DshTone = when (state) {
    AuthState.Connected -> DshTone.Positive
    AuthState.ResourceForbidden -> DshTone.Warning
    AuthState.AuthExpired -> DshTone.Negative
    AuthState.Unreachable -> DshTone.Warning
    AuthState.Unknown -> DshTone.Neutral
}

private fun capabilityLabelRes(capability: ServerCapability): Int = when (capability) {
    ServerCapability.WebSocket -> R.string.settings_cap_websocket
    ServerCapability.BackgroundSync -> R.string.settings_cap_background_sync
    ServerCapability.EpochTracking -> R.string.settings_cap_epoch
    ServerCapability.Receipts -> R.string.settings_cap_receipts
    ServerCapability.Files -> R.string.settings_cap_files
    ServerCapability.ImagePrompt -> R.string.settings_cap_image_prompt
}

private fun probeErrorRes(error: DomainError): Int = when (error) {
    DomainError.AuthExpired -> R.string.settings_probe_failed_auth
    is DomainError.Forbidden -> R.string.settings_probe_failed_forbidden
    DomainError.Unreachable -> R.string.settings_probe_failed_unreachable
    DomainError.Timeout -> R.string.settings_probe_failed_timeout
    DomainError.HistoryIncomplete -> R.string.settings_probe_failed_generic
    DomainError.Conflict -> R.string.settings_probe_failed_generic
    is DomainError.Rejected -> R.string.settings_probe_failed_rejected
    is DomainError.UnavailableCapability -> R.string.settings_probe_failed_generic
}

@Composable
private fun channelLabel(channel: ChannelKind): String = when (channel) {
    ChannelKind.Mux -> stringResource(R.string.settings_diagnostics_channel_mux)
    ChannelKind.Host -> stringResource(R.string.settings_diagnostics_channel_host)
    else -> stringResource(R.string.settings_diagnostics_channel_other)
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(
    if (value) R.string.settings_diagnostics_yes else R.string.settings_diagnostics_no,
)

@Composable
private fun yesNoUnknown(value: Boolean?): String = when (value) {
    true -> stringResource(R.string.settings_diagnostics_yes)
    false -> stringResource(R.string.settings_diagnostics_no)
    null -> stringResource(R.string.settings_diagnostics_unknown)
}

@Composable
private fun completenessLabel(value: PendingCompleteness): String = when (value) {
    PendingCompleteness.Complete -> stringResource(R.string.settings_diagnostics_completeness_complete)
    PendingCompleteness.Stale -> stringResource(R.string.settings_diagnostics_completeness_stale)
    PendingCompleteness.Unknown -> stringResource(R.string.settings_diagnostics_completeness_unknown)
    PendingCompleteness.CompleteByServer -> stringResource(R.string.settings_diagnostics_completeness_by_server)
}

@Composable
private fun rosterAgeText(ageMs: Long?): String {
    if (ageMs == null) return stringResource(R.string.settings_diagnostics_unknown)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ageMs)
    return when {
        minutes < 1 -> stringResource(R.string.settings_diagnostics_roster_age_just_now)
        minutes < TimeUnit.HOURS.toMinutes(1) -> stringResource(
            R.string.settings_diagnostics_roster_age_minutes,
            minutes,
        )
        else -> stringResource(
            R.string.settings_diagnostics_roster_age_hours,
            TimeUnit.MILLISECONDS.toHours(ageMs),
        )
    }
}
