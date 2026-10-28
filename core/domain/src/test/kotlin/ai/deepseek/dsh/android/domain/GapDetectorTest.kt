package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TransportFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val gapTuning = SyncTuning.DEFAULT
private val gapMergeDistance = gapTuning.islandMergeGap
private val gapSession = SessionId("s-gap")

private fun gapIsland(start: Long, end: Long): AppliedIsland = AppliedIsland(EventSeq(start), EventSeq(end))

private fun gapPosition(contiguous: Long?, appliedMax: Long?): SessionPosition = SessionPosition(
    contiguousSeq = if (contiguous == null) null else EventSeq(contiguous),
    appliedMaxSeq = if (appliedMax == null) null else EventSeq(appliedMax),
)

private fun gapFrame(value: Long, replay: Boolean = false): TransportFrame = TransportFrame(
    channel = ChannelKind.Mux,
    sessionId = gapSession,
    seq = EventSeq(value),
    type = "assistant/message",
    ignorable = false,
    payloadJson = "{}",
    wallClockMs = value,
    replay = replay,
)

private fun gapFrames(vararg values: Long, replay: Boolean = false): List<TransportFrame> =
    values.map { gapFrame(it, replay) }

class GapDetectorTest {

    @Test
    fun sessionCursorGapRequiresSeqStrictlyBeyondContiguousPlusOneGapReset() {
        val adjacent = GapDetector.observe(gapPosition(100, 100), listOf(gapIsland(100, 100)), gapFrames(101))
        val beyond = GapDetector.observe(gapPosition(100, 100), listOf(gapIsland(100, 100)), gapFrames(102))
        assertNull(adjacent)
        assertEquals(GapObservation(EventSeq(101), EventSeq(102)), beyond)
    }

    @Test
    fun sessionCursorReplayedFrameBelowWatermarkIsDroppedNotAGapGapReset() {
        val selection = GapDetector.select(gapPosition(100, 100), emptyList(), gapFrames(90, replay = true) + gapFrames(101))
        assertEquals(listOf(EventSeq(101)), selection.candidateSeqs)
        assertEquals(listOf(EventSeq(90)), selection.droppedSeqs)
        assertNull(GapDetector.observe(gapPosition(100, 100), emptyList(), gapFrames(90, replay = true)))
    }

    @Test
    fun sessionCursorLedgerCoveredFramesAreDedupedBeforeGapDetectionGapReset() {
        val position = gapPosition(100, 110)
        val ledger = listOf(gapIsland(101, 110))
        val selection = GapDetector.select(position, ledger, gapFrames(105, 106, 107))
        assertTrue(selection.candidateSeqs.isEmpty())
        assertEquals(listOf(EventSeq(105), EventSeq(106), EventSeq(107)), selection.droppedSeqs)
        assertNull(GapDetector.observe(position, ledger, gapFrames(105, 106, 107)))
    }

    @Test
    fun sessionCursorOneGapPerBatchHoweverManyAheadFramesGapReset() {
        val gap = GapDetector.observe(gapPosition(100, 100), emptyList(), gapFrames(200, 300, 400))
        assertEquals(GapObservation(EventSeq(101), EventSeq(200)), gap)
        val applied = Positions.apply(gapPosition(100, 100), emptyList(), gapFrames(200, 300, 400), gapMergeDistance)
        assertEquals(3, applied.islands.size)
        assertEquals(gap, applied.gap)
    }

    @Test
    fun sessionCursorSnapshotTailRecordsLedgerWithoutOpeningNewGapGapReset() {
        val frames = (900L..950L).map { gapFrame(it) }
        val applied = Positions.apply(gapPosition(100, 100), emptyList(), frames, gapMergeDistance)
        assertEquals(EventSeq(100), applied.position.contiguousSeq)
        assertEquals(EventSeq(950), applied.position.appliedMaxSeq)
        assertNotNull(applied.gap)
        assertEquals(EventSeq(101), assertNotNull(applied.gap).expectedSeq)
    }

    @Test
    fun sessionCursorBaselineBackfillBehindLedgerNeverBecomesAGapGapReset() {
        val ahead = Positions.apply(gapPosition(100, 100), emptyList(), gapFrames(150), gapMergeDistance)
        val backfilled = Positions.apply(
            position = ahead.position,
            islands = ahead.islands,
            frames = (101L..149L).map { gapFrame(it) },
            mergeGap = gapMergeDistance,
        )
        assertNull(backfilled.gap)
        assertEquals(EventSeq(150), backfilled.position.contiguousSeq)
    }

    @Test
    fun sessionCursorWithoutWatermarkNoGapCanBeObservedGapReset() {
        assertNull(GapDetector.observe(gapPosition(null, null), emptyList(), gapFrames(500, 501)))
        assertEquals(listOf(EventSeq(500), EventSeq(501)), GapDetector.candidateSeqs(gapPosition(null, null), emptyList(), gapFrames(500, 501)))
    }

    @Test
    fun sessionCursorUnorderedFramesAreSortedBeforeSelectionGapReset() {
        val selection = GapDetector.select(gapPosition(100, 100), emptyList(), gapFrames(103, 101, 102))
        assertEquals(listOf(EventSeq(101), EventSeq(102), EventSeq(103)), selection.candidateSeqs)
        val applied = Positions.apply(gapPosition(100, 100), listOf(gapIsland(100, 100)), gapFrames(103, 101, 102), gapMergeDistance)
        assertNull(applied.gap)
        assertEquals(EventSeq(103), applied.position.contiguousSeq)
    }

    @Test
    fun sessionCursorDuplicateSeqsInsideOneBatchCountOnceGapReset() {
        val selection = GapDetector.select(gapPosition(100, 100), emptyList(), gapFrames(101, 101, 102, 102))
        assertEquals(listOf(EventSeq(101), EventSeq(102)), selection.candidateSeqs)
        assertTrue(selection.droppedSeqs.isEmpty())
    }

    @Test
    fun sessionCursorGapDetectorSharesTheWatermarkConditionGapReset() {
        val position = gapPosition(100, 950)
        assertTrue(GapDetector.isGap(position, EventSeq(102)))
        assertTrue(Positions.isGap(position, EventSeq(102)))
        val ahead = gapFrames(102)
        assertEquals(GapDetector.observe(position, emptyList(), ahead), Positions.apply(position, emptyList(), ahead, gapMergeDistance).gap)
    }
}
