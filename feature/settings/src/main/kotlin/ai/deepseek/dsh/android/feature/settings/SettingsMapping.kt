package ai.deepseek.dsh.android.feature.settings

import ai.deepseek.dsh.android.domain.CapabilityGate
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.repository.ServerDescriptor
import ai.deepseek.dsh.android.domain.repository.capabilityAllowed
import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.DiagnosticsSnapshot
import ai.deepseek.dsh.android.model.PendingCompleteness

internal fun ServerDescriptor.toUiItem(tuning: SyncTuning, probe: ProbeUiState): ServerUiItem =
    ServerUiItem(
        serverId = serverId,
        label = label,
        displayBase = sanitizedBaseUrl(storedBase),
        authState = lastKnownAuthState,
        capabilities = capabilityFacts(capabilities, tuning),
        probe = probe,
    )

/** 能力事实走统一 gate：键缺失、值为 0 或本机调参禁用都视为不可用，UI 直接不渲染。 */
internal fun capabilityFacts(
    capabilities: BackendCapabilities?,
    tuning: SyncTuning,
): List<ServerCapability> {
    if (capabilities == null) return emptyList()
    val gates = CapabilityGate.gates(capabilities, tuning)
    return buildList {
        if (gates.websocketAllowed && capabilityAllowed(capabilities, "wsTicket")) {
            add(ServerCapability.WebSocket)
        }
        if (gates.backgroundSyncAllowed && capabilityAllowed(capabilities, "eventPolling")) {
            add(ServerCapability.BackgroundSync)
        }
        if (gates.epochTrackingAllowed && capabilityAllowed(capabilities, "eventEpoch")) {
            add(ServerCapability.EpochTracking)
        }
        if (gates.receiptsAllowed && capabilityAllowed(capabilities, "operations")) {
            add(ServerCapability.Receipts)
        }
        if (gates.filesAllowed && capabilityAllowed(capabilities, "workspaceFiles")) {
            add(ServerCapability.Files)
        }
        if (gates.imagePromptAllowed && capabilityAllowed(capabilities, "imagePromptTransport")) {
            add(ServerCapability.ImagePrompt)
        }
    }
}

/** 地址只保留 scheme + host[:port] + path：查询串、片段与 userinfo 都可能携带凭据。 */
internal fun sanitizedBaseUrl(raw: String): String {
    val noFragment = raw.trim().substringBefore('#')
    val noQuery = noFragment.substringBefore('?')
    val schemeEnd = noQuery.indexOf("://")
    if (schemeEnd < 0) {
        val pathStart = noQuery.indexOf('/').let { if (it >= 0) it else noQuery.length }
        val authority = noQuery.substring(0, pathStart)
        val at = authority.lastIndexOf('@')
        return if (at < 0) noQuery else noQuery.substring(at + 1)
    }
    val authorityStart = schemeEnd + 3
    val pathStart = noQuery.indexOf('/', authorityStart).let { if (it >= 0) it else noQuery.length }
    val authority = noQuery.substring(authorityStart, pathStart)
    val at = authority.lastIndexOf('@')
    if (at < 0) return noQuery
    return noQuery.substring(0, authorityStart) + authority.substring(at + 1) + noQuery.substring(pathStart)
}

internal fun DiagnosticsSnapshot.toDiagnosticsUiState(tuning: SyncTuning): DiagnosticsUiState {
    if (!hasLocalFacts()) return DiagnosticsUiState.Empty
    return DiagnosticsUiState.Content(
        degraded = degraded,
        cursors = transportCursorPerChannel.entries
            .sortedBy { it.key.value }
            .map { DiagnosticCursor(channel = it.key, sinceSeq = it.value.sinceSeq) },
        laggingSessions = contiguousSeqLag.size,
        maxSeqLag = contiguousSeqLag.values.maxOrNull() ?: 0L,
        needsResyncCount = needsResyncCount,
        historyIncompleteSessions = historyIncompleteSessions,
        historyUnsafeSessions = historyUnsafeSessions,
        pendingCompleteness = pendingCompleteness,
        baselineSweepSkipped = baselineSweepSkipped,
        authState = authState,
        lastPollWaitSupported = lastPollWaitSupported,
        retentionTrimmedCount = retentionTrimmedCount,
        badJsonCount = badJsonCount,
        wsDowngradeCount = wsDowngradeCount,
        identityRetiredPendingPurge = identityRetiredPendingPurge,
        rosterAgeMs = rosterAgeMs,
        capabilities = capabilityFacts(capabilities, tuning),
        capabilitiesKnown = capabilities != null,
    )
}

/** 全 0 + 全未知的 snapshot 与“还没有数据”不可区分，此时宁可显示空态也不伪造 0。 */
internal fun DiagnosticsSnapshot.hasLocalFacts(): Boolean =
    transportCursorPerChannel.isNotEmpty() ||
        contiguousSeqLag.isNotEmpty() ||
        needsResyncCount > 0 ||
        historyIncompleteSessions > 0 ||
        historyUnsafeSessions > 0 ||
        pendingCompleteness != PendingCompleteness.Unknown ||
        baselineSweepSkipped ||
        authState != AuthState.Unknown ||
        lastPollWaitSupported != null ||
        retentionTrimmedCount > 0 ||
        badJsonCount > 0 ||
        wsDowngradeCount > 0 ||
        identityRetiredPendingPurge > 0 ||
        rosterAgeMs != null ||
        capabilities != null
