package ai.deepseek.dsh.android.model

/** 只用于 poll 增量；reset 时清除，绝不与 session 事件序号混用。 */
data class TransportCursor(
    val identity: ConnectionIdentity,
    val channel: ChannelKind,
    val sinceSeq: Long,
    val epoch: String?,
)

/**
 * 会话持久位置。两列不能合并：快照尾部可以应用 900..950 而 contiguousSeq 仍是 100，
 * 合并成一个 watermark 会掩盖空洞。
 */
data class SessionPosition(
    val contiguousSeq: EventSeq?,
    val appliedMaxSeq: EventSeq?,
) {
    val nextExpected: EventSeq? get() = contiguousSeq?.let { EventSeq(it.value + 1) }
}

data class AppliedIsland(val startSeq: EventSeq, val endSeq: EventSeq)

enum class ResetTrigger { ServerResetFlag, EpochChanged, Truncated, LatestSeqBehindSince }

enum class ResyncReason { SeqGap, HistoryRepair, ManualImmediate }

data class NeedsResync(
    val sessionId: SessionId,
    val reason: ResyncReason,
    val expectedSeq: EventSeq,
    val attempts: Int,
    val degraded: Boolean,
)

enum class SyncOwnerRole { None, Foreground, Worker }

/** 通知外发箱；与 cursor 同事务写入，统一 drain，至少一次投递。 */
enum class OutboxState { New, Sent, Cancelled, Skipped }

/**
 * key-replace 投影族：这些族的最新状态由 `asOfSeq` 单调决定，不存在 append-only 路径。
 * 族名是领域概念，不是网关事件名字符串；把字符串留在 `:core:protocol` 的映射表里。
 */
enum class ProjectionFamily { Approval, Question, Queue, Jobs, Reasoning, Session }

enum class ConnectionPhase {
    Idle,
    ForegroundPending,
    Live,
    Polling,
    Suspended,
    OneShotRunning,
    Unreachable,
    AuthExpired,
}

enum class AuthState { Unknown, Connected, ResourceForbidden, AuthExpired, Unreachable }

/** 401 是 claim 不是 verdict：一次 episode 只探测一次凭据。 */
enum class AuthProbeVerdict { CredentialValidResourceForbidden, CredentialRejected, Indeterminate }

/** Worker 结果映射的输入。 */
enum class SyncAttemptResult { Completed, ForegroundTookOver, Busy, AuthExpired, Unreachable }

/** 网关 /health 发布的整数 capability 表；按独立能力协商，不用单一版本号分支。 */
data class BackendCapabilities(
    val wsTicket: Int,
    val eventPolling: Int,
    val eventEpoch: Int,
    val operations: Int,
    val workspaceFiles: Int,
    val resumableUploads: Int,
    val fileInbox: Int,
    val imagePromptTransport: Int,
    val raw: Map<String, Int>,
) {
    val backgroundSyncSupported: Boolean get() = eventPolling >= 1
    val websocketAllowed: Boolean get() = wsTicket >= 1
    val tracksEpoch: Boolean get() = eventEpoch >= 1
    val hasReceipts: Boolean get() = operations >= 1
    val imagePromptAllowed: Boolean get() = imagePromptTransport >= 1
    val filesAllowed: Boolean get() = workspaceFiles >= 1

    fun unknown(name: String): Boolean = !raw.containsKey(name)
}
