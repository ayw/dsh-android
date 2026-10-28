package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.EventSeq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val islandTuning = SyncTuning.DEFAULT
private val ledgerMergeGap = islandTuning.islandMergeGap

private fun islandSeq(value: Long): EventSeq = EventSeq(value)

private fun ledger(start: Long, end: Long): AppliedIsland = AppliedIsland(islandSeq(start), islandSeq(end))

private fun ledgerOf(vararg ranges: Pair<Int, Int>): List<AppliedIsland> = ranges.map { ledger(it.first.toLong(), it.second.toLong()) }

private fun appliedRange(from: Long, to: Long): Set<Long> = (from..to).toSet()

class IslandsTest {

    @Test
    fun appliedIslandsMergeTouchingRangesIntoOneLedgerEntryIslandReset() {
        val merged = Islands.applyIslands(ledgerOf(100 to 110), appliedRange(111, 120), ledgerMergeGap)
        assertEquals(ledgerOf(100 to 120), merged)
    }

    @Test
    fun appliedIslandsMergeOverlappingRangesIslandReset() {
        val merged = Islands.applyIslands(ledgerOf(100 to 110), appliedRange(105, 115), ledgerMergeGap)
        assertEquals(ledgerOf(100 to 115), merged)
    }

    @Test
    fun appliedIslandsKeepARealHoleSeparatedIslandReset() {
        val kept = Islands.applyIslands(ledgerOf(100 to 110), appliedRange(112, 120), ledgerMergeGap)
        assertEquals(ledgerOf(100 to 110, 112 to 120), kept)
        assertFalse(Islands.covers(kept, islandSeq(111)))
    }

    @Test
    fun appliedIslandsMergeGapDistanceComesFromTuningIslandReset() {
        val adjacentDistance = ledgerMergeGap
        val touching = Islands.applyIslands(ledgerOf(100 to 100), appliedRange(100 + adjacentDistance, 100 + adjacentDistance), adjacentDistance)
        val oneBeyond = Islands.applyIslands(ledgerOf(100 to 100), appliedRange(101 + adjacentDistance, 101 + adjacentDistance), adjacentDistance)
        assertEquals(1, touching.size)
        assertEquals(2, oneBeyond.size)
    }

    @Test
    fun appliedIslandsEmptyApplyKeepsLedgerNormalizedIslandReset() {
        val normalized = Islands.applyIslands(ledgerOf(100 to 100, 101 to 101), emptySet(), ledgerMergeGap)
        assertEquals(ledgerOf(100 to 101), normalized)
    }

    @Test
    fun appliedIslandsDropReversedRangeFromLedgerIslandReset() {
        val normalized = Islands.applyIslands(ledgerOf(120 to 100, 130 to 140), emptySet(), ledgerMergeGap)
        assertEquals(ledgerOf(130 to 140), normalized)
    }

    @Test
    fun appliedIslandFrontierTrimDropsCoveredPrefixIslandReset() {
        val trimmed = Islands.trimFrontier(ledgerOf(100 to 120, 130 to 140), islandSeq(125))
        assertEquals(ledgerOf(130 to 140), trimmed)
    }

    @Test
    fun appliedIslandFrontierTrimSplitsSpanningIslandIslandReset() {
        val trimmed = Islands.trimFrontier(ledgerOf(100 to 200), islandSeq(150))
        assertEquals(ledgerOf(151 to 200), trimmed)
        assertTrue(Islands.covers(trimmed, islandSeq(151)))
        assertFalse(Islands.covers(trimmed, islandSeq(150)))
    }

    @Test
    fun appliedIslandFrontierTrimWithoutWatermarkKeepsWholeLedgerIslandReset() {
        val trimmed = Islands.trimFrontier(ledgerOf(130 to 140, 100 to 120), null)
        assertEquals(ledgerOf(100 to 120, 130 to 140), trimmed)
    }

    @Test
    fun appliedIslandCoverageAnswersFromLedgerOnlyIslandReset() {
        val ledger = ledgerOf(100 to 110, 120 to 130)
        assertTrue(Islands.covers(ledger, islandSeq(100)))
        assertTrue(Islands.covers(ledger, islandSeq(110)))
        assertFalse(Islands.covers(ledger, islandSeq(111)))
        assertTrue(Islands.covers(ledger, islandSeq(130)))
    }

    @Test
    fun appliedIslandCoveredHeadStopsAtTheHoleIslandReset() {
        val ledger = ledgerOf(101 to 120, 130 to 140)
        assertEquals(islandSeq(120), Islands.coveredHead(ledger, islandSeq(101)))
        assertEquals(islandSeq(140), Islands.coveredHead(ledger, islandSeq(130)))
        assertNull(Islands.coveredHead(ledger, islandSeq(121)))
    }

    @Test
    fun appliedIslandCoveredHeadWalksMultipleTouchingEntriesIslandReset() {
        val ledger = ledgerOf(101 to 110, 111 to 125)
        assertEquals(islandSeq(125), Islands.coveredHead(ledger, islandSeq(101)))
    }

    @Test
    fun appliedIslandRangesCollapseSparseSeqsIntoRunsIslandReset() {
        val runs = Islands.ranges(listOf(1L, 2L, 3L, 7L, 8L, 3L), ledgerMergeGap)
        assertEquals(ledgerOf(1 to 3, 7 to 8), runs)
    }

    @Test
    fun appliedIslandEmptyLedgerCoversNothingIslandReset() {
        assertFalse(Islands.covers(emptyList(), islandSeq(1)))
        assertNull(Islands.coveredHead(emptyList(), islandSeq(1)))
        assertEquals(emptyList(), Islands.applyIslands(emptyList(), emptySet(), ledgerMergeGap))
    }
}
