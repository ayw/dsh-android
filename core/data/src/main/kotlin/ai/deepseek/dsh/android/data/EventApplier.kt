package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.DurableStore
import ai.deepseek.dsh.android.domain.GapDetector
import ai.deepseek.dsh.android.domain.GapObservation
import ai.deepseek.dsh.android.domain.Islands
import ai.deepseek.dsh.android.domain.PendingEvent
import ai.deepseek.dsh.android.domain.PendingIdentity
import ai.deepseek.dsh.android.domain.PendingReducer
import ai.deepseek.dsh.android.domain.Positions
import ai.deepseek.dsh.android.domain.ResetBaselineTarget
import ai.deepseek.dsh.android.domain.ResetPlanner
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.SystemClock
import ai.deepseek.dsh.android.domain.toIdentity
import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.DurableEvent
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.NeedsResync
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.ResyncReason
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TransportCursor
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.protocol.DshJson
import ai.deepseek.dsh.android.protocol.DurableEventCodec
import ai.deepseek.dsh.android.protocol.EventClassification
import ai.deepseek.dsh.android.protocol.IgnoreTier
import kotlinx.serialization.json.JsonObject

/**
 * 一个会话的应用状态。`:core:domain` 的端口只暴露“写”，位置与岛账本的读侧没有端口，
 * 因此调用方（[SyncActor]）把它们带进来；本类不猜、也不从 raw 表重建，避免出现第二套真相（D26）。
 */
data class SessionSyncState(
    val position: SessionPosition = SessionPosition(null, null),
    val islands: List<AppliedIsland> = emptyList(),
    val needsResync: NeedsResync? = null,
)

/**
 * 一批之前已知的同步事实。所有字段都可缺省：缺省意味着“冷启动，不知道”，
 * 于是位置从零开始、空岛、空 pending —— 绝不意味着“确认没有”。
 */
data class KnownSyncState(
    val sessions: Map<SessionId, SessionSyncState> = emptyMap(),
    val pending: List<PendingRequest> = emptyList(),
    /** baseline sweep 的 absent 只能与**批前已存在**的 key 对照（D13），所以必须由调用方带入。 */
    val pendingKeys: Map<SessionId, Set<DurableStore.PendingKey>> = emptyMap(),
    /** baseline watermark 只用于计划归因，不写回位置。 */
    val watermarks: Map<SessionId, EventSeq> = emptyMap(),
) {
    companion object {
        val Empty = KnownSyncState()
    }
}

/** 一次应用的结果：既有落库批次（测试/诊断用），也有供 actor 维护内存视图的后置状态。 */
data class ApplyOutcome(
    val committed: DurableStore.ApplyBatch,
    val sessions: Map<SessionId, SessionSyncState>,
    val pending: List<PendingRequest>,
    val gaps: List<GapObservation>,
    val historyUnsafeSessions: Set<SessionId>,
    val badJsonCount: Int,
    val unknownEventCount: Int,
) {
    val reset: Boolean get() = committed.cursorAfter != null && committed.islands.values.all { it.isEmpty() }
}

/**
 * D08 的单向标记端口。Room 适配器在事务外置位（`session_head.historyUnsafe` 只能由
 * `markUnsafe` 改），因此这里允许“先标记、后落库”：多标一次只是保守，少标一次会让历史
 * 被假装可重建。**在 commit 之前**调用，崩溃时宁可留下假阳性。
 */
fun interface HistoryUnsafeMarker {
    suspend fun mark(serverId: ServerId, sessionId: SessionId)

    companion object {
        val None = HistoryUnsafeMarker { _, _ -> }
    }
}

/**
 * reset 的“清游标”步骤（[ai.deepseek.dsh.android.domain.ResetStep.ClearTransportCursor]）。
 * 清空发生在事务之前是安全的：崩溃最多让下一轮从头重放，绝不会跳过未应用的帧。
 */
fun interface TransportCursorCleaner {
    suspend fun clear(identity: ConnectionIdentity)

    companion object {
        val None = TransportCursorCleaner { }
    }
}

/**
 * 批次 → 单个 [DurableStore.ApplyBatch] 的唯一编排者。
 *
 * 它只做编排，不重新实现任何领域算法：空洞判定/游标推进用 [Positions]（内部就是 [GapDetector]），
 * 岛账本用 [Islands]，reset 顺序与 baseline sweep 的 absent 集合用 [ResetPlanner]，
 * pending 状态机用 [PendingReducer]。顺序不变量由 [DurableStore] 保证：一次 commit 一个事务，
 * cursor 段落永远最后；本类在 reset 时**只在计划允许时才给出 cursorAfter**，
 * 且清游标永远发生在 commit 之前（见 [TransportCursorCleaner]）。
 *
 * 投影不在这里重建（D07）：raw frame 是唯一证据，`ProjectionUpsert` 只服务有独立键替换表的族，
 * v1 的 approval/question 已经由 `pendingChanges` 落 `pending_request`，因此这里不重复写一份。
 */
class EventApplier(
    private val store: DurableStore,
    private val clock: Clock = SystemClock(),
    private val tuning: SyncTuning = SyncTuning.DEFAULT,
    private val historyUnsafeMarker: HistoryUnsafeMarker = HistoryUnsafeMarker.None,
    private val cursorCleaner: TransportCursorCleaner = TransportCursorCleaner.None,
) {

    suspend fun apply(
        identity: ConnectionIdentity,
        serverId: ServerId,
        batch: EventBatch,
        known: KnownSyncState = KnownSyncState.Empty,
        storedCursor: TransportCursor? = null,
        /**
         * WS 推送只提供“有新帧”的证据，不提供“没有漏帧”的证据：帧批次不得推进传输游标，
         * 否则一次丢帧会被永久跳过。poll 批次才带 since/latestSeq，由它唯一推进游标（D29）。
         */
        advanceCursor: Boolean = true,
        nowMs: Long = clock.wallClockMs(),
    ): ApplyOutcome {
        val plan = ResetPlanner.plan(
            identity = identity,
            serverId = serverId,
            batch = batch,
            storedCursor = storedCursor,
            preBatchKeys = known.pendingKeys,
            preBatchWatermark = known.watermarks,
        )

        val badJson = batch.frames.filterNot { isWellFormed(it.payloadJson) }
        val unsafeSessions = batch.frames
            .filter { EventClassification.tierOf(it.type, it.ignorable) == IgnoreTier.SURFACE }
            .mapTo(linkedSetOf()) { it.sessionId }
        val unknownEvents = batch.frames.count {
            EventClassification.tierOf(it.type, it.ignorable) != IgnoreTier.PROJECTED
        }

        val framesBySession = batch.frames.groupBy { it.sessionId }
        val touchedSessions = linkedSetOf<SessionId>().apply {
            addAll(framesBySession.keys)
            addAll(plan.sweeps.map { it.sessionId })
        }

        val sessionsAfter = linkedMapOf<SessionId, SessionSyncState>()
        val gaps = linkedMapOf<SessionId, GapObservation>()
        if (plan.reset) {
            seedResetSessions(batch, plan.sweeps.associateBy { it.sessionId }, framesBySession, touchedSessions, sessionsAfter)
        } else {
            applyIncremental(framesBySession, known, sessionsAfter, gaps)
        }

        val pendingEvents = pendingEventsOf(serverId, batch, plan.reset, known, framesBySession)
        val existingByIdentity = known.pending.associateBy { it.toIdentity() }
        val eventDelta = PendingReducer.reduceEvents(serverId, pendingEvents, existingByIdentity, nowMs, tuning)

        val changes = linkedMapOf<PendingIdentity, PendingRequest>()
        eventDelta.writes.forEach { changes[it.identity] = it.request }
        val sweepTargets = if (batch.baseline == null) emptyList() else plan.sweeps
        for (target in sweepTargets) {
            val rows = (known.pending + changes.values)
                .filter { it.serverId == serverId && it.sessionId == target.sessionId }
                .distinctBy { it.toIdentity() }
            // 阈值跳过由上层按 key 数判定（D12）；走到这里说明计划已决定执行 sweep。
            PendingReducer.onBaselineSweep(
                requests = rows,
                presentKeys = target.presentKeys,
                sweepSkipped = false,
                nowMs = nowMs,
                tuning = tuning,
            ).forEach { changes[it.toIdentity()] = it }
        }

        val pendingAfter = linkedMapOf<PendingIdentity, PendingRequest>().apply {
            known.pending.forEach { put(it.toIdentity(), it) }
            changes.forEach { (key, value) -> put(key, value) }
        }.values.toList()

        val newResync = linkedMapOf<SessionId, NeedsResync>()
        gaps.forEach { (sessionId, gap) ->
            val existing = known.sessions[sessionId]?.needsResync
            val carried = existing?.takeIf { !isCovered(sessionsAfter[sessionId]?.position, it) }
            val state = sessionsAfter.getValue(sessionId)
            sessionsAfter[sessionId] = state.copy(needsResync = carried)
            if (carried == null) {
                newResync[sessionId] = NeedsResync(
                    sessionId = sessionId,
                    reason = ResyncReason.SeqGap,
                    expectedSeq = gap.expectedSeq,
                    attempts = 0,
                    degraded = false,
                )
                sessionsAfter[sessionId] = sessionsAfter.getValue(sessionId).copy(needsResync = newResync[sessionId])
            }
        }

        if (unsafeSessions.isNotEmpty()) {
            unsafeSessions.forEach { historyUnsafeMarker.mark(serverId, it) }
        }

        val cursorAfter = when {
            !advanceCursor -> null
            else -> plan.cursorAfter ?: TransportCursor(identity, batch.channel, batch.latestSeq, batch.epoch)
        }
        val baselineSweep = sweepTargets.firstOrNull()?.let { first ->
            DurableStore.BaselineSweep(
                sessionId = first.sessionId,
                // 端口只有单个 sweep 槽位；present 的 key 无会话维度，合并是安全的。
                presentKeys = sweepTargets.flatMapTo(linkedSetOf()) { it.presentKeys },
            )
        }
        val tombstones = sweepTargets.flatMap { it.absentKeys }.distinct()

        val committed = DurableStore.ApplyBatch(
            identity = identity,
            serverId = serverId,
            frames = batch.frames,
            upserts = emptyList(),
            pendingChanges = changes.values.toList(),
            outboxEntries = emptyList(),
            cursorAfter = cursorAfter,
            positionsAfter = sessionsAfter.mapValues { it.value.position },
            islands = sessionsAfter.mapValues { it.value.islands },
            needsResync = newResync,
            baselineSweep = baselineSweep,
            tombstones = tombstones,
            diagnosticsDelta = DurableStore.DiagnosticsDelta(badJsonCount = badJson.size),
        )

        // 先清后写：清空的游标不会被同一次 commit 之外的任何东西观察；commit 失败只是重放。
        if (plan.reset) cursorCleaner.clear(identity)
        store.commit(committed)

        return ApplyOutcome(
            committed = committed,
            sessions = sessionsAfter,
            pending = pendingAfter,
            gaps = gaps.values.toList(),
            historyUnsafeSessions = unsafeSessions,
            badJsonCount = badJson.size,
            unknownEventCount = unknownEvents,
        )
    }

    /**
     * 普通增量：位置只前进；被 dropped 的帧（<= contiguous 或已被岛覆盖）不参与空洞判定，
     * 否则一串重放会各自报一个假洞。
     */
    private fun applyIncremental(
        framesBySession: Map<SessionId, List<TransportFrame>>,
        known: KnownSyncState,
        out: MutableMap<SessionId, SessionSyncState>,
        gaps: MutableMap<SessionId, GapObservation>,
    ) {
        framesBySession.forEach { (sessionId, frames) ->
            val prior = known.sessions[sessionId] ?: SessionSyncState()
            val applied = Positions.apply(prior.position, prior.islands, frames, tuning.islandMergeGap)
            applied.gap?.let { gaps[sessionId] = it }
            val trimmed = Islands.trimFrontier(applied.islands, applied.position.contiguousSeq)
            val carried = prior.needsResync?.takeIf { !isCovered(applied.position, it) }
            out[sessionId] = SessionSyncState(applied.position, trimmed, carried)
        }
    }

    /**
     * reset 把覆盖账本清零：旧岛描述的是**旧日志**，必须整体丢弃（islands 空列表即清空）。
     * 新 frontier 取本批证据的最大 seq（baseline 的 lastSeq 与帧 seq），没有证据的会话保持原状。
     * 重置后本批帧仍原样落 raw log，但不再算“新覆盖”——它们已经 <= 新 frontier。
     */
    private fun seedResetSessions(
        batch: EventBatch,
        targets: Map<SessionId, ai.deepseek.dsh.android.domain.ResetBaselineTarget>,
        framesBySession: Map<SessionId, List<TransportFrame>>,
        touched: Set<SessionId>,
        out: MutableMap<SessionId, SessionSyncState>,
    ) {
        touched.forEach { sessionId ->
            val frameHead = framesBySession[sessionId]?.maxOfOrNull { it.seq.value }?.let(::EventSeq)
            val baselineHead = targets[sessionId]?.lastSeq
            val head = listOfNotNull(frameHead, baselineHead).maxByOrNull { it.value } ?: return@forEach
            out[sessionId] = SessionSyncState(
                position = SessionPosition(head, head),
                islands = emptyList(),
                needsResync = null,
            )
        }
    }

    /**
     * 只把“真正新增的帧”交给状态机：重放已应用帧没有新信息，重跑 reducer 反而会在 TTL 边界
     * 复活本不该复活的 tombstone。reset 批次例外——它重播的是新日志的种子。
     */
    private fun pendingEventsOf(
        serverId: ServerId,
        batch: EventBatch,
        reset: Boolean,
        known: KnownSyncState,
        framesBySession: Map<SessionId, List<TransportFrame>>,
    ): List<PendingEvent> {
        val reducible = if (reset) {
            batch.frames.distinctBy { it.sessionId to it.seq.value }
        } else {
            framesBySession.flatMap { (sessionId, frames) ->
                val prior = known.sessions[sessionId] ?: SessionSyncState()
                val candidates = GapDetector.select(prior.position, prior.islands, frames).candidateSeqs.toSet()
                frames.filter { it.seq in candidates }
            }
        }
        return reducible.mapNotNull { frame ->
            if (!isWellFormed(frame.payloadJson)) return@mapNotNull null
            val event: DurableEvent = DurableEventCodec.decode(frame)
            if (PendingReducer.identityOf(serverId, event) == null) {
                null
            } else {
                PendingEvent(frame.sessionId, event, frame.payloadJson)
            }
        }
    }

    private fun isCovered(position: SessionPosition?, resync: NeedsResync): Boolean {
        val contiguous = position?.contiguousSeq ?: return false
        return contiguous.value >= resync.expectedSeq.value
    }

    /** `DshJson.spec` 是 D06 的唯一解析实例；坏 JSON 只计数并继续，绝不中断整批。 */
    private fun isWellFormed(payloadJson: String): Boolean =
        runCatching { DshJson.spec.parseToJsonElement(payloadJson) is JsonObject }.getOrDefault(false)
}
