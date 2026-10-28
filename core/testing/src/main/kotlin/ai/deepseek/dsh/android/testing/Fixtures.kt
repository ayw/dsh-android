package ai.deepseek.dsh.android.testing

import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.DiagnosticsSnapshot
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.model.WriteResult

/**
 * 测试夹具。identity digest 必须是 64 位小写 hex（`ConnectionIdentity` 的 init 会校验），
 * 这里用 seed 的 hex 左补齐，既稳定又能区分多个身份。
 */
object Fixtures {
    const val GATEWAY_VERSION = "0.0.0-test"

    val serverId = ServerId("srv-test")
    val mux = ChannelKind.Mux
    val host = ChannelKind.Host

    fun identity(seed: Int): ConnectionIdentity = ConnectionIdentity(seed.toString(16).padStart(64, '0'))

    fun sessionId(index: Int) = SessionId("session-$index")

    fun seq(value: Long) = EventSeq(value)

    /** 全 1 的能力表：背景同步、WS、回执、文件、图片都可用。 */
    fun fullCapabilities() = capabilities(
        wsTicket = 1,
        eventPolling = 1,
        eventEpoch = 1,
        operations = 1,
        workspaceFiles = 1,
        resumableUploads = 1,
        fileInbox = 1,
        imagePromptTransport = 1,
    )

    /** v0 真实网关最常见的一档：没有回执、没有 WS ticket，只有 long-poll。 */
    fun legacyGatewayCapabilities() = capabilities(
        wsTicket = 0,
        eventPolling = 1,
        eventEpoch = 0,
        operations = 0,
        workspaceFiles = 1,
        resumableUploads = 0,
        fileInbox = 0,
        imagePromptTransport = 0,
    )

    /** 连 long-poll 都没有：后台同步必须整体禁用（G08 的唯一硬要求）。 */
    fun noBackgroundSync() = capabilities(
        wsTicket = 1,
        eventPolling = 0,
        eventEpoch = 1,
        operations = 1,
        workspaceFiles = 0,
        resumableUploads = 0,
        fileInbox = 0,
        imagePromptTransport = 0,
    )

    fun capabilities(
        wsTicket: Int,
        eventPolling: Int,
        eventEpoch: Int,
        operations: Int,
        workspaceFiles: Int,
        resumableUploads: Int,
        fileInbox: Int,
        imagePromptTransport: Int,
    ): BackendCapabilities {
        val raw = linkedMapOf(
            "wsTicket" to wsTicket,
            "eventPolling" to eventPolling,
            "eventEpoch" to eventEpoch,
            "operations" to operations,
            "workspaceFiles" to workspaceFiles,
            "resumableUploads" to resumableUploads,
            "fileInbox" to fileInbox,
            "imagePromptTransport" to imagePromptTransport,
        )
        return BackendCapabilities(
            wsTicket = wsTicket,
            eventPolling = eventPolling,
            eventEpoch = eventEpoch,
            operations = operations,
            workspaceFiles = workspaceFiles,
            resumableUploads = resumableUploads,
            fileInbox = fileInbox,
            imagePromptTransport = imagePromptTransport,
            raw = raw,
        )
    }

    fun health(capabilities: BackendCapabilities = fullCapabilities()) = BackendHealth(
        ok = true,
        gatewayVersion = GATEWAY_VERSION,
        upstreamOk = true,
        capabilities = capabilities,
    )

    fun frame(
        type: String,
        seq: Long,
        sessionId: SessionId = sessionId(1),
        channel: ChannelKind = mux,
        payloadJson: String = "{}",
        ignorable: Boolean = false,
        replay: Boolean = false,
        wallClockMs: Long = 1_700_000_000_000L,
    ) = TransportFrame(
        channel = channel,
        sessionId = sessionId,
        seq = EventSeq(seq),
        type = type,
        ignorable = ignorable,
        payloadJson = payloadJson,
        wallClockMs = wallClockMs,
        replay = replay,
    )

    fun roster(index: Int, updatedAtMs: Long = 1_700_000_000_000L + index, running: Boolean = false) =
        RosterEntry(
            sessionId = sessionId(index),
            title = "会话 $index",
            updatedAtMs = updatedAtMs,
            running = running,
            archived = false,
            workspaceId = "ws-$index",
            rosterRank = index.toLong(),
        )

    fun pending(
        kind: PendingKind = PendingKind.Approval,
        requestId: String = "req-1",
        sessionId: SessionId = sessionId(1),
        status: PendingStatus = PendingStatus.Pending,
        completeness: PendingCompleteness = PendingCompleteness.Complete,
        serverId: ServerId = this.serverId,
    ) = PendingRequest(
        serverId = serverId,
        kind = kind,
        requestId = RequestId(requestId),
        sessionId = sessionId,
        status = status,
        completeness = completeness,
        payloadJson = "{}",
        firstSeenAtMs = 1_700_000_000_000L,
        createdAtMs = 1_700_000_000_000L,
    )

    fun accepted() = WriteResult(accepted = true, receiptId = "op-1", outcome = WriteResult.WriteOutcome.Accepted)

    fun rejected() = WriteResult(accepted = false, receiptId = null, outcome = WriteResult.WriteOutcome.Rejected)

    fun unknown() = WriteResult(accepted = null, receiptId = null, outcome = WriteResult.WriteOutcome.Unknown)

    /**
     * 零值诊断快照（测试替身用）。放在这里而不是 UI 测试里：G10 禁止 app/feature 层构造
     * `DiagnosticsSnapshot`，避免界面编造没人测过的数字。
     */
    fun emptyDiagnostics() = DiagnosticsSnapshot(
        transportCursorPerChannel = emptyMap(),
        contiguousSeqLag = emptyMap(),
        needsResyncCount = 0,
        historyIncompleteSessions = 0,
        historyUnsafeSessions = 0,
        pendingCompleteness = PendingCompleteness.Unknown,
        baselineSweepSkipped = false,
        authState = AuthState.Unknown,
        lastPollWaitSupported = null,
        retentionTrimmedCount = 0,
        badJsonCount = 0,
        wsDowngradeCount = 0,
        identityRetiredPendingPurge = 0,
        rosterAgeMs = null,
        capabilities = null,
    )
}
