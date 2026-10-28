package ai.deepseek.dsh.android.protocol

import ai.deepseek.dsh.android.model.DurableEvent
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRef
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.TransportFrame

/**
 * baseline 行的待处理身份。事件家族由 [DurableEventCodec] 的精确 `type` 分派决定，
 * 而不是对 `payloadJson` 做子串匹配：任何正文里提到 `approval/asked` 的行（工具输出、
 * 会话标题、甚至用户消息原文）都会被子串匹配误判成待处理行，sweep 于是把真实存在的
 * 请求当成 absent 并 tombstone 掉。
 *
 * 只认 asked：decided/resolved 是已结算行，若也算进 presentKeys，`onBaselinePresent`
 * 会把已结算的请求复活成 PENDING。
 */
object BaselinePendingRef {

    fun of(frame: TransportFrame): PendingRef? = when (val event = DurableEventCodec.decode(frame)) {
        is DurableEvent.ApprovalAsked -> PendingRef(PendingKind.Approval, event.requestId)
        is DurableEvent.QuestionAsked -> PendingRef(PendingKind.Question, RequestId(event.callId.value))
        else -> null
    }
}
