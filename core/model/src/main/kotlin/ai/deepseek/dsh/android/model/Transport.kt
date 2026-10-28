package ai.deepseek.dsh.android.model

/** poll/WS 的一帧：payload 原文保留，投影前不重新编码。 */
data class TransportFrame(
    val channel: ChannelKind,
    val sessionId: SessionId,
    val seq: EventSeq,
    /**
     * 外层帧序号（D29）。mux 通道是双层信封：外层 `seq` 是传输游标推进的依据，内层
     * `payload.event.seq` 才是会话内序号（即上面的 [seq]）。host 通道只有一层，两者相等，
     * 于是默认成 [seq]。domain 只读 [seq]，`TransportCursor.sinceSeq` 只读本字段。
     */
    val transportSeq: EventSeq = seq,
    val type: String,
    val ignorable: Boolean,
    val payloadJson: String,
    val wallClockMs: Long,
    /** 传输层标记，来自网关缓冲重放；不属于 durable 事件模型。 */
    val replay: Boolean,
    /**
     * 外层信封的 `event.rpcId`。现代网关的 `/api/respond` 只认这个值（waterfall eventId）；
     * 它与 payload 里的身份（approvalId / callId）不是一回事，恢复时必须原样带回。
     */
    val rpcId: String? = null,
)

/** baseline envelope：只做快照种子，不进消息时间线。 */
/**
 * 基线信封里已经解码过的待处理事务引用。由 `:core:protocol` 从事件类型与 `data.id` 判定后填入：
 * `:core:domain` 不允许再从 `payloadJson` 文本里猜（子串匹配会把会话标题里出现的
 * "approval/asked" 误判成待处理家族，进而在 sweep 时错误地 tombstone 掉真实请求）。
 */
data class PendingRef(val kind: PendingKind, val requestId: RequestId)

data class BaselineEntry(
    val sessionId: SessionId,
    val lastSeq: EventSeq?,
    val payloadJson: String,
    val pendingRef: PendingRef? = null,
    val signalsMuxReload: Boolean = false,
)

data class EventBatch(
    val channel: ChannelKind,
    val epoch: String?,
    val since: Long,
    val latestSeq: Long,
    val truncated: Boolean,
    val reset: Boolean,
    val waitSupported: Boolean?,
    /** null 表示这次响应没有携带 baseline family，不得据此 sweep。 */
    val baseline: List<BaselineEntry>?,
    val frames: List<TransportFrame>,
)

data class PollRequest(
    val channel: ChannelKind,
    val since: Long,
    val epoch: String?,
    val waitMs: Long,
)

/** 服务端 cursor 缺失时，客户端分页使用的稳定排序键。 */
data class RosterEntry(
    val sessionId: SessionId,
    val title: String,
    val updatedAtMs: Long,
    val running: Boolean,
    val archived: Boolean,
    val workspaceId: String?,
    /** 每次 roster 同步写入的单调序号；分页不依赖会被后台同步改写的 updatedAt。 */
    val rosterRank: Long,
)

data class HistoryRequest(
    val sessionId: SessionId,
    /** exclusive；服务端会自行注入 throughSeq，客户端不发送。 */
    val beforeSeq: EventSeq?,
    val maxMessages: Int,
)

data class HistoryPage(
    val sessionId: SessionId,
    val frames: List<TransportFrame>,
    val hasMore: Boolean,
) {
    /** 网关响应没有 cut 字段，续跑前沿取本页最小 seq。 */
    val readFrontier: EventSeq? get() = frames.minByOrNull { it.seq.value }?.seq
}

data class PromptRequest(val sessionId: SessionId, val text: String, val attachments: List<PromptAttachment> = emptyList())

data class PromptAttachment(val fileName: String, val mediaType: String, val base64: String)

/** accepted 不等于 executed；operations=0 时没有回执，只能显示 unknown 且不重试。 */
data class WriteResult(
    val accepted: Boolean?,
    val receiptId: String?,
    val outcome: WriteOutcome,
) {
    enum class WriteOutcome { Accepted, Rejected, ReceiptPending, Unknown, Forbidden }
}

data class ResolveRequest(
    val requestId: RequestId,
    val kind: PendingKind,
    val valueJson: String,
    /** 帧信封里的 `rpcId`；现代网关 resolve 的唯一定位键，缺省时退回 [requestId]。 */
    val transportKey: String? = null,
)

data class BackendHealth(
    val ok: Boolean,
    val gatewayVersion: String,
    val upstreamOk: Boolean,
    val capabilities: BackendCapabilities,
)

/** 写操作只暴露幂等键，不暴露自动重试。 */
data class StreamHandle(val close: suspend () -> Unit)
