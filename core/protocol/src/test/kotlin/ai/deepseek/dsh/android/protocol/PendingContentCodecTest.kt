package ai.deepseek.dsh.android.protocol

import ai.deepseek.dsh.android.model.PendingContent
import ai.deepseek.dsh.android.model.PendingKind
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D32：卡片内容解码对着 `contract/gateway/v0` 的真实录制跑，不另造 payload 手稿。
 * 录制内容是“帧的 data 对象”，与 `DurableRow.payloadJson` 存进 pending 行的形态一致。
 */
class PendingContentCodecTest {

    @Test
    fun approvalPayloadFromRecordingDecodesToolAndReason() {
        val payload = recording("approval-asked-and-respond-accepted.json")
            .firstEventPayload { string("type") == "approval/requested" }

        val content = decodePendingContent(PendingKind.Approval, payload.toString())

        assertEquals(PendingContent.Approval(toolName = "shell", reason = "执行测试命令"), content)
    }

    @Test
    fun questionPayloadFromRecordingDecodesEveryPromptAndOption() {
        val payload = recording("question-toolcall-and-result.json")
            .firstEventPayload { string("type") == "session/event" && innerData()?.string("name") == "ask_user_question" }
        val data = payload.obj("event")?.obj("data") ?: error("session/event wrapper missing in recording")

        val content = decodePendingContent(PendingKind.Question, data.toString())

        val question = (content as PendingContent.Question).questions.single()
        assertEquals("Recorder question", question.header)
        assertEquals("Proceed?", question.text)
        assertEquals(false, question.multiSelect)
        val option = question.options.single()
        assertEquals("Yes", option.label)
        assertEquals("Continue", option.description)
        assertTrue(question.questionId.isNotBlank())
    }

    @Test
    fun bareQuestionsShapeIsAcceptedToo() {
        val bare = """{"questions":[{"id":"q1","question":"继续吗？","multiSelect":true,"options":[]}]}"""

        val content = decodePendingContent(PendingKind.Question, bare)

        val question = (content as PendingContent.Question).questions.single()
        assertEquals("q1", question.questionId)
        assertTrue(question.multiSelect)
        assertTrue(question.options.isEmpty())
    }

    @Test
    fun brokenPayloadDegradesToUndecodableInsteadOfThrowing() {
        assertEquals(PendingContent.Undecodable(PendingKind.Question), decodePendingContent(PendingKind.Question, "{"))
        assertEquals(
            PendingContent.Undecodable(PendingKind.Question),
            decodePendingContent(PendingKind.Question, """{"questions":[]}"""),
        )
        // 审批缺字段仍可用：卡片显示身份与状态，不伪造内容。
        assertEquals(
            PendingContent.Approval(toolName = null, reason = null),
            decodePendingContent(PendingKind.Approval, "{}"),
        )
    }

    // ---- recording helpers ----

    private fun recording(name: String): JsonObject =
        Json.parseToJsonElement(readContract(name)) as JsonObject

    /** 在整份录制的所有响应里找第一个满足条件的“事件载荷对象”。 */
    private fun JsonObject.firstEventPayload(predicate: JsonObject.() -> Boolean): JsonObject {
        val arrays = ArrayList<JsonArray>()
        fun collect(element: JsonElement?) {
            when (element) {
                is JsonArray -> element.forEach(::collect)
                is JsonObject -> {
                    element["events"]?.let { arrays += it as JsonArray }
                    element["baseline"]?.let { arrays += it as JsonArray }
                    element.values.forEach(::collect)
                }
                else -> Unit
            }
        }
        collect(this)
        for (array in arrays) {
            for (row in array) {
                val obj = row as? JsonObject ?: continue
                // poll 行有两种形态：events[] 是 {event:{payload:…},seq,ts}，baseline[] 是
                // {payload:…,rpcId}（D29）。两者都要剥到帧的 data 对象才做判定。
                val payload = obj.obj("event")?.obj("payload") ?: obj.obj("payload") ?: continue
                if (payload.predicate()) return payload
            }
        }
        error("no recorded event payload matched the predicate")
    }

    private fun JsonObject.innerData(): JsonObject? = obj("event")?.obj("data")

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun readContract(name: String): String {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "contract/gateway/v0")
            if (candidate.isDirectory) return File(candidate, name).readText()
            dir = dir.parentFile
        }
        error("contract/gateway/v0 not found walking up from ${System.getProperty("user.dir")}")
    }
}
