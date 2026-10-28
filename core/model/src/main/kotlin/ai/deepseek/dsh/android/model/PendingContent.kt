package ai.deepseek.dsh.android.model

/**
 * 待处理卡片的可渲染内容。由数据层从持久化的 `payloadJson` 解码后交给特性模块（D32）：
 * 特性模块只做排版与本地化，不解析协议 JSON，也不接触协议 DTO。
 */
sealed interface PendingContent {
    data class Approval(val toolName: String?, val reason: String?) : PendingContent

    data class Question(val questions: List<QuestionPrompt>) : PendingContent

    /** 载荷缺字段或不是预期结构：卡片仍渲染身份与状态，但不伪造内容。 */
    data class Undecodable(val kind: PendingKind) : PendingContent
}

/** `ask_user_question` 的一个字段（`arguments.questions[]`）。 */
data class QuestionPrompt(
    val questionId: String,
    val header: String?,
    val text: String,
    val multiSelect: Boolean,
    val options: List<QuestionOption>,
)

data class QuestionOption(
    val label: String,
    val description: String?,
)
