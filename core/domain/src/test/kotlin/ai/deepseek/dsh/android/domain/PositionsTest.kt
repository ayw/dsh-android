package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.SessionPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val tuning = SyncTuning.DEFAULT
private val mergeGap = tuning.islandMergeGap

private fun seq(value: Long): EventSeq = EventSeq(value)

private fun island(start: Long, end: Long): AppliedIsland = AppliedIsland(seq(start), seq(end))

private fun position(contiguous: Long?, appliedMax: Long?): SessionPosition = SessionPosition(
    contiguousSeq = if (contiguous == null) null else seq(contiguous),
    appliedMaxSeq = if (appliedMax == null) null else seq(appliedMax),
)

private fun range(from: Long, to: Long): List<EventSeq> = (from..to).map { seq(it) }

class PositionsTest {

    @Test
    fun sessionCursorTwoColumnsAdvanceIndependentlyAfterRangedReset() {
        val settled = Positions.applySeqs(position(10, 10), listOf(island(10, 10)), range(11, 13), mergeGap)
        assertEquals(seq(13), settled.position.contiguousSeq)
        assertEquals(seq(13), settled.position.appliedMaxSeq)

        val aheadOfHole = Positions.applySeqs(settled.position, settled.islands, listOf(seq(20)), mergeGap)
        assertEquals(seq(13), aheadOfHole.position.contiguousSeq)
        assertEquals(seq(20), aheadOfHole.position.appliedMaxSeq)
    }

    @Test
    fun sessionCursorContiguousStuckBehindSnapshotTailAfterReset() {
        val result = Positions.applySeqs(position(100, 100), emptyList(), range(900, 950), mergeGap)
        assertEquals(seq(100), result.position.contiguousSeq)
        assertEquals(seq(950), result.position.appliedMaxSeq)
        assertEquals(seq(101), result.gap?.expectedSeq)
        assertEquals(seq(900), result.gap?.arrivedSeq)
        assertTrue(Islands.covers(result.islands, seq(950)))
        assertFalse(Islands.covers(result.islands, seq(500)))
    }

    @Test
    fun sessionCursorAdvancesAcrossOpenHoleOnceBackfilledReset() {
        val tail = Positions.applySeqs(position(100, 100), emptyList(), range(900, 950), mergeGap)
        val backfilled = Positions.applySeqs(tail.position, tail.islands, range(101, 899), mergeGap)
        assertEquals(seq(950), backfilled.position.contiguousSeq)
        assertEquals(seq(950), backfilled.position.appliedMaxSeq)
        assertNull(backfilled.gap)
        assertEquals(1, backfilled.islands.size)
    }

    @Test
    fun sessionCursorNextExpectedIsContiguousPlusOneNotAppliedMaxReset() {
        assertEquals(seq(101), Positions.nextExpected(position(100, 950)))
        assertNull(Positions.nextExpected(position(null, null)))
    }

    @Test
    fun sessionCursorOnlySeqBeyondContiguousPlusOneCountsAsGapReset() {
        val current = position(100, 950)
        assertFalse(Positions.isGap(current, seq(101)))
        assertFalse(Positions.isGap(current, seq(100)))
        assertFalse(Positions.isGap(current, seq(50)))
        assertTrue(Positions.isGap(current, seq(102)))
        assertTrue(Positions.isGap(current, seq(951)))
    }

    @Test
    fun sessionCursorWithoutWatermarkNeverInventsGapReset() {
        val seeded = Positions.applySeqs(position(null, null), emptyList(), range(900, 910), mergeGap)
        assertNull(seeded.gap)
        assertEquals(seq(910), seeded.position.contiguousSeq)
        assertEquals(seq(910), seeded.position.appliedMaxSeq)
        assertEquals(1, seeded.islands.size)
    }

    @Test
    fun sessionCursorSeedStartsAtEarliestFrameNotLatestReset() {
        val seeded = Positions.applySeqs(position(null, null), emptyList(), listOf(seq(905), seq(900)), mergeGap)
        assertNull(seeded.gap)
        assertEquals(seq(900), seeded.position.contiguousSeq)
        assertEquals(seq(905), seeded.position.appliedMaxSeq)
    }

    @Test
    fun sessionCursorAppliedMaxNeverRewindsOnBackfilledFramesReset() {
        val result = Positions.applySeqs(position(100, 950), listOf(island(100, 100)), listOf(seq(101)), mergeGap)
        assertEquals(seq(950), result.position.appliedMaxSeq)
        assertEquals(seq(101), result.position.contiguousSeq)
    }

    @Test
    fun sessionCursorAdvanceStopsAtTheLedgerHoleReset() {
        val islands = listOf(island(101, 120), island(130, 140))
        val advanced = Positions.advanceContiguous(position(100, 140), islands)
        assertEquals(seq(120), advanced.contiguousSeq)
        assertEquals(seq(140), advanced.appliedMaxSeq)
    }

    @Test
    fun sessionCursorEmptyCandidateBatchKeepsPositionAndLedgerReset() {
        val dropped = listOf(seq(50))
        val result = Positions.applySeqs(position(100, 100), listOf(island(100, 100)), emptyList(), mergeGap, dropped)
        assertEquals(position(100, 100), result.position)
        assertEquals(listOf(island(100, 100)), result.islands)
        assertEquals(dropped, result.droppedSeqs)
        assertTrue(result.appliedSeqs.isEmpty())
        assertNull(result.gap)
    }

    @Test
    fun sessionCursorAppliedSeqsAreDedupedAndOrderedReset() {
        val result = Positions.applySeqs(
            position(100, 100),
            listOf(island(100, 100)),
            listOf(seq(103), seq(101), seq(103), seq(102)),
            mergeGap,
        )
        assertEquals(range(101, 103), result.appliedSeqs)
        assertEquals(seq(103), result.position.contiguousSeq)
        assertNull(result.gap)
    }

    @Test
    fun sessionCursorGapObservationCarriesExpectedAndArrivedOnlyReset() {
        val result = Positions.applySeqs(position(100, 100), emptyList(), listOf(seq(102)), mergeGap)
        assertEquals(GapObservation(seq(101), seq(102)), result.gap)
    }

    @Test
    fun sessionCursorIslandMergeDistanceComesFromTuningReset() {
        val touching = Positions.applySeqs(
            position = position(100, 100),
            islands = listOf(island(100, 100)),
            candidateSeqs = listOf(seq(100 + mergeGap)),
            mergeGap = mergeGap,
        )
        assertEquals(1, touching.islands.size)
        assertEquals(seq(101), touching.position.contiguousSeq)
        assertNull(touching.gap)

        val pastMergeDistance = Positions.applySeqs(
            position = position(100, 100),
            islands = listOf(island(100, 100)),
            candidateSeqs = listOf(seq(100 + mergeGap + 1)),
            mergeGap = mergeGap,
        )
        assertEquals(2, pastMergeDistance.islands.size)
        assertEquals(seq(100), pastMergeDistance.position.contiguousSeq)
        assertEquals(GapObservation(seq(101), seq(102)), pastMergeDistance.gap)
    }
}
