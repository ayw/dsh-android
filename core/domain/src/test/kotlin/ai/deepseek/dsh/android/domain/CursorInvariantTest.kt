package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.IdentityDigest
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TransportCursor
import ai.deepseek.dsh.android.model.TransportFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val cursorIdentity = ConnectionIdentity(IdentityDigest.of("https://gw.example.test/base", "token-cursor"))
private val cursorServer = ServerId("srv-cursor")
private val cursorSession = SessionId("s-cursor")
private val cursorChannel = ChannelKind.Mux
private val cursorMergeGap = SyncTuning.DEFAULT.islandMergeGap

private fun cursorSeq(value: Long): EventSeq = EventSeq(value)

private fun cursorPosition(contiguous: Long?, appliedMax: Long?): SessionPosition = SessionPosition(
    contiguousSeq = contiguous?.let(::cursorSeq),
    appliedMaxSeq = appliedMax?.let(::cursorSeq),
)

/** mux 帧：外层传输序号与内层会话序号是两个独立空间（D29），这里显式让它们不等。 */
private fun cursorFrame(sessionSeq: Long, transportSeq: Long): TransportFrame = TransportFrame(
    channel = cursorChannel,
    sessionId = cursorSession,
    seq = cursorSeq(sessionSeq),
    transportSeq = cursorSeq(transportSeq),
    type = "assistant/message",
    ignorable = false,
    payloadJson = "{}",
    wallClockMs = sessionSeq,
    replay = false,
)

private fun cursorBatch(
    latestSeq: Long,
    frames: List<TransportFrame>,
    reset: Boolean = true,
    since: Long = 30,
): EventBatch = EventBatch(
    channel = cursorChannel,
    epoch = "e2",
    since = since,
    latestSeq = latestSeq,
    truncated = false,
    reset = reset,
    waitSupported = true,
    baseline = null,
    frames = frames,
)

private fun cursorStoredCursor(sinceSeq: Long = 3, epoch: String? = "e1"): TransportCursor =
    TransportCursor(cursorIdentity, cursorChannel, sinceSeq, epoch)

private fun cursorPlan(batch: EventBatch, stored: TransportCursor? = cursorStoredCursor()): ResetPlan =
    ResetPlanner.plan(
        identity = cursorIdentity,
        serverId = cursorServer,
        batch = batch,
        storedCursor = stored,
        preBatchKeys = emptyMap(),
    )

/**
 * D29 的两套序号空间：`TransportCursor.sinceSeq` 只推进传输游标（poll 增量、gap/重放），
 * `SessionPosition.contiguousSeq/appliedMaxSeq` 只来自内层 `payload.event.seq`。
 * 本类只钉“两者不得互换”，不重复 [ResetPlannerTest]/[PositionsTest] 的触发判定。
 */
class CursorInvariantTest {

    @Test
    fun cursorTransportAndSessionSeqSpacesStayDisjoint() {
        // 外层传输序号 4，内层会话序号 950：同一帧的两层信封。
        val frame = cursorFrame(sessionSeq = 950, transportSeq = 4)
        val plan = cursorPlan(cursorBatch(latestSeq = 4, frames = listOf(frame)))

        assertEquals(4L, plan.cursorAfter?.sinceSeq)
        assertEquals(cursorSeq(950), plan.headSeq)

        val applied = Positions.applySeqs(cursorPosition(949, 949), emptyList(), plan.frames.map { it.seq }, cursorMergeGap)
        assertEquals(cursorSeq(950), applied.position.contiguousSeq)
        assertEquals(cursorSeq(951), applied.position.nextExpected)
        assertNotEquals(cursorSeq(4), applied.position.contiguousSeq)
    }

    @Test
    fun cursorResetPlanRewindsTheTransportCursorNotTheSessionPosition() {
        val stored = cursorStoredCursor(sinceSeq = 900, epoch = "e1")
        val plan = cursorPlan(
            cursorBatch(latestSeq = 120, frames = listOf(cursorFrame(950, 1), cursorFrame(951, 2))),
            stored = stored,
        )

        assertTrue(plan.reset)
        assertEquals(ResetStep.ClearTransportCursor, plan.steps.first())
        assertEquals(ResetStep.AdvanceCursor, plan.steps.last())
        // 传输游标被重指到网关给的 latestSeq（可以比旧游标小 = 回退）。
        assertEquals(120L, plan.cursorAfter?.sinceSeq)
        assertTrue(plan.cursorAfter!!.sinceSeq < stored.sinceSeq)
        assertEquals("e2", plan.cursorAfter?.epoch)

        // 会话位置从会话帧重建，绝不取 120 这个传输序号。
        val applied = Positions.applySeqs(cursorPosition(949, 949), emptyList(), plan.frames.map { it.seq }, cursorMergeGap)
        assertEquals(cursorSeq(951), applied.position.contiguousSeq)
        assertEquals(cursorSeq(951), applied.position.appliedMaxSeq)
        assertNotEquals(cursorSeq(120), applied.position.contiguousSeq)
        // reset 计划里会话 head 取会话证据，不因传输游标回退而变小。
        assertEquals(cursorSeq(951), plan.headSeq)
    }

    @Test
    fun cursorNextExpectedIsAlwaysContiguousPlusOne() {
        assertEquals(cursorSeq(101), cursorPosition(100, 950).nextExpected)
        assertEquals(cursorSeq(41), cursorPosition(40, 40).nextExpected)
        assertNull(cursorPosition(null, null).nextExpected)
        assertNull(cursorPosition(null, 900).nextExpected)

        // appliedMaxSeq 领先不改变 nextExpected：快照尾部可以到 950 而连续位置仍是 100。
        val ahead = Positions.applySeqs(cursorPosition(100, 100), emptyList(), listOf(cursorSeq(950)), cursorMergeGap)
        assertEquals(cursorSeq(100), ahead.position.contiguousSeq)
        assertEquals(cursorSeq(950), ahead.position.appliedMaxSeq)
        assertEquals(cursorSeq(101), ahead.position.nextExpected)
    }

    @Test
    fun cursorTransportSeqNeverSeedsTheSessionPosition() {
        val frame = cursorFrame(sessionSeq = 310, transportSeq = 5)
        val plan = cursorPlan(cursorBatch(latestSeq = 5, frames = listOf(frame)))

        val applied = Positions.applySeqs(cursorPosition(null, null), emptyList(), listOf(frame.seq), cursorMergeGap)
        assertEquals(cursorSeq(310), applied.position.contiguousSeq)
        assertNotEquals(cursorSeq(5), applied.position.contiguousSeq)
        assertNotEquals(cursorSeq(5), applied.position.appliedMaxSeq)
        // 同一批的传输游标仍按外层序号推进。
        assertEquals(5L, plan.cursorAfter?.sinceSeq)
        assertEquals(cursorSeq(310), plan.headSeq)
    }

    @Test
    fun cursorGapDetectorReportsMissingSeqAndStopsContiguousBeforeIt() {
        val position = cursorPosition(100, 100)
        val frames = listOf(cursorFrame(103, 103))

        assertEquals(GapObservation(cursorSeq(101), cursorSeq(103)), GapDetector.observe(position, emptyList(), frames))

        val applied = Positions.apply(position, emptyList(), frames, cursorMergeGap)
        assertEquals(GapObservation(cursorSeq(101), cursorSeq(103)), applied.gap)
        assertEquals(cursorSeq(100), applied.position.contiguousSeq)
        assertEquals(cursorSeq(103), applied.position.appliedMaxSeq)
        assertEquals(cursorSeq(101), applied.position.nextExpected)
    }

    @Test
    fun cursorGapDetectorCollapsesDuplicateSeqsInsideOneBatch() {
        val position = cursorPosition(100, 100)
        val frames = listOf(cursorFrame(101, 1), cursorFrame(101, 2), cursorFrame(102, 3))

        val selection = GapDetector.select(position, emptyList(), frames)
        assertEquals(listOf(cursorSeq(101), cursorSeq(102)), selection.candidateSeqs)
        assertTrue(selection.droppedSeqs.isEmpty())
        assertNull(GapDetector.observe(position, emptyList(), frames))

        val applied = Positions.apply(position, emptyList(), frames, cursorMergeGap)
        assertNull(applied.gap)
        assertEquals(cursorSeq(102), applied.position.contiguousSeq)
        assertEquals(listOf(cursorSeq(101), cursorSeq(102)), applied.appliedSeqs)
    }

    @Test
    fun cursorGapDetectorDropsAlreadyAppliedSeqInsteadOfCallingItAGap() {
        val position = cursorPosition(100, 100)
        val frames = listOf(cursorFrame(100, 1), cursorFrame(101, 2))

        val selection = GapDetector.select(position, emptyList(), frames)
        assertEquals(listOf(cursorSeq(101)), selection.candidateSeqs)
        assertEquals(listOf(cursorSeq(100)), selection.droppedSeqs)
        assertNull(GapDetector.observe(position, emptyList(), frames))
    }

    @Test
    fun cursorGapDetectorSortsOutOfOrderSeqsBeforeTheGapCheck() {
        val position = cursorPosition(100, 100)
        val frames = listOf(cursorFrame(103, 1), cursorFrame(101, 2), cursorFrame(102, 3))

        val selection = GapDetector.select(position, emptyList(), frames)
        assertEquals(listOf(cursorSeq(101), cursorSeq(102), cursorSeq(103)), selection.candidateSeqs)
        assertNull(GapDetector.observe(position, emptyList(), frames))

        val applied = Positions.apply(position, emptyList(), frames, cursorMergeGap)
        assertNull(applied.gap)
        assertEquals(cursorSeq(103), applied.position.contiguousSeq)
    }

    @Test
    fun cursorIncrementalBatchKeepsCursorOutOfTheSessionStepList() {
        val plan = cursorPlan(
            cursorBatch(latestSeq = 42, frames = listOf(cursorFrame(950, 42)), reset = false),
            // 同 epoch 才是“不触发 reset”的增量批次；epoch 变化本身就走 reset 路径，那由 ResetPlannerTest 钉。
            stored = cursorStoredCursor(sinceSeq = 30, epoch = "e2"),
        )
        assertEquals(false, plan.reset)
        assertNull(plan.cursorAfter)
        assertTrue(plan.steps.none { it == ResetStep.ClearTransportCursor || it == ResetStep.AdvanceCursor })
        assertEquals(cursorSeq(950), plan.headSeq)
    }
}
