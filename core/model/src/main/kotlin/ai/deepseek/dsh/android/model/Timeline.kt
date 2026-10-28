package ai.deepseek.dsh.android.model

enum class ToolStatus {
    /** 已调用，等待结果。 */
    Called,

    Succeeded,
    Failed,

    /** turn/step 结束仍无结果：中性展示，不显示为红色失败。 */
    Incomplete,

    /** 先看到 result、后补 call 时的合并前状态。 */
    OrphanResult,
    ;

    val isTerminal: Boolean get() = this == Succeeded || this == Failed
}

enum class GroupStatus { Failed, Running, Incomplete, Complete }

data class ToolEntry(
    val callId: CallId,
    val toolName: String,
    val argumentsJson: String?,
    val status: ToolStatus,
    val resultJson: String?,
    val callSeq: EventSeq?,
    val resultSeq: EventSeq?,
    /** 同一 call 收到不同 seq 的第二条 result：保留较早终态并在此记录冲突。 */
    val conflictingResultSeq: EventSeq? = null,
)

sealed interface ActivityEntry {
    data class Tool(val tool: ToolEntry) : ActivityEntry
    data class Reasoning(val text: String) : ActivityEntry

    /** assistant/attempt 折叠出的诊断行。 */
    data class Attempt(val summary: String, val errorKind: String?, val interrupted: Boolean) : ActivityEntry

    data class UnsupportedEvent(val type: String) : ActivityEntry
}

sealed interface TimelineItem {
    val key: String
    val primarySeq: EventSeq

    data class User(
        override val key: String,
        override val primarySeq: EventSeq,
        val text: String,
        val wallClockMs: Long,
    ) : TimelineItem

    data class Assistant(
        override val key: String,
        override val primarySeq: EventSeq,
        val text: String,
        val modelId: String?,
        val usage: UsageInfo?,
        val interrupted: Boolean,
        val finishReason: String?,
    ) : TimelineItem

    data class ActivityGroup(
        override val key: String,
        override val primarySeq: EventSeq,
        val entries: List<ActivityEntry>,
        val status: GroupStatus,
        val turnOpen: Boolean,
    ) : TimelineItem

    data class SystemNotice(
        override val key: String,
        override val primarySeq: EventSeq,
        val text: String,
        val developer: Boolean,
    ) : TimelineItem

    data class CompactionMarker(
        override val key: String,
        override val primarySeq: EventSeq,
        val compactionId: String,
    ) : TimelineItem

    data class TurnEndBanner(
        override val key: String,
        override val primarySeq: EventSeq,
        val reason: TurnEndReason,
    ) : TimelineItem

    /** seq 空洞：turn/activity 的硬边界。 */
    data class GapMarker(
        override val key: String,
        override val primarySeq: EventSeq,
        val expectedSeq: EventSeq,
        val arrivedSeq: EventSeq,
    ) : TimelineItem

    data class HistoryUnsafeMarker(
        override val key: String,
        override val primarySeq: EventSeq,
        val unknownType: String,
    ) : TimelineItem

    data class PendingAnchor(
        override val key: String,
        override val primarySeq: EventSeq,
        val requestId: RequestId,
        val kind: PendingKind,
        val anchoredToCallId: CallId?,
    ) : TimelineItem

    /** 未登记事件类型的聚合行，避免第一个新增类型永久卡住会话。 */
    data class UnsupportedCount(
        override val key: String,
        override val primarySeq: EventSeq,
        val count: Int,
    ) : TimelineItem
}

data class TimelineSnapshot(
    val sessionId: SessionId,
    val items: List<TimelineItem>,
    /** 已投影到的最高连续 seq，用于 UI 判断“历史可能不完整”。 */
    val contiguousSeq: EventSeq?,
    val hasGap: Boolean,
    val historyUnsafe: Boolean,
    val runningTurn: Boolean,
)
