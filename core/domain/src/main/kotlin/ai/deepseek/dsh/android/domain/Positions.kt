package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TransportFrame

/**
 * 空洞观测：期望哪个 seq、实际来了哪个 seq。
 * 除了 `seq > contiguousSeq + 1` 没有别的判定入口，baseline 补齐不算空洞。
 */
data class GapObservation(
    val expectedSeq: EventSeq,
    val arrivedSeq: EventSeq,
)

/** 一次批量应用的位置结果：新的两列位置、islands 账本、落库与丢弃的 seq、以及唯一的空洞。 */
data class PositionApply(
    val position: SessionPosition,
    val islands: List<AppliedIsland>,
    val appliedSeqs: List<EventSeq>,
    val droppedSeqs: List<EventSeq>,
    val gap: GapObservation?,
)

/**
 * 会话位置 maths：`contiguousSeq`（无洞最高 seq）与 `appliedMaxSeq`（曾应用最高 seq）独立推进。
 * 帧必须先按 seq 去重再做空洞检测，否则一串连续的回放帧会各自报一个洞。
 */
object Positions {

    fun nextExpected(position: SessionPosition): EventSeq? = position.nextExpected

    /** 唯一的 gap 条件。 */
    fun isGap(position: SessionPosition, seq: EventSeq): Boolean {
        val contiguous = position.contiguousSeq ?: return false
        return seq.value > contiguous.value + 1
    }

    fun apply(
        position: SessionPosition,
        islands: List<AppliedIsland>,
        frames: List<TransportFrame>,
        mergeGap: Long,
    ): PositionApply {
        val selection = GapDetector.select(position, islands, frames)
        return applySeqs(
            position = position,
            islands = islands,
            candidateSeqs = selection.candidateSeqs,
            mergeGap = mergeGap,
            droppedSeqs = selection.droppedSeqs,
        )
    }

    fun applySeqs(
        position: SessionPosition,
        islands: List<AppliedIsland>,
        candidateSeqs: List<EventSeq>,
        mergeGap: Long,
        droppedSeqs: List<EventSeq> = emptyList(),
    ): PositionApply {
        if (candidateSeqs.isEmpty()) {
            return PositionApply(position, islands, emptyList(), droppedSeqs.distinct(), null)
        }
        val lowest = candidateSeqs.minOf { it.value }
        val expected = position.nextExpected
        val arrived = EventSeq(lowest)
        val gap = expected?.let { if (isGap(position, arrived)) GapObservation(it, arrived) else null }
        val seededContiguous = position.contiguousSeq ?: arrived
        val updatedIslands = Islands.applyIslands(islands, candidateSeqs.mapTo(mutableSetOf()) { it.value }, mergeGap)
        val advanced = advanceContiguous(SessionPosition(seededContiguous, position.appliedMaxSeq), updatedIslands)
        val appliedMax = highestOf(position.appliedMaxSeq, candidateSeqs)
        return PositionApply(
            position = SessionPosition(advanced.contiguousSeq, appliedMax),
            islands = updatedIslands,
            appliedSeqs = candidateSeqs.distinct().sortedBy { it.value },
            droppedSeqs = droppedSeqs.distinct(),
            gap = gap,
        )
    }

    /** 跨连续段推进：只有 islands 覆盖 `contiguousSeq + 1` 时才动，否则停在洞前。 */
    fun advanceContiguous(position: SessionPosition, islands: List<AppliedIsland>): SessionPosition {
        var contiguous = position.contiguousSeq ?: return position
        while (true) {
            val next = contiguous.value + 1
            val covering = islands.firstOrNull { it.startSeq.value <= next && it.endSeq.value >= next } ?: break
            if (covering.endSeq.value <= contiguous.value) break
            contiguous = covering.endSeq
        }
        return if (contiguous == position.contiguousSeq) position else position.copy(contiguousSeq = contiguous)
    }

    private fun highestOf(current: EventSeq?, seqs: List<EventSeq>): EventSeq? =
        seqs.fold(current) { acc, seq -> if (acc == null || seq.value > acc.value) seq else acc }
}
