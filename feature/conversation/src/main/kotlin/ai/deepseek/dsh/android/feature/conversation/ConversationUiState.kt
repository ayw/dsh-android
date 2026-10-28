package ai.deepseek.dsh.android.feature.conversation

import ai.deepseek.dsh.android.designsystem.timeline.PendingAnchorContent
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.TimelineGapState
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TimelineItem

/**
 * 会话详情页（M4 只读）唯一状态。
 *
 * 不变量：
 * - [items] 是聊天顺序（最新在最下），key 唯一；向上翻页只插入更早的行，不重排已有行；
 * - [pendingContents] 的键是 [TimelineItem.key]，值为 null 表示载荷解码失败（D32），卡片仍渲染身份；
 * - [notices] 只带**种类**，文案与视觉强度由屏幕从 `:core:designsystem` 资源取（G14）。
 */
data class ConversationUiState(
    val sessionId: SessionId,
    val items: List<TimelineItem> = emptyList(),
    val pendingContents: Map<String, PendingAnchorContent?> = emptyMap(),
    val expandedGroups: Set<String> = emptySet(),
    val notices: List<ConversationNoticeKind> = emptyList(),
    val gapState: TimelineGapState = TimelineGapState.FullyCovered,
    val historyUnsafeType: String? = null,
    val initialLoading: Boolean = true,
    val historyLoading: Boolean = false,
    val historyError: DomainError? = null,
    /** 读侧流（快照/pending）失败：仍保留已缓存的条目，不伪装成空会话。 */
    val readError: DomainError? = null,
    val actionError: DomainError? = null,
    /** 远端可能还有更早的页；由历史页响应驱动，不由条目数猜测。 */
    val hasMoreHistory: Boolean = true,
) {
    val isEmptyTimeline: Boolean get() = items.isEmpty()
}

/** 说明条种类；每个值在 UI 侧都有唯一落点。 */
enum class ConversationNoticeKind {
    SeqMissing,
    HistoryIncomplete,
    HistoryUnsafe,
    VisibleHistoryNotContext,
}
