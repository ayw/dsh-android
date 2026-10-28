package ai.deepseek.dsh.android.feature.settings

import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.ServerId

/**
 * 设置页单一状态。只承载可安全外泄的服务器事实与本机计数，不含 token/ticket/查询串。
 */
data class SettingsUiState(
    val servers: ServersUiState = ServersUiState.Loading,
    val backgroundSync: BackgroundSyncUiState = BackgroundSyncUiState.Loading,
    val diagnostics: DiagnosticsUiState = DiagnosticsUiState.Loading,
)

sealed interface ServersUiState {
    data object Loading : ServersUiState
    data object Empty : ServersUiState
    data object Unavailable : ServersUiState
    data class Content(val items: List<ServerUiItem>) : ServersUiState
}

data class ServerUiItem(
    val serverId: ServerId,
    val label: String,
    val displayBase: String,
    val authState: AuthState,
    val capabilities: List<ServerCapability>,
    val probe: ProbeUiState = ProbeUiState.Idle,
)

/** 允许展示的能力事实；未知或未启用的能力不会出现在列表里（G08）。 */
enum class ServerCapability {
    WebSocket,
    BackgroundSync,
    EpochTracking,
    Receipts,
    Files,
    ImagePrompt,
}

sealed interface ProbeUiState {
    data object Idle : ProbeUiState
    data object Running : ProbeUiState
    data object Success : ProbeUiState
    data class Failed(val error: DomainError) : ProbeUiState
}

sealed interface BackgroundSyncUiState {
    data object Loading : BackgroundSyncUiState
    data object Enabled : BackgroundSyncUiState
    data object Disabled : BackgroundSyncUiState
    data object Unavailable : BackgroundSyncUiState
}

sealed interface DiagnosticsUiState {
    data object Loading : DiagnosticsUiState
    data object Empty : DiagnosticsUiState
    data class Content(
        val degraded: Boolean,
        val cursors: List<DiagnosticCursor>,
        val laggingSessions: Int,
        val maxSeqLag: Long,
        val needsResyncCount: Int,
        val historyIncompleteSessions: Int,
        val historyUnsafeSessions: Int,
        val pendingCompleteness: PendingCompleteness,
        val baselineSweepSkipped: Boolean,
        val authState: AuthState,
        val lastPollWaitSupported: Boolean?,
        val retentionTrimmedCount: Int,
        val badJsonCount: Int,
        val wsDowngradeCount: Int,
        val identityRetiredPendingPurge: Int,
        val rosterAgeMs: Long?,
        val capabilities: List<ServerCapability>,
        val capabilitiesKnown: Boolean,
    ) : DiagnosticsUiState
}

data class DiagnosticCursor(
    val channel: ChannelKind,
    val sinceSeq: Long,
)
