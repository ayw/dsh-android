package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.EventSeq

/**
 * 已应用 seq 的账本：覆盖率只从这份账本算，不从 raw frame 表或可见消息表算。
 * `mergeGap` 是相邻判定距离：默认 1 表示 `next.start - current.end == 1` 即首尾相接就合并，
 * 中间真有缺口的两段不会合并，否则账本会谎报覆盖。
 */
object Islands {

    fun applyIslands(
        existing: List<AppliedIsland>,
        applied: Set<Long>,
        mergeGap: Long,
    ): List<AppliedIsland> {
        if (applied.isEmpty()) return normalize(existing, mergeGap)
        val singletons = applied.map { AppliedIsland(EventSeq(it), EventSeq(it)) }
        return normalize(existing + singletons, mergeGap)
    }

    /** frontier 前进后删除已被连续位置覆盖的区段，账本规模与会话内实际连续段同阶。 */
    fun trimFrontier(islands: List<AppliedIsland>, contiguousSeq: EventSeq?): List<AppliedIsland> {
        if (contiguousSeq == null) return islands.sortedBy { it.startSeq.value }
        return normalize(
            islands.mapNotNull { island ->
                when {
                    island.endSeq.value <= contiguousSeq.value -> null
                    island.startSeq.value <= contiguousSeq.value ->
                        AppliedIsland(EventSeq(contiguousSeq.value + 1), island.endSeq)
                    else -> island
                }
            },
            0,
        )
    }

    fun covers(islands: List<AppliedIsland>, seq: EventSeq): Boolean =
        islands.any { it.startSeq.value <= seq.value && it.endSeq.value >= seq.value }

    fun coveredHead(islands: List<AppliedIsland>, fromSeq: EventSeq): EventSeq? {
        var head: EventSeq? = if (covers(islands, fromSeq)) fromSeq else null
        var progressed = true
        while (progressed) {
            progressed = false
            val current = head ?: return null
            for (island in islands) {
                if (island.startSeq.value <= current.value + 1 && island.endSeq.value > current.value) {
                    head = island.endSeq
                    progressed = true
                }
            }
        }
        return head
    }

    fun ranges(seqs: Collection<Long>, mergeGap: Long): List<AppliedIsland> =
        normalize(seqs.sorted().map { AppliedIsland(EventSeq(it), EventSeq(it)) }, mergeGap)

    private fun normalize(islands: List<AppliedIsland>, mergeGap: Long): List<AppliedIsland> {
        val sorted = islands.filter { it.startSeq.value <= it.endSeq.value }.sortedBy { it.startSeq.value }
        if (sorted.isEmpty()) return emptyList()
        val merged = mutableListOf(sorted.first())
        for (island in sorted.drop(1)) {
            val last = merged.last()
            if (island.startSeq.value - last.endSeq.value <= mergeGap) {
                merged[merged.lastIndex] = AppliedIsland(last.startSeq, maxOfEnd(last.endSeq, island.endSeq))
            } else {
                merged += island
            }
        }
        return merged
    }

    private fun maxOfEnd(a: EventSeq, b: EventSeq): EventSeq = if (a.value >= b.value) a else b
}
