package ai.deepseek.dsh.android.feature.conversation

import ai.deepseek.dsh.android.domain.repository.TimelineGapState
import ai.deepseek.dsh.android.model.TimelineItem

/**
 * 时间线的纯函数：不碰 Android、不碰 Flow，便于单测覆盖合并与缺口语义。
 */

/**
 * 手工向上翻页得到的 [manual] 与实时快照 [live] 合并。
 *
 * 快照是活真相：同一个 key 以快照版本为准（内容可能已更新），手工条目只补充快照窗口
 * 之外的更早部分。相等 `primarySeq` 时保持快照自身的相对顺序（投影器同 seq 内分组在前），
 * 手工条目只在严格更小的 seq 处插入 —— 于是快照推进永远不会重排或复制已经显示过的行。
 */
internal fun mergeTimeline(manual: List<TimelineItem>, live: List<TimelineItem>): List<TimelineItem> {
    val liveKeys = HashSet<String>(live.size)
    live.forEach { liveKeys.add(it.key) }
    val older = manual
        .filterNot { liveKeys.contains(it.key) }
        .distinctBy { it.key }
        .sortedBy { it.primarySeq.value }

    if (older.isEmpty()) return live
    if (live.isEmpty()) return older

    val out = ArrayList<TimelineItem>(older.size + live.size)
    var index = 0
    live.forEach { liveItem ->
        while (index < older.size && older[index].primarySeq.value < liveItem.primarySeq.value) {
            out.add(older[index])
            index++
        }
        out.add(liveItem)
    }
    while (index < older.size) {
        out.add(older[index])
        index++
    }
    return out
}

/** 连续性状态的可见落点：任何不完整都必须成条说明，绝不静默截断。 */
internal fun gapNotices(gap: TimelineGapState, historyUnsafe: Boolean): List<ConversationNoticeKind> {
    val primary = when (gap) {
        TimelineGapState.FullyCovered ->
            if (historyUnsafe) listOf(ConversationNoticeKind.HistoryUnsafe) else emptyList()

        is TimelineGapState.Missing -> listOf(ConversationNoticeKind.SeqMissing)
        TimelineGapState.HistoryIncomplete -> listOf(ConversationNoticeKind.HistoryIncomplete)
        is TimelineGapState.HistoryUnsafe -> listOf(ConversationNoticeKind.HistoryUnsafe)
    }
    return if (primary.isEmpty()) emptyList() else primary + ConversationNoticeKind.VisibleHistoryNotContext
}
