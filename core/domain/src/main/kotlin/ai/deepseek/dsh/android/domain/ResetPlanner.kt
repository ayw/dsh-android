package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.BaselineEntry
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.ResetTrigger
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TransportCursor
import ai.deepseek.dsh.android.model.TransportFrame

enum class RosterResetScope { Mux, Host }

enum class WatermarkChange { Advance, Ignore }

enum class RowIgnoreReason { RosterOnly, NotTrusted, UnknownRow, UnknownType }

/** 名册之外唯一的会话行变更来源：已存在行可更新，任何事件都不插入。 */
sealed interface SessionRowOp {
    data class Update(val sessionId: SessionId) : SessionRowOp
    data class Ignore(val sessionId: SessionId, val reason: RowIgnoreReason) : SessionRowOp
}

/** host 通道一帧的处置：行操作，或“名册需要重拉”。 */
sealed interface RosterEventPlan {
    data class Row(val op: SessionRowOp) : RosterEventPlan
    data object RosterReload : RosterEventPlan
}

data class RosterPlan(
    val sessionRowOps: List<SessionRowOp>,
    val pendingMuxReconnect: Boolean,
    val hostRosterReloadPending: Boolean,
)

/** baseline 里携带的 `host/mux-reload` 只重拉名册，不重建 durable 事务。 */
data class ResetBaselineTarget(
    val sessionId: SessionId,
    val lastSeq: EventSeq?,
    val presentKeys: Set<DurableStore.PendingKey>,
    val absentKeys: List<DurableStore.PendingKey>,
    val watermark: EventSeq?,
    val reloadMux: Boolean,
) {
    val sweepSkipped: Boolean get() = absentKeys.isEmpty()
}

/** reset 判定输入：与网关 `eventPollPayload` 的 OR 项一一对应。 */
data class ResetContext(
    val storedEpoch: String?,
    val batchEpoch: String?,
    val since: Long,
    val latestSeq: Long,
    val serverResetFlag: Boolean,
    val truncated: Boolean,
)

data class ResetAssessment(
    val triggers: Set<ResetTrigger>,
    val reset: Boolean,
    val triggerUnobservable: Boolean,
    val reconnectionReloadingBaseline: Boolean,
) {
    /** 网关只给一个 OR 出来的布尔时，能单独归因的触发项。 */
    val observableTriggers: List<ResetTrigger> get() = ResetTrigger.entries.filter { it in triggers }
}

/** reset 的事务计划；steps 的顺序就是 EventApplier 的执行顺序，cursor 永远在最后。 */
data class ResetPlan(
    val serverId: ServerId,
    val channel: ChannelKind,
    val assessment: ResetAssessment,
    val cursorAfter: TransportCursor?,
    val baseline: List<BaselineEntry>,
    val frames: List<TransportFrame>,
    val sweeps: List<ResetBaselineTarget>,
    val steps: List<ResetStep>,
    val headSeq: EventSeq?,
) {
    val reset: Boolean get() = assessment.reset
    val sweepSessions: List<SessionId> get() = sweeps.filter { it.absentKeys.isNotEmpty() }.map { it.sessionId }
}

sealed interface ResetStep {
    data object ClearTransportCursor : ResetStep
    data class TombstoneAbsent(val sessionId: SessionId, val keys: List<DurableStore.PendingKey>) : ResetStep
    data object ApplyBaseline : ResetStep
    data object ApplyEvents : ResetStep
    data object AdvanceCursor : ResetStep
}

object ResetPlanner {

    /** host 通道请求重拉名册的信号帧类型。 */
    const val HOST_RESYNC_REQUESTED: String = "host/resync-requested"

    private const val SUBSCRIBED_TYPE = "session/subscribed"
    private const val REMOVED_TYPE = "host/session-removed"
    private const val MUX_RELOAD_TYPE = "host/mux-reload"
    private val ROW_UPDATE_TYPES = setOf("session/projection", "host/session-activity", "host/session-status")
    private val ROSTER_PAYLOAD_TYPES = setOf("session/list", "session/list-updated", "host/session-list")

    fun triggers(context: ResetContext, epochTracking: Boolean): Set<ResetTrigger> {
        val fired = mutableSetOf<ResetTrigger>()
        if (context.serverResetFlag) fired += ResetTrigger.ServerResetFlag
        if (epochTracking && context.storedEpoch != null && context.batchEpoch != null &&
            context.storedEpoch != context.batchEpoch
        ) {
            fired += ResetTrigger.EpochChanged
        }
        if (context.truncated) fired += ResetTrigger.Truncated
        if (context.latestSeq < context.since) fired += ResetTrigger.LatestSeqBehindSince
        return fired.toSet()
    }

    fun triggers(batch: EventBatch, storedEpoch: String?, epochTracking: Boolean): Set<ResetTrigger> =
        triggers(
            ResetContext(
                storedEpoch = storedEpoch,
                batchEpoch = batch.epoch,
                since = batch.since,
                latestSeq = batch.latestSeq,
                serverResetFlag = batch.reset,
                truncated = batch.truncated,
            ),
            epochTracking,
        )

    fun assess(context: ResetContext, epochTracking: Boolean): ResetAssessment {
        val fired = triggers(context, epochTracking)
        return ResetAssessment(
            triggers = fired,
            reset = fired.isNotEmpty(),
            triggerUnobservable = fired == setOf(ResetTrigger.ServerResetFlag),
            reconnectionReloadingBaseline =
                reconnectionReloadScope(context.batchEpoch, context.storedEpoch) == RosterResetScope.Mux,
        )
    }

    /**
     * 会话 head 的取证顺序：帧与 baseline 的 `lastSeq` 都算证据，取最大；
     * 两者都没有才退到通道 latestSeq（它跨会话，只作最后兜底）。
     */
    fun headSeqOf(batch: EventBatch): EventSeq? {
        val evidence = batch.frames.map { it.seq } + batch.baseline.orEmpty().mapNotNull { it.lastSeq }
        return evidence.maxByOrNull { it.value } ?: seqOf(batch.latestSeq)
    }

    /**
     * epoch 前进会同时带来 `reset` 与 `truncated`，重连时走 baseline 重放 + 增量事件恢复；
     * `host/mux-reload` 只走名册重拉与 mux 重订阅，不能顺带清空待处理事务。
     */
    fun reconnectionReloadScope(batchEpoch: String?, storedEpoch: String?): RosterResetScope =
        if (batchEpoch != null && storedEpoch != null && batchEpoch != storedEpoch) {
            RosterResetScope.Mux
        } else {
            RosterResetScope.Host
        }

    fun plan(
        identity: ConnectionIdentity,
        serverId: ServerId,
        batch: EventBatch,
        storedCursor: TransportCursor?,
        preBatchKeys: Map<SessionId, Set<DurableStore.PendingKey>>,
        preBatchWatermark: Map<SessionId, EventSeq> = emptyMap(),
        epochTracking: Boolean = true,
    ): ResetPlan {
        val assessment = assess(
            ResetContext(
                storedEpoch = storedCursor?.epoch,
                batchEpoch = batch.epoch,
                since = batch.since,
                latestSeq = batch.latestSeq,
                serverResetFlag = batch.reset,
                truncated = batch.truncated,
            ),
            epochTracking,
        )
        val cursorAfter = if (assessment.reset) {
            TransportCursor(identity, batch.channel, batch.latestSeq, batch.epoch)
        } else {
            null
        }
        val sweeps = if (batch.baseline == null) {
            emptyList()
        } else {
            baselineTargets(batch, preBatchKeys, preBatchWatermark)
        }
        val steps = buildList {
            if (assessment.reset) add(ResetStep.ClearTransportCursor)
            sweeps.filter { it.absentKeys.isNotEmpty() }
                .forEach { add(ResetStep.TombstoneAbsent(it.sessionId, it.absentKeys)) }
            if (batch.baseline != null) add(ResetStep.ApplyBaseline)
            if (batch.frames.isNotEmpty()) add(ResetStep.ApplyEvents)
            if (cursorAfter != null) add(ResetStep.AdvanceCursor)
        }
        return ResetPlan(
            serverId = serverId,
            channel = batch.channel,
            assessment = assessment,
            cursorAfter = cursorAfter,
            baseline = batch.baseline ?: emptyList(),
            frames = batch.frames,
            sweeps = sweeps,
            steps = steps,
            headSeq = headSeqOf(batch),
        )
    }

    /** absent 只与批处理前已存在的 key 对照，baseline 缺席即 tombstone 的候选集合。 */
    fun baselineTargets(
        batch: EventBatch,
        preBatchKeys: Map<SessionId, Set<DurableStore.PendingKey>>,
        preBatchWatermark: Map<SessionId, EventSeq> = emptyMap(),
    ): List<ResetBaselineTarget> =
        batch.baseline.orEmpty().groupBy { it.sessionId }.map { (sessionId, entries) ->
            val present = entries.mapNotNull { it.pendingKeyOrNull() }.toSet()
            val absent = (preBatchKeys[sessionId].orEmpty() - present).sortedWith(
                compareBy<DurableStore.PendingKey> { it.kind.ordinal }.thenBy { it.requestId.value },
            )
            // baseline 行不保证按 seq 有序，取最大值而不是取末行。
            val highWater = entries.mapNotNull { it.lastSeq }.maxByOrNull { it.value }
            ResetBaselineTarget(
                sessionId = sessionId,
                lastSeq = highWater,
                presentKeys = present,
                absentKeys = absent,
                watermark = preBatchWatermark[sessionId] ?: highWater,
                reloadMux = entries.any { it.payloadJson.contains(MUX_RELOAD_TYPE) },
            )
        }

    fun sessionRowOp(type: String, sessionId: SessionId, knownRows: Set<SessionId>): SessionRowOp =
        when {
            type == SUBSCRIBED_TYPE -> SessionRowOp.Ignore(sessionId, RowIgnoreReason.RosterOnly)
            type == REMOVED_TYPE -> SessionRowOp.Ignore(sessionId, RowIgnoreReason.NotTrusted)
            type in ROSTER_PAYLOAD_TYPES -> SessionRowOp.Ignore(sessionId, RowIgnoreReason.RosterOnly)
            type in ROW_UPDATE_TYPES && sessionId in knownRows -> SessionRowOp.Update(sessionId)
            type in ROW_UPDATE_TYPES -> SessionRowOp.Ignore(sessionId, RowIgnoreReason.UnknownRow)
            else -> SessionRowOp.Ignore(sessionId, RowIgnoreReason.UnknownType)
        }

    fun planRosterEvent(
        frame: TransportFrame,
        knownRows: Set<SessionId>,
    ): RosterEventPlan =
        if (frame.type == HOST_RESYNC_REQUESTED) {
            RosterEventPlan.RosterReload
        } else {
            RosterEventPlan.Row(sessionRowOp(frame.type, frame.sessionId, knownRows))
        }

    fun planRosterEventBatch(
        frames: List<TransportFrame>,
        knownRows: Set<SessionId>,
    ): RosterPlan = RosterPlan(
        sessionRowOps = frames.mapNotNull { frame ->
            (planRosterEvent(frame, knownRows) as? RosterEventPlan.Row)?.op as? SessionRowOp.Update
        }.distinctBy { it.sessionId },
        pendingMuxReconnect = frames.any { it.type == MUX_RELOAD_TYPE },
        hostRosterReloadPending = frames.any { it.type == HOST_RESYNC_REQUESTED },
    )

    /** 只在游标前进时改 watermark；相等与回退都忽略。 */
    fun advanceWatermark(current: EventSeq?, arrived: EventSeq): WatermarkChange =
        if (current != null && arrived.value <= current.value) WatermarkChange.Ignore else WatermarkChange.Advance

    fun seqOf(value: Long): EventSeq = EventSeq(value)
}

/**
 * baseline 行的待处理身份由协议层判定：`:core:protocol` 解码时填 `pendingRef`，domain 不再从
 * `payloadJson` 里猜事件家族。子串匹配会把任何正文提到 `approval/asked` 的行误判成待处理行，
 * 进而把真实存在的请求 tombstone 掉。
 */
internal fun BaselineEntry.pendingKeyOrNull(): DurableStore.PendingKey? =
    pendingRef?.let { DurableStore.PendingKey(it.kind, it.requestId) }
