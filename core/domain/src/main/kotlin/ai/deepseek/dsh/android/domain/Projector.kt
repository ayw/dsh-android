package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.DurableEvent
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TimelineSnapshot

/** 一次投影的结果：时间线快照，加上只属于诊断、不进时间线的计数与在途状态。 */
data class Projection(
    val snapshot: TimelineSnapshot,
    /** 正文为空且没有 usage 的 assistant/message：不出气泡，但必须可被数出来。 */
    val skippedEmptyAssistant: Int,
    /** 仍在进行、等 id 匹配的 ended 事件的压缩操作 id；带旧 id 的迟到 ended 不会清空它。 */
    val openCompactionId: String?,
)

/**
 * 会话时间线的唯一投影者：纯函数，无 Room、无协程、无 Android 类型、不读时钟。
 * wallClockMs 只能来自事件本身，单调时间由调用方注入的 [Clock] 负责。
 *
 * 输入约定：`events` 已去重并按 seq 升序；同一 seq 再次投递视为替换（按 seq upsert），
 * 因此同一份输入永远得到等值的 [TimelineSnapshot]（G15 靠快照等值比对 v0/v1）。
 */
class Projector {

    fun project(
        sessionId: SessionId,
        events: List<DurableEvent>,
        pending: List<PendingRequest>,
        position: SessionPosition,
    ): TimelineSnapshot = projectWithStats(sessionId, events, pending, position).snapshot

    fun projectWithStats(
        sessionId: SessionId,
        events: List<DurableEvent>,
        pending: List<PendingRequest>,
        position: SessionPosition,
    ): Projection = TimelineBuilder(sessionId, events, pending, position).project()
}
