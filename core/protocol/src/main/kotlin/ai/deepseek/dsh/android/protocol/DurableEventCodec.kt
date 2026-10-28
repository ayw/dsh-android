package ai.deepseek.dsh.android.protocol

import ai.deepseek.dsh.android.model.AbortCause
import ai.deepseek.dsh.android.model.ApprovalOutcome
import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.ContentPart
import ai.deepseek.dsh.android.model.DurableEvent
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.QUESTION_TOOL_NAME
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.TurnEndReason
import ai.deepseek.dsh.android.model.UsageInfo
import ai.deepseek.dsh.android.model.TransportFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The known-case + unknown-ignorable decode table. `type` stays a `String` on the wire ([TransportFrame]);
 * this is the ONLY place that string is turned into a [DurableEvent]. An unrecognised `type` NEVER throws:
 * it becomes [DurableEvent.Unknown] carrying the wire `ignorable` marker, and [EventClassification.tierOf]
 * decides what the projector does with it (D08).
 *
 * Field names below are the authoritative DSH `data` keys confirmed against
 * `packages/core/session/src/{types,known-event-types}.ts`,
 * `packages/session/session-format-v0-to-v1/src/dispositions.ts` and the real recordings under
 * `contract/gateway/v0/`. This codec consumes the ALREADY-UNWRAPPED [TransportFrame] produced by the
 * channel dispatch in `gateway/v0/Envelopes.kt` (D29): for a mux `session/event` row, [TransportFrame.type]
 * is the INNER durable type and [TransportFrame.payloadJson] is the inner `data` object, so every reader
 * below looks up `data` fields at the top level. Where a DSH field the durable model wants (`time`,
 * `surfaceOp`, `sourceEventSeqs`) is not carried, that field is defaulted.
 */
object DurableEventCodec {

    /** Question replies arrive as a user message whose source kind is this literal. */
    private const val USER_QUESTION_REPLY = "user-question-reply"

    fun decode(frame: TransportFrame): DurableEvent {
        val seq = frame.seq
        val wall = frame.wallClockMs
        val payload = parsePayload(frame.payloadJson)
        return when (frame.type) {
            "user/message" -> decodeUser(seq, wall, payload)
            "assistant/message" -> decodeAssistant(seq, wall, payload)
            "assistant/attempt" -> DurableEvent.AssistantAttempt(seq, wall, text = null, errorKind = null)
            "tool/call" -> decodeToolCall(seq, wall, payload)
            "tool/result" -> decodeToolResult(seq, wall, payload)
            "turn/start" -> DurableEvent.TurnStart(seq, wall)
            "turn/end" -> DurableEvent.TurnEnd(seq, wall, decodeTurnEndReason(payload.obj("reason")))
            "step/start" -> DurableEvent.StepStart(seq, wall)
            "step/end" -> DurableEvent.StepEnd(seq, wall)
            "approval/asked" -> decodeApprovalAsked(seq, wall, payload)
            "approval/requested" -> decodeApprovalAsked(seq, wall, payload)
            "approval/decided" -> decodeApprovalDecided(seq, wall, payload)
            "approval/resolved" -> decodeApprovalDecided(seq, wall, payload)
            "question/requested" -> decodeQuestionRequested(seq, wall, frame.rpcId, payload)
            "question/resolved" -> decodeQuestionResolved(seq, wall, payload)
            "compaction/start" -> DurableEvent.CompactionStarted(
                seq, wall,
                compactionId = payload.string("compactionId").orEmpty(),
                turn = payload.long("turn"),
            )
            "compaction/end" -> DurableEvent.CompactionEnded(
                seq, wall,
                compactionId = payload.string("compactionId").orEmpty(),
                error = payload.obj("error")?.string("message") ?: payload.string("error"),
            )
            "compaction/summary" -> DurableEvent.CompactionSummary(
                seq, wall,
                compactionId = payload.string("compactionId").orEmpty(),
                summaryText = payload.string("summary").orEmpty(),
            )
            "session/end-seed" -> DurableEvent.SessionEndSeed(seq, wall)
            "system/message" -> DurableEvent.SystemMessage(
                seq, wall, text = joinText(payload.obj("message")?.arr("content")), surfaceOp = null,
            )
            "developer/message" -> DurableEvent.DeveloperMessage(
                seq, wall, text = joinText(payload.obj("message")?.arr("content")), surfaceOp = null,
            )
            "model/selection" -> DurableEvent.ModelSelection(seq, wall, modelId = payload.string("model").orEmpty())
            "goal/change" -> DurableEvent.GoalChange(
                seq, wall,
                phase = payload.string("kind").orEmpty(),
                objective = payload.string("goal"),
            )
            "working-directory/change" -> DurableEvent.WorkingDirectoryChange(
                seq, wall, path = payload.string("cwd").orEmpty(),
            )
            "session/title" -> DurableEvent.SessionTitle(seq, wall, title = payload.string("title").orEmpty())
            else -> DurableEvent.Unknown(seq, wall, type = frame.type, ignorable = frame.ignorable)
        }
    }

    private fun decodeUser(seq: EventSeq, wall: Long, payload: JsonObject): DurableEvent {
        val sourceKind = payload.obj("source")?.string("kind") ?: "user"
        if (sourceKind == USER_QUESTION_REPLY) {
            val callId = payload.obj("source")?.string("callId")
            return DurableEvent.QuestionResolved(
                seq, wall,
                callId = CallId(callId ?: ""),
                answerJson = payload.arr("content")?.rawOrEmptyObject(),
                replySourceKind = sourceKind,
            )
        }
        return DurableEvent.UserMessage(
            seq, wall,
            text = joinText(payload.arr("content")),
            sourceKind = sourceKind,
            surfaceOp = null,
        )
    }

    private fun decodeAssistant(seq: EventSeq, wall: Long, payload: JsonObject): DurableEvent {
        // `assistant/message` puts `content` at the top level of `data` (contract/gateway/v0),
        // exactly like `user/message`; there is no intermediate `message` object.
        return DurableEvent.AssistantMessage(
            seq, wall,
            parts = decodeParts(payload.arr("content")),
            usage = decodeUsage(payload.obj("usage")),
            interrupted = payload.boolean("interrupted") == true,
            // assistant/message carries no model id / finish reason in the DSH payload; both are
            // contract-recording TODOs until the gateway exposes them. See the report.
            modelId = payload.obj("source")?.string("model"),
            finishReason = payload.string("finishReason"),
        )
    }

    private fun decodeToolCall(seq: EventSeq, wall: Long, payload: JsonObject): DurableEvent {
        val callId = CallId(payload.string("callId") ?: "")
        val name = payload.string("name").orEmpty()
        val args = payload.string("arguments") ?: payload.obj("arguments").rawOrEmptyObject()
        return if (name == QUESTION_TOOL_NAME) {
            DurableEvent.QuestionAsked(seq, wall, callId = callId, questionsJson = args)
        } else {
            DurableEvent.ToolCall(seq, wall, callId = callId, toolName = name, argumentsJson = args, surfaceOp = null)
        }
    }

    private fun decodeToolResult(seq: EventSeq, wall: Long, payload: JsonObject): DurableEvent {
        val callId = ToolAssociation.resolve(payload)
        val message = payload.obj("message")
        val error = payload.obj("error")
        val isError = message?.boolean("isError") ?: (error != null)
        return DurableEvent.ToolResult(
            seq, wall,
            callId = callId,
            toolName = message?.string("name") ?: payload.string("name"),
            contentJson = message?.arr("content").rawOrEmptyObject(),
            isError = isError,
            surfaceOp = null,
        )
    }

    private fun decodeApprovalAsked(seq: EventSeq, wall: Long, payload: JsonObject): DurableEvent =
        // F8: modern identity is `data.id`. The gateway 0.8.1 recordings (legacy fake-DSH dialect,
        // see contract/gateway/v0/approval-asked-and-respond-accepted.json) carry the same identity
        // under the flat `approvalId` key instead; accept either, preferring `id`.
        DurableEvent.ApprovalAsked(
            seq, wall,
            requestId = RequestId(payload.string("id") ?: payload.string("approvalId") ?: ""),
            toolName = payload.string("toolName").orEmpty(),
            callId = payload.string("callId")?.let { CallId(it) },
            reason = payload.string("reason"),
        )

    private fun decodeApprovalDecided(seq: EventSeq, wall: Long, payload: JsonObject): DurableEvent =
        DurableEvent.ApprovalDecided(
            seq, wall,
            requestId = RequestId(payload.string("id") ?: payload.string("approvalId") ?: ""),
            outcome = ApprovalOutcome.fromWire(payload.string("outcome")),
        )

    /**
     * Gateway-synthesized `question/requested` (`{sessionId, questions}`) has no `callId`: the modern
     * waterfall `eventId` (the frame `rpcId`) IS the identity the upstream accepts on resolve, so it
     * becomes the durable [CallId] here and travels to `pending_request.transport_key` unchanged.
     */
    private fun decodeQuestionRequested(seq: EventSeq, wall: Long, rpcId: String?, payload: JsonObject): DurableEvent {
        val callId = payload.string("callId") ?: rpcId
        if (callId.isNullOrEmpty()) return DurableEvent.Unknown(seq, wall, type = "question/requested", ignorable = false)
        return DurableEvent.QuestionAsked(
            seq, wall,
            callId = CallId(callId),
            questionsJson = payload["questions"].rawOrEmptyObject(),
        )
    }

    /** Gateway-synthesized `question/resolved` (`{sessionId, questionRpcId, outcome}`). */
    private fun decodeQuestionResolved(seq: EventSeq, wall: Long, payload: JsonObject): DurableEvent {
        val callId = payload.string("questionRpcId") ?: payload.string("callId") ?: ""
        return DurableEvent.QuestionResolved(seq, wall, callId = CallId(callId), answerJson = null, replySourceKind = null)
    }

    private fun decodeTurnEndReason(reason: JsonObject?): TurnEndReason {
        val kind = reason?.string("kind") ?: return TurnEndReason.Unknown("")
        return when (kind) {
            "completed" -> TurnEndReason.Completed
            "aborted" -> TurnEndReason.Aborted(decodeAbortCause(reason.obj("reason")?.string("kind")))
            "blocked" -> TurnEndReason.Blocked
            "max-tokens" -> TurnEndReason.MaxTokens
            "interrupted" -> TurnEndReason.Interrupted
            "forked" -> TurnEndReason.Forked
            "error" -> TurnEndReason.Failure(decodeFailureSummary(reason.obj("error")))
            else -> TurnEndReason.Unknown(kind)
        }
    }

    private fun decodeAbortCause(raw: String?): AbortCause = when (raw) {
        "user" -> AbortCause.User
        "parent" -> AbortCause.Parent
        "hook" -> AbortCause.Hook
        "disposed" -> AbortCause.Disposed
        "legacy" -> AbortCause.Legacy
        else -> AbortCause.Legacy
    }

    private fun decodeFailureSummary(error: JsonObject?): String =
        error?.string("message") ?: error?.string("code") ?: error.rawOrEmptyObject()

    private fun decodeUsage(usage: JsonObject?): UsageInfo? {
        if (usage == null) return null
        return UsageInfo(
            inputTokens = usage.long("inputTokens"),
            outputTokens = usage.long("outputTokens"),
            cacheReadTokens = usage.long("cacheReadTokens"),
        )
    }

    private fun decodeParts(content: JsonArray?): List<ContentPart> =
        content?.mapNotNull { (it as? JsonObject)?.let(::decodePart) } ?: emptyList()

    private fun decodePart(block: JsonObject): ContentPart {
        val type = block.string("type") ?: return ContentPart.Unsupported("")
        return when (type) {
            "text" -> ContentPart.Text(block.string("text").orEmpty())
            "reasoning" -> ContentPart.Reasoning(block.string("text").orEmpty())
            "tool-call" -> ContentPart.ToolCallBlock(
                blockId = block.string("id").orEmpty(),
                toolName = block.string("name").orEmpty(),
                argumentsJson = block.string("arguments") ?: block.obj("arguments").rawOrEmptyObject(),
            )
            else -> ContentPart.Unsupported(type)
        }
    }

    private fun joinText(content: JsonArray?): String =
        content?.joinToString("") { (it as? JsonObject)?.takeIf { o -> o.string("type") == "text" }?.string("text").orEmpty() }
            ?: ""

    private fun parsePayload(payloadJson: String): JsonObject =
        runCatching { DshJson.spec.parseToJsonElement(payloadJson) as? JsonObject }
            .getOrNull() ?: EMPTY_OBJ
}
