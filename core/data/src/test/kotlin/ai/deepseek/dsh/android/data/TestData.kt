package ai.deepseek.dsh.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.database.toEntity
import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.DurableStore
import ai.deepseek.dsh.android.domain.FakeClock
import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.NeedsResync
import ai.deepseek.dsh.android.model.NotificationTag
import ai.deepseek.dsh.android.model.OutboxState
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.ProjectionFamily
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ResyncReason
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TransportCursor
import ai.deepseek.dsh.android.model.TransportFrame

/**
 * 测试数据装配：G06 的顺序与崩溃安全断言都从这里拿批次，避免每个用例重复拼 `ApplyBatch`。
 * 数字是测试 fixture（`check-source.sh` 的魔法数扫描不含 `src/test`）。
 */
internal object TestData {

    val identity = ConnectionIdentity("ab".repeat(32))
    val serverId = ServerId("srv-g06")
    val sessionId = SessionId("session-1")
    val otherSession = SessionId("session-2")
    val nowMs = 1_700_000_000_000L

    fun clock(startMs: Long = nowMs): FakeClock = FakeClock(startMs, startMs)

    fun room(): DshDatabase = DshDatabase.inMemoryForTest(application())

    fun application(): Context = ApplicationProvider.getApplicationContext()

    fun frame(seq: Long, type: String = "assistant/message", session: SessionId = sessionId): TransportFrame =
        TransportFrame(
            channel = ChannelKind.Mux,
            sessionId = session,
            seq = EventSeq(seq),
            transportSeq = EventSeq(seq * 10),
            type = type,
            ignorable = false,
            payloadJson = """{"seq":$seq,"type":"$type"}""",
            wallClockMs = nowMs + seq,
            replay = false,
        )

    fun cursor(sinceSeq: Long, channel: ChannelKind = ChannelKind.Mux, epoch: String? = "epoch-1") =
        TransportCursor(identity, channel, sinceSeq, epoch)

    fun position(contiguous: Long?, appliedMax: Long?) =
        SessionPosition(contiguous?.let { EventSeq(it) }, appliedMax?.let { EventSeq(it) })

    fun island(start: Long, end: Long) = AppliedIsland(EventSeq(start), EventSeq(end))

    fun pendingKey(kind: PendingKind = PendingKind.Approval, requestId: String = "req-1") =
        DurableStore.PendingKey(kind, RequestId(requestId))

    fun sweep(session: SessionId = sessionId, vararg present: DurableStore.PendingKey) =
        DurableStore.BaselineSweep(session, present.toSet())

    fun pendingRequest(
        kind: PendingKind = PendingKind.Approval,
        requestId: String = "req-1",
        status: PendingStatus = PendingStatus.Pending,
        session: SessionId = sessionId,
        anchorSeq: Long? = 1L,
    ): PendingRequest = PendingRequest(
        serverId = serverId,
        kind = kind,
        requestId = RequestId(requestId),
        sessionId = session,
        status = status,
        completeness = PendingCompleteness.Stale,
        payloadJson = "{}",
        firstSeenAtMs = nowMs - 10_000,
        createdAtMs = nowMs - 10_000,
        anchorSeq = anchorSeq?.let { EventSeq(it) },
        callId = CallId("call-$requestId"),
        toolName = if (kind == PendingKind.Question) "ask_user_question" else "bash",
    )

    fun outboxEntry(
        kind: PendingKind = PendingKind.Approval,
        requestId: String = "req-1",
        state: OutboxState = OutboxState.New,
    ): DurableStore.OutboxEntry = DurableStore.OutboxEntry(
        kind = kind,
        requestId = RequestId(requestId),
        tag = NotificationTag.of(identity, kind, RequestId(requestId)),
        state = state,
    )

    fun upsert(family: ProjectionFamily = ProjectionFamily.Approval, recordKey: String = "req-1") =
        DurableStore.ProjectionUpsert(
            sessionId = sessionId,
            family = family,
            recordKey = recordKey,
            asOfSeq = EventSeq(3L),
            payloadJson = "{}",
        )

    fun needsResync(
        session: SessionId = sessionId,
        expected: Long = 4L,
        reason: ResyncReason = ResyncReason.SeqGap,
        degraded: Boolean = false,
    ): NeedsResync = NeedsResync(session, reason, EventSeq(expected), attempts = 0, degraded = degraded)

    /** 六个段落都会写到的“全量批次”：顺序断言用它的记录 == [CommitSection.ordered]。 */
    fun fullBatch(
        cursorSeq: Long = 5L,
        frames: List<TransportFrame> = listOf(frame(1), frame(2), frame(3)),
        positions: Map<SessionId, SessionPosition> = mapOf(sessionId to position(3, 3)),
        islands: Map<SessionId, List<AppliedIsland>> = mapOf(sessionId to listOf(island(1, 3))),
        pendingChanges: List<PendingRequest> = listOf(pendingRequest()),
        outbox: List<DurableStore.OutboxEntry> = listOf(outboxEntry()),
        upserts: List<DurableStore.ProjectionUpsert> = listOf(upsert()),
        tombstones: List<DurableStore.PendingKey> = listOf(pendingKey(requestId = "gone-1")),
        sweep: DurableStore.BaselineSweep? = DurableStore.BaselineSweep(sessionId, setOf(pendingKey())),
        resync: Map<SessionId, NeedsResync> = emptyMap(),
        diagnostics: DurableStore.DiagnosticsDelta = DurableStore.DiagnosticsDelta(1, 1, 1),
    ): DurableStore.ApplyBatch = DurableStore.ApplyBatch(
        identity = identity,
        serverId = serverId,
        frames = frames,
        upserts = upserts,
        pendingChanges = pendingChanges,
        outboxEntries = outbox,
        cursorAfter = cursor(cursorSeq),
        positionsAfter = positions,
        islands = islands,
        needsResync = resync,
        baselineSweep = sweep,
        tombstones = tombstones,
        diagnosticsDelta = diagnostics,
    )

    fun seedPending(db: DshDatabase, vararg requests: PendingRequest) {
        kotlinx.coroutines.runBlocking {
            db.pendingDao().putPending(requests.map { it.toEntity(asOfSeq = 1L) })
        }
    }

    fun seedIslands(db: DshDatabase, session: SessionId = sessionId, spans: List<AppliedIsland>) {
        kotlinx.coroutines.runBlocking {
            db.islandDao().replaceIslands(serverId.value, session.value, spans)
        }
    }
}
