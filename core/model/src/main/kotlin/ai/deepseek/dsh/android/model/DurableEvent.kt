package ai.deepseek.dsh.android.model

/** assistant/message 与 user/message 等 surface 事件的替换语义；原始字段必须保留。 */
sealed interface SurfaceOp {
    data object Append : SurfaceOp
    data class Replace(val startSeq: EventSeq, val endSeq: EventSeq) : SurfaceOp
}

/** 消息内容块。tool-call 块的 id 与独立 tool/call 事件的 callId 同一 id 空间、不同字段名。 */
sealed interface ContentPart {
    data class Text(val text: String) : ContentPart
    data class Reasoning(val text: String) : ContentPart
    data class ToolCallBlock(val blockId: String, val toolName: String, val argumentsJson: String) : ContentPart
    /** 未识别的内容块：只计数，不猜测语义。 */
    data class Unsupported(val type: String) : ContentPart
}

data class UsageInfo(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheReadTokens: Long?,
)

/** turn/end 的原因。aborted 带嵌套成因；未知 kind 走通用文案，不静默归并。 */
sealed interface TurnEndReason {
    data object Completed : TurnEndReason
    data class Aborted(val cause: AbortCause) : TurnEndReason
    data object Blocked : TurnEndReason
    data class Failure(val summary: String) : TurnEndReason
    data object MaxTokens : TurnEndReason
    data object Interrupted : TurnEndReason
    data object Forked : TurnEndReason
    data class Unknown(val kind: String) : TurnEndReason
}

enum class AbortCause { User, Parent, Hook, Disposed, Legacy }

/** 审批结论，取值与 user-approval 的 ApprovalOutcome 一致。 */
enum class ApprovalOutcome {
    AllowedOnce, Rejected, Cancelled, Unavailable;

    companion object {
        fun fromWire(raw: String?): ApprovalOutcome = when (raw) {
            "allowed-once" -> AllowedOnce
            "rejected" -> Rejected
            "cancelled" -> Cancelled
            "unavailable" -> Unavailable
            else -> Unavailable
        }
    }
}

/**
 * 投影器的唯一输入：已持久化的会话事件。
 * 传输元数据（epoch、since、latestSeq、reset、truncated、replay、waitSupported）不在这里，
 * 它们属于 :core:network，不进入 durable 模型。
 */
sealed interface DurableEvent {
    val seq: EventSeq
    val wallClockMs: Long

    data class UserMessage(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val text: String,
        val sourceKind: String,
        val surfaceOp: SurfaceOp?,
    ) : DurableEvent {
        val isHumanInitiated: Boolean get() = sourceKind == "user"
    }

    data class AssistantMessage(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val parts: List<ContentPart>,
        val usage: UsageInfo?,
        val interrupted: Boolean,
        val modelId: String?,
        val finishReason: String?,
    ) : DurableEvent

    /** 一次被放弃的重试：折叠为诊断行，不是普通气泡。 */
    data class AssistantAttempt(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val text: String?,
        val errorKind: String?,
    ) : DurableEvent

    data class ToolCall(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val callId: CallId,
        val toolName: String,
        val argumentsJson: String,
        val surfaceOp: SurfaceOp?,
    ) : DurableEvent

    data class ToolResult(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val callId: CallId?,
        val toolName: String?,
        val contentJson: String,
        val isError: Boolean,
        val surfaceOp: SurfaceOp?,
    ) : DurableEvent

    data class TurnStart(override val seq: EventSeq, override val wallClockMs: Long) : DurableEvent

    data class TurnEnd(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val reason: TurnEndReason,
    ) : DurableEvent

    data class StepStart(override val seq: EventSeq, override val wallClockMs: Long) : DurableEvent

    data class StepEnd(override val seq: EventSeq, override val wallClockMs: Long) : DurableEvent

    data class ApprovalAsked(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val requestId: RequestId,
        val toolName: String,
        val callId: CallId?,
        val reason: String?,
    ) : DurableEvent

    data class ApprovalDecided(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val requestId: RequestId,
        val outcome: ApprovalOutcome,
    ) : DurableEvent

    /** 由 ask_user_question 的 tool/call 派生，DSH 没有独立的 question 事件。 */
    data class QuestionAsked(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val callId: CallId,
        val questionsJson: String,
    ) : DurableEvent

    /** tool/result 或后续 user-question-reply 都表示提问已回答。 */
    data class QuestionResolved(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val callId: CallId,
        val answerJson: String?,
        val replySourceKind: String?,
    ) : DurableEvent

    data class CompactionStarted(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val compactionId: String,
        val turn: Long?,
    ) : DurableEvent

    data class CompactionEnded(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val compactionId: String,
        val error: String?,
    ) : DurableEvent

    data class CompactionSummary(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val compactionId: String,
        val summaryText: String,
    ) : DurableEvent

    data class SessionEndSeed(override val seq: EventSeq, override val wallClockMs: Long) : DurableEvent

    data class SystemMessage(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val text: String,
        val surfaceOp: SurfaceOp?,
    ) : DurableEvent

    data class DeveloperMessage(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val text: String,
        val surfaceOp: SurfaceOp?,
    ) : DurableEvent

    data class ModelSelection(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val modelId: String,
    ) : DurableEvent

    data class GoalChange(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val phase: String,
        val objective: String?,
    ) : DurableEvent

    data class WorkingDirectoryChange(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val path: String,
    ) : DurableEvent

    /** 会话名册之外的状态行，host/session-status 等只能更新已存在会话。 */
    data class SessionTitle(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val title: String,
    ) : DurableEvent

    data class Unknown(
        override val seq: EventSeq,
        override val wallClockMs: Long,
        val type: String,
        val ignorable: Boolean,
    ) : DurableEvent
}

/** 提问工具的名字，DSH 侧固定值；身份来自其 callId。 */
const val QUESTION_TOOL_NAME: String = "ask_user_question"
