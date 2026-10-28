package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DiagnosticsCounters
import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.database.IslandDao
import ai.deepseek.dsh.android.database.OutboxDao
import ai.deepseek.dsh.android.database.PendingDao
import ai.deepseek.dsh.android.database.SessionEventDao
import ai.deepseek.dsh.android.database.SessionHeadDao
import ai.deepseek.dsh.android.database.pendingSweepKey
import ai.deepseek.dsh.android.database.toEntity
import ai.deepseek.dsh.android.database.toHeadEntity
import ai.deepseek.dsh.android.database.outboxEntry
import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.DurableStore
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.NeedsResync
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.ProjectionFamily
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TransportCursor
import androidx.room.withTransaction

/**
 * `DurableStore` 的 Room 实现（G06 的写侧唯一入口）。
 *
 * 一个 [DurableStore.ApplyBatch] = **一个** Room 事务，段落顺序固定并由 [CommitSection.ordered] 表达：
 * `tombstone → baseline → raw frame → projection → outbox → cursor`，游标永远最后写。
 * 中途任何异常（[CommitFault] 注入或真实崩溃）都会让事务整体回滚，因此游标不会前进，
 * 重启后同一批会被重放：幂等由各 DAO 的守卫（`asOfSeq`、`appliedMaxSeq`、`ON CONFLICT`）保证。
 *
 * 本类**不做**任何区间合并或空洞判定（D26）：`islands` 与 `positionsAfter` 由调用方
 * （[EventApplier]）用 `:core:domain` 的 `Islands` / `Positions` 算好后整体交付，这里只落库。
 */
class RoomDurableStore(
    private val db: DshDatabase,
    private val clock: Clock,
    private val journal: WriteJournal = WriteJournal.None,
    private val fault: CommitFault = CommitFault.None,
    private val projectionSink: ProjectionSink = ProjectionSink.None,
) : DurableStore {

    private val events: SessionEventDao get() = db.sessionEventDao()
    private val heads: SessionHeadDao get() = db.sessionHeadDao()
    private val islands: IslandDao get() = db.islandDao()
    private val outbox: OutboxDao get() = db.outboxDao()
    private val pending: PendingDao get() = db.pendingDao()
    private val diagnostics get() = db.diagnosticsDao()

    override suspend fun commit(batch: DurableStore.ApplyBatch) {
        val nowMs = clock.wallClockMs()
        val tombstones = batch.tombstones
        val sweep = batch.baselineSweep
        val outboxEntries = batch.outboxEntries
        val cursor = batch.cursorAfter
        (journal as? RecordingWriteJournal)?.beginCommit()
        db.withTransaction {
            // 1) tombstone：基线缺席的 key 先标记，后续投影不得再把它当在途请求（G06 reset 第一步）。
            writeSection(CommitSection.Tombstones) {
                tombstones.forEach { key ->
                    pending.tombstone(
                        serverId = batch.serverId.value,
                        kind = key.kind,
                        requestId = key.requestId,
                        completeness = PendingCompleteness.Stale,
                        nowMs = nowMs,
                    )
                }
            }

            // 2) baseline：本次确认到的 key 打世代标记并清 tombstone（absent 半边已由 tombstones 表达）。
            writeSection(CommitSection.Baseline) {
                val keys = sweep?.presentKeys.orEmpty().map { pendingSweepKey(it.kind, it.requestId) }
                if (keys.isNotEmpty()) {
                    pending.markPresent(serverId = batch.serverId.value, keys = keys, sweepAtMs = nowMs)
                }
            }

            // 3) 原始帧：先落 raw log，投影的一切证据都来自它（D07）。
            writeSection(CommitSection.Frames) {
                if (batch.frames.isNotEmpty()) {
                    events.upsertEvents(batch.frames.map { it.toEntity(batch.serverId, nowMs) })
                }
            }

            // 4) 投影：head 位置 + islands 整体替换 + pending 行 + resync + 诊断增量 + 关键替换族。
            writeSection(CommitSection.Projection) {
                batch.positionsAfter.forEach { (sessionId, position) ->
                    writePosition(batch.serverId, sessionId, position, batch.needsResync[sessionId], nowMs)
                    // D26：区间已由 Islands 合并，这里只做整体替换（空列表 = 清空，reset 场景）。
                    batch.islands[sessionId]?.let { spans ->
                        islands.replaceIslands(batch.serverId.value, sessionId.value, spans)
                    }
                    clearCoveredResync(batch.serverId, sessionId, position, raisedInThisBatch = sessionId in batch.needsResync)
                }
                if (batch.pendingChanges.isNotEmpty()) {
                    pending.applyPendingChanges(
                        batch.pendingChanges.map { request -> request.toEntity(asOfSeqOf(batch, request.sessionId)) },
                    )
                }
                if (batch.needsResync.isNotEmpty()) {
                    db.resyncDao().putResync(batch.needsResync.values.map { it.toEntity(batch.serverId, nowMs) })
                }
                applyDiagnostics(batch.diagnosticsDelta, nowMs)
                applyProjectionUpserts(batch)
            }

            // 5) outbox：通知外发期望（D28），与游标同事务。
            writeSection(CommitSection.Outbox) {
                outboxEntries.forEach { entry ->
                    outbox.insertOutbox(
                        listOf(
                            outboxEntry(
                                identityHash = batch.identity.digest,
                                kind = entry.kind,
                                requestId = entry.requestId,
                                notificationTag = entry.tag,
                                state = entry.state,
                                nowMs = nowMs,
                            ),
                        ),
                    )
                }
            }

            // 6) 游标：永远最后一段（G06）。
            writeSection(CommitSection.Cursor) {
                if (cursor != null) writeCursor(cursor, nowMs, force = tombstones.isNotEmpty())
            }
        }
    }

    /** D08 的单向标记：未知且非可忽略的事件让该会话历史不可重建（事务外的独立写，见类注释的端口缺口）。 */
    suspend fun markHistoryUnsafe(serverId: ServerId, sessionId: SessionId, incomplete: Boolean) {
        db.withTransaction { heads.markUnsafe(serverId.value, sessionId.value, incomplete) }
    }

    /** reset 之后位置可以回退，普通同步不行：回退用无条件覆盖，前进用守卫 upsert（G06）。 */
    private suspend fun writePosition(
        serverId: ServerId,
        sessionId: SessionId,
        position: SessionPosition,
        resync: NeedsResync?,
        nowMs: Long,
    ) {
        val existing = heads.head(serverId.value, sessionId.value)
        val head = position.toHeadEntity(
            serverId = serverId,
            sessionId = sessionId,
            lastAppliedAtMs = nowMs,
            // 单向标记不能被一次普通同步顺手抹掉（advanceHeadRow 的 SET 列表不含 historyUnsafe）。
            historyUnsafe = existing?.historyUnsafe ?: false,
            historyIncomplete = (existing?.historyIncomplete ?: false) || (resync?.degraded == true),
            prunedBelowSeq = existing?.prunedBelowSeq,
        )
        val rewound = existing != null && position.appliedMaxSeq.isBelow(existing.appliedMaxSeq)
        if (rewound) heads.putHeads(listOf(head)) else heads.upsertSessionHeads(listOf(head))
    }

    /** cursor 段落本身：只有 epoch 变化、reset 回退或 tombstone 批次才允许覆盖（D29：外层 seq 是传输游标）。 */
    private suspend fun writeCursor(cursor: TransportCursor, nowMs: Long, force: Boolean) {
        val dao = db.syncStateDao()
        val existing = dao.cursor(cursor.identity.digest, cursor.channel.value)
        val overwrite = force ||
            existing == null ||
            existing.epoch != cursor.epoch ||
            cursor.sinceSeq < existing.seq
        val row = cursor.toEntity(nowMs)
        if (overwrite) dao.putCursors(listOf(row)) else dao.advanceCursor(listOf(row))
    }

    /** needsResync 的退出：连续位置已经覆盖 expectedSeq 就清除（degraded 行由 attempts 决定，不在此处）。 */
    private suspend fun clearCoveredResync(
        serverId: ServerId,
        sessionId: SessionId,
        position: SessionPosition,
        raisedInThisBatch: Boolean,
    ) {
        if (raisedInThisBatch) return
        val contiguous = position.contiguousSeq ?: return
        val row = db.resyncDao().resync(serverId.value, sessionId.value) ?: return
        if (row.expectedSeq.value <= contiguous.value) {
            db.resyncDao().clearResync(serverId.value, sessionId.value)
        }
    }

    private fun asOfSeqOf(batch: DurableStore.ApplyBatch, sessionId: SessionId): Long =
        batch.positionsAfter[sessionId]?.appliedMaxSeq?.value ?: 0L

    private suspend fun applyDiagnostics(delta: DurableStore.DiagnosticsDelta, nowMs: Long) {
        if (delta.badJsonCount != 0) {
            diagnostics.incrementCounter(DiagnosticsCounters.BAD_JSON, delta.badJsonCount.toLong(), nowMs)
        }
        if (delta.wsDowngradeCount != 0) {
            diagnostics.incrementCounter(DiagnosticsCounters.WS_DOWNGRADE, delta.wsDowngradeCount.toLong(), nowMs)
        }
        if (delta.retentionTrimmedCount != 0) {
            diagnostics.incrementCounter(
                DiagnosticsCounters.RETENTION_TRIMMED,
                delta.retentionTrimmedCount.toLong(),
                nowMs,
            )
        }
    }

    /**
     * `ProjectionFamily` 的落地面：Approval/Question 已经由 `pendingChanges` 进 `pending_request`，
     * Session 由 `session_head`/`session` 承载；Queue/Jobs/Reasoning 在 v1 schema 里没有键替换表，
     * 它们按 D07 从原始帧现场投影，这里把族与条数交给 [ProjectionSink] 记账，不静默丢弃。
     */
    private fun applyProjectionUpserts(batch: DurableStore.ApplyBatch) {
        if (batch.upserts.isEmpty()) return
        projectionSink.consume(batch.upserts.groupingBy { it.family }.eachCount())
    }

    private suspend fun writeSection(section: CommitSection, block: suspend () -> Unit) {
        fault.before(section)
        journal.section(section)
        block()
    }

    private fun EventSeq?.isBelow(other: EventSeq?): Boolean =
        this != null && other != null && value < other.value

    companion object {
        /** 只有这两个族在 v1 schema 里有关键替换表（其余走原始帧重投影）。 */
        val tabledFamilies: Set<ProjectionFamily> = setOf(ProjectionFamily.Approval, ProjectionFamily.Question)

        /** outbox 的取值域（D28：只承载审批与提问的通知期望）。 */
        val outboxKinds: Set<PendingKind> = PendingKind.entries.toSet()
    }
}
