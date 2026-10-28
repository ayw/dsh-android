package ai.deepseek.dsh.android.model

/**
 * 诊断页与 degraded 状态的机器可读定义（验收 G10）。
 * UI 只能渲染这个类型，不得自造临时字符串。
 */
data class DiagnosticsSnapshot(
    val transportCursorPerChannel: Map<ChannelKind, TransportCursor>,
    val contiguousSeqLag: Map<SessionId, Long>,
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
    val capabilities: BackendCapabilities?,
) {
    val degraded: Boolean
        get() = needsResyncCount > 0 ||
            historyIncompleteSessions > 0 ||
            historyUnsafeSessions > 0 ||
            baselineSweepSkipped ||
            lastPollWaitSupported == false
}
