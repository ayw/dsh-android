package ai.deepseek.dsh.android.protocol

import ai.deepseek.dsh.android.model.PendingContent
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.QuestionOption
import ai.deepseek.dsh.android.model.QuestionPrompt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 把 pending 行里持久化的 `payloadJson` 解成卡片内容（D32）。
 *
 * 容忍两种来源形态：帧的 `data` 对象（正常路径），以及多包一层 `data` 的变体。
 * 提问同时接受 `{arguments:{questions:[…]}}` 与 `{questions:[…]}`（后者是
 * `DurableEvent.QuestionAsked.questionsJson` 的形态）。
 * 任何缺字段/坏 JSON 都落到 [PendingContent.Undecodable]，绝不抛：一张坏卡片不能拖垮收件箱。
 */
fun decodePendingContent(kind: PendingKind, payloadJson: String): PendingContent {
    val root = runCatching { Json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()
        ?: return PendingContent.Undecodable(kind)
    val data = root.obj("data") ?: root
    return when (kind) {
        PendingKind.Approval -> PendingContent.Approval(
            toolName = data.string("toolName"),
            reason = data.string("reason"),
        )

        PendingKind.Question -> {
            val args = data.obj("arguments") ?: data
            val questions = decodeQuestions(args)
            if (questions.isEmpty()) PendingContent.Undecodable(kind) else PendingContent.Question(questions)
        }
    }
}

private fun decodeQuestions(args: JsonObject): List<QuestionPrompt> {
    val raw = args.arr("questions") ?: return emptyList()
    val out = ArrayList<QuestionPrompt>(raw.size)
    for (element in raw) {
        val q = element as? JsonObject ?: continue
        val text = q.string("question") ?: continue
        val id = q.string("id") ?: q.string("questionId") ?: continue
        out += QuestionPrompt(
            questionId = id,
            header = q.string("header"),
            text = text,
            multiSelect = q.boolean("multiSelect") == true,
            options = decodeOptions(q.arr("options") ?: JsonArray(emptyList())),
        )
    }
    return out
}

private fun decodeOptions(raw: JsonArray): List<QuestionOption> {
    val out = ArrayList<QuestionOption>(raw.size)
    for (element in raw) {
        val o = element as? JsonObject ?: continue
        val label = o.string("label") ?: continue
        out += QuestionOption(label = label, description = o.string("description"))
    }
    return out
}
