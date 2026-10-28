package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.database.toFrame
import ai.deepseek.dsh.android.database.toModel
import ai.deepseek.dsh.android.domain.GapDetector
import ai.deepseek.dsh.android.domain.PendingStore
import ai.deepseek.dsh.android.domain.Projector
import ai.deepseek.dsh.android.domain.repository.HistoryWindow
import ai.deepseek.dsh.android.domain.repository.TimelineGapState
import ai.deepseek.dsh.android.domain.repository.TimelineRepository
import ai.deepseek.dsh.android.domain.repository.domainCall
import ai.deepseek.dsh.android.model.DurableEvent
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.NeedsResync
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TimelineSnapshot
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.protocol.DurableEventCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** 已持久化帧的只读面。用 [TransportFrame] 而不是 Room 实体，测试与投影都不依赖数据库类型。 */
interface TimelineEventSource {
    /** 会话全部已存帧（顺序由实现决定，投影器会按 seq 去重排序）。 */
    fun observeEvents(sessionId: SessionId): Flow<List<TransportFrame>>

    /** keyset：`seq < beforeSeq`（exclusive），从新到旧取至多 [limit] 行。 */
    suspend fun eventsBefore(sessionId: SessionId, beforeSeq: EventSeq?, limit: Int): List<TransportFrame>
}

/** 未被覆盖的 resync 请求；`null` 表示该会话当前没有 needsResync 行。 */
interface ResyncSource {
    fun observeResync(sessionId: SessionId): Flow<NeedsResync?>
}

/**
 * [TimelineRepository] 的 Room 实现。投影只有 [Projector] 一份实现：本类只做“读帧 → 解码 → 投影”，
 * 不在读侧重复任何消息/工具/空洞规则。
 *
 * `SessionEventDao` 没有“按会话无界观察”的查询，`observeEvents` 因此用
 * `observeEventsBefore(..., limit = Int.MAX_VALUE)` 覆盖全部已存帧；保留裁剪已经把单会话行数封顶
 * （`SyncTuning.maxEventsPerSession`），所以这不是无界内存风险。
 */
class RoomTimelineRepository(
    private val serverId: ServerId,
    private val events: TimelineEventSource,
    private val heads: SessionHeadSource,
    private val resync: ResyncSource,
    private val pending: PendingStore,
    private val projector: Projector = Projector(),
) : TimelineRepository {

    override fun snapshot(sessionId: SessionId): Flow<TimelineSnapshot> =
        combine(
            events.observeEvents(sessionId),
            pending.observeForSession(serverId, sessionId),
            heads.observeHead(sessionId),
        ) { frames, pendingRows, head ->
            projector.project(
                sessionId = sessionId,
                events = frames.map(DurableEventCodec::decode),
                pending = pendingRows,
                position = head?.toPosition() ?: SessionPosition(contiguousSeq = null, appliedMaxSeq = null),
            )
        }

    override suspend fun historyPage(
        sessionId: SessionId,
        beforeSeq: EventSeq?,
        maxMessages: Int,
    ): Result<HistoryWindow> = domainCall {
        val limit = maxMessages.coerceAtLeast(1)
        // 多取一行只用于判断 hasMore，不回传、不落库；传输游标只在写侧推进，这里绝不触碰。
        val fetched = events.eventsBefore(sessionId, beforeSeq, limit + 1)
        val pageDesc = fetched.take(limit)
        val hasMore = fetched.size > limit
        val ordered = pageDesc.sortedBy { it.seq.value }
        // 历史页是窗口而不是全量：用空位置投影，避免把“窗口之前的帧”误报成空洞；
        // 页内空洞仍由投影器按相邻 seq 检出。pending 锚点属于实时快照，历史页不重复附带。
        val window = projector.project(
            sessionId = sessionId,
            events = ordered.map(DurableEventCodec::decode),
            pending = emptyList(),
            position = SessionPosition(contiguousSeq = null, appliedMaxSeq = null),
        )
        HistoryWindow(
            sessionId = sessionId,
            items = window.items,
            // 下一页从本页最小 seq 之前继续；没有更旧的行时显式 null，而不是留一个悬空游标。
            nextBeforeSeq = if (hasMore) ordered.minByOrNull { it.seq.value }?.seq else null,
            hasMore = hasMore,
        )
    }

    override fun gapState(sessionId: SessionId): Flow<TimelineGapState> =
        combine(
            events.observeEvents(sessionId),
            heads.observeHead(sessionId),
            resync.observeResync(sessionId),
        ) { frames, head, needsResync ->
            when {
                // D08 是硬边界，优先于一切“还能修”的状态。
                head?.historyUnsafe == true ->
                    TimelineGapState.HistoryUnsafe(firstUnsafeType(frames))

                head?.historyIncomplete == true ||
                    head?.prunedBelowSeq != null ||
                    needsResync?.degraded == true -> TimelineGapState.HistoryIncomplete

                else -> gapOrCovered(head, frames)
            }
        }

    /**
     * 空洞判定复用 [GapDetector] 的唯一条件（`seq > contiguousSeq + 1`）。
     * 找不到“已到达的最小 seq”但 `appliedMaxSeq` 已经跑在前面，说明帧被保留策略裁掉了：
     * 这属于历史不完整，而不是“完全覆盖”。
     */
    private fun gapOrCovered(head: SessionHeadSnapshot?, frames: List<TransportFrame>): TimelineGapState {
        val position = head?.toPosition() ?: return TimelineGapState.FullyCovered
        val contiguous = position.contiguousSeq ?: return TimelineGapState.FullyCovered
        val arrived = frames
            .map { it.seq }
            .filter { it.value > contiguous.value }
            .minByOrNull { it.value }
        if (arrived == null) {
            val maxSeq = position.appliedMaxSeq
            return if (maxSeq != null && maxSeq.value > contiguous.value) {
                TimelineGapState.HistoryIncomplete
            } else {
                TimelineGapState.FullyCovered
            }
        }
        return if (GapDetector.isGap(position, arrived)) {
            TimelineGapState.Missing(expectedSeq = EventSeq(contiguous.value + 1), arrivedSeq = arrived)
        } else {
            TimelineGapState.FullyCovered
        }
    }

    /** head 只有布尔标记，没有存未知类型；从原始帧里找第一条非可忽略的未知事件补回类型。 */
    private fun firstUnsafeType(frames: List<TransportFrame>): String =
        frames.asSequence()
            .map(DurableEventCodec::decode)
            .filterIsInstance<DurableEvent.Unknown>()
            .firstOrNull { !it.ignorable }
            ?.type
            ?: UNRECORDED_UNKNOWN_TYPE

    private companion object {
        /** 标记在、但对应原始帧已被裁剪时的诚实占位：不猜具体事件类型。 */
        const val UNRECORDED_UNKNOWN_TYPE = "unrecorded"
    }
}

/** [TimelineEventSource] 的 Room 实现。 */
class RoomTimelineEventSource(
    db: DshDatabase,
    serverId: ServerId,
) : TimelineEventSource {

    private val events = db.sessionEventDao()
    private val serverIdValue = serverId.value

    override fun observeEvents(sessionId: SessionId): Flow<List<TransportFrame>> =
        events.observeEventsBefore(serverIdValue, sessionId.value, beforeSeq = null, limit = ALL_ROWS)
            .map { rows -> rows.map { it.toFrame() } }

    override suspend fun eventsBefore(
        sessionId: SessionId,
        beforeSeq: EventSeq?,
        limit: Int,
    ): List<TransportFrame> =
        events.eventsBefore(serverIdValue, sessionId.value, beforeSeq?.value, limit).map { it.toFrame() }

    private companion object {
        /** `LIMIT` 在 SQLite 接受大整数；语义是“全部已存帧”，行数另由保留裁剪封顶。 */
        const val ALL_ROWS = Int.MAX_VALUE
    }
}

/** [ResyncSource] 的 Room 实现：DAO 只提供按 server 的列表，这里按会话挑出唯一一行。 */
class RoomResyncSource(
    db: DshDatabase,
    serverId: ServerId,
) : ResyncSource {

    private val resyncDao = db.resyncDao()
    private val serverIdValue = serverId.value

    override fun observeResync(sessionId: SessionId): Flow<NeedsResync?> =
        resyncDao.observeResync(serverIdValue)
            .map { rows -> rows.firstOrNull { it.sessionId == sessionId }?.toModel() }
}
