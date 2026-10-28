package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.TransportFrame

/** 去重后的候选 seq 与被丢弃的 seq：丢弃帧永远不参与空洞判定。 */
data class SeqSelection(
    val candidateSeqs: List<EventSeq>,
    val droppedSeqs: List<EventSeq>,
)

/**
 * 空洞检测：先按 seq 去重（含 islands 已覆盖与低于连续位置两种重复），再比较最小的新 seq。
 * baseline 只是补齐已记录的空洞，把 `lastSeq` 当作“缺失”会凭空造洞。
 */
object GapDetector {

    fun select(
        position: SessionPosition,
        islands: List<AppliedIsland>,
        frames: List<TransportFrame>,
    ): SeqSelection {
        val contiguous = position.contiguousSeq?.value
        val candidates = linkedSetOf<EventSeq>()
        val dropped = linkedSetOf<EventSeq>()
        for (frame in frames.sortedBy { it.seq.value }) {
            if (contiguous != null && frame.seq.value <= contiguous) {
                dropped += frame.seq
            } else if (Islands.covers(islands, frame.seq)) {
                dropped += frame.seq
            } else {
                candidates += frame.seq
            }
        }
        return SeqSelection(candidates.toList(), dropped.toList())
    }

    fun candidateSeqs(
        position: SessionPosition,
        islands: List<AppliedIsland>,
        frames: List<TransportFrame>,
    ): List<EventSeq> = select(position, islands, frames).candidateSeqs

    /** 一批帧最多产生一个空洞。 */
    fun observe(
        position: SessionPosition,
        islands: List<AppliedIsland>,
        frames: List<TransportFrame>,
    ): GapObservation? {
        val candidates = candidateSeqs(position, islands, frames)
        if (candidates.isEmpty()) return null
        val expected = Positions.nextExpected(position) ?: return null
        val arrived = candidates.minBy { it.value }
        return if (Positions.isGap(position, arrived)) GapObservation(expected, arrived) else null
    }

    fun isGap(position: SessionPosition, seq: EventSeq): Boolean = Positions.isGap(position, seq)
}
