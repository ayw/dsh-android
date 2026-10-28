package ai.deepseek.dsh.android.feature.conversation

import ai.deepseek.dsh.android.designsystem.timeline.PendingAnchorContent
import ai.deepseek.dsh.android.designsystem.timeline.PendingField
import ai.deepseek.dsh.android.designsystem.timeline.PendingOption
import ai.deepseek.dsh.android.domain.PendingContentDecoder
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.PendingRepository
import ai.deepseek.dsh.android.domain.repository.TimelineGapState
import ai.deepseek.dsh.android.domain.repository.TimelineRepository
import ai.deepseek.dsh.android.domain.repository.toDomainError
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.PendingContent
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TimelineItem
import ai.deepseek.dsh.android.model.TimelineSnapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 单个会话的只读视图模型（M4）。
 *
 * 写侧一律不碰（D33）：屏幕的 `onDecision` 恒为 null，这里也不调用 `pending.resolve`；
 * dismiss/undismiss 只是本机标记。历史翻页用 `historyPage` 的 keyset，绝不推进任何轮询游标。
 */
class ConversationViewModel(
    private val sessionId: SessionId,
    private val timeline: TimelineRepository,
    private val pending: PendingRepository,
    private val decoder: PendingContentDecoder,
    private val tuning: SyncTuning = SyncTuning.DEFAULT,
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationUiState(sessionId = sessionId))
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    // 读侧流最后一次发出的值；交互侧字段（展开、翻页、错误）在 publish() 里合并。
    private var snapshot: TimelineSnapshot? = null
    private var gap: TimelineGapState = TimelineGapState.FullyCovered
    private var pendingRows: List<PendingRequest> = emptyList()
    private var snapshotLoaded = false
    private var readError: DomainError? = null

    private val expandedGroups = linkedSetOf<String>()
    private val olderItems = LinkedHashMap<String, TimelineItem>()
    private var hasMore = true
    private var loadingOlder = false
    private var olderError: DomainError? = null
    private var actionError: DomainError? = null

    /** 按 payload 文本缓存解码结果，避免同一次 pending 快照反复解码同一份 JSON。 */
    private val decoded = mutableMapOf<String, DecodedRow>()

    init {
        val snapshots: Flow<TimelineSnapshot?> = timeline.snapshot(sessionId)
            .map<TimelineSnapshot, TimelineSnapshot?> { it }
            .catch { failure ->
                readError = failure.toDomainError()
                snapshotLoaded = true
                publish()
                emit(null)
            }
        val gaps: Flow<TimelineGapState> = timeline.gapState(sessionId)
            .catch { failure ->
                // 缺口状态读不出来时按“可能不完整”处理，不能默默显示 FullyCovered。
                gap = TimelineGapState.HistoryIncomplete
                readError = readError ?: failure.toDomainError()
                emit(gap)
            }

        var lastRows: List<PendingRequest> = emptyList()
        val rows: Flow<List<PendingRequest>> = pending.forSession(sessionId)
            .catch { failure ->
                readError = readError ?: failure.toDomainError()
                emit(lastRows)
            }

        viewModelScope.launch {
            combine(snapshots, gaps, rows) { snapshotValue, gapValue, pendingValue ->
                Triple(snapshotValue, gapValue, pendingValue)
            }.collect { (snapshotValue, gapValue, pendingValue) ->
                snapshot = snapshotValue
                gap = gapValue
                pendingRows = pendingValue
                lastRows = pendingValue
                snapshotLoaded = true
                publish()
            }
        }
    }

    /**
     * 向上翻一页。边界取当前已加载条目的最小 seq（`beforeSeq` 是 exclusive）；
     * 0 号占位不是真实位置，不能作为游标（D30）。
     */
    fun loadOlder() {
        if (loadingOlder || !hasMore) return
        loadingOlder = true
        olderError = null
        publish()
        viewModelScope.launch {
            val result = timeline.historyPage(
                sessionId = sessionId,
                beforeSeq = oldestCursor(_state.value.items),
                maxMessages = tuning.historyPageSize,
            )
            result.onSuccess { window ->
                if (window.sessionId != sessionId) {
                    // 防御：跨会话串流不得污染本页窗口。
                    hasMore = false
                } else {
                    window.items.forEach { item ->
                        if (item.key !in olderItems) olderItems[item.key] = item
                    }
                    hasMore = window.hasMore
                }
            }
            result.onFailure { failure -> olderError = failure.toDomainError() }
            loadingOlder = false
            publish()
        }
    }

    fun toggleGroup(key: String) {
        if (!expandedGroups.add(key)) expandedGroups.remove(key)
        publish()
    }

    /** 本地划掉：请求仍未结算，只是从计数里移开（D13）。失败经 [ConversationUiState.actionError] 呈现。 */
    fun dismiss(kind: PendingKind, requestId: RequestId) {
        viewModelScope.launch {
            actionError = null
            publish()
            pending.dismiss(kind, requestId)
                .onFailure { failure -> actionError = failure.toDomainError() }
            publish()
        }
    }

    /** 撤销本地划掉。 */
    fun undismiss(kind: PendingKind, requestId: RequestId) {
        viewModelScope.launch {
            actionError = null
            publish()
            pending.undismiss(kind, requestId)
                .onFailure { failure -> actionError = failure.toDomainError() }
            publish()
        }
    }

    private fun publish() {
        val snap = snapshot
        val items = mergeTimeline(olderItems.values.toList(), snap?.items.orEmpty())
        val rows = pendingRows.associateBy { requestKey(it.kind, it.requestId) }
        val contents = buildContents(items, rows)
        decoded.keys.retainAll(contents.keys)
        _state.value = ConversationUiState(
            sessionId = sessionId,
            items = items,
            pendingContents = contents,
            expandedGroups = expandedGroups.toSet(),
            notices = gapNotices(gap, snap?.historyUnsafe == true),
            gapState = gap,
            historyUnsafeType = unsafeType(items, snap),
            initialLoading = !snapshotLoaded,
            historyLoading = loadingOlder,
            historyError = olderError,
            readError = readError,
            actionError = actionError,
            hasMoreHistory = hasMore,
        )
    }

    private fun unsafeType(items: List<TimelineItem>, snap: TimelineSnapshot?): String? {
        // `gap` 是可被流并发写入的属性，先取本地快照才能安全判型。
        val current = gap
        return when {
            current is TimelineGapState.HistoryUnsafe -> current.unknownType
            snap?.historyUnsafe == true ->
                items.filterIsInstance<TimelineItem.HistoryUnsafeMarker>().firstOrNull()?.unknownType

            else -> null
        }
    }

    private fun buildContents(
        items: List<TimelineItem>,
        rows: Map<String, PendingRequest>,
    ): Map<String, PendingAnchorContent?> {
        val contents = LinkedHashMap<String, PendingAnchorContent?>()
        items.forEach { item ->
            if (item is TimelineItem.PendingAnchor) {
                contents[item.key] = contentFor(rows[requestKey(item.kind, item.requestId)])
            }
        }
        return contents
    }

    private fun contentFor(row: PendingRequest?): PendingAnchorContent? {
        if (row == null) return null
        val key = requestKey(row.kind, row.requestId)
        val cached = decoded[key]
        if (cached != null && cached.payload == row.payloadJson) return cached.content
        val content = toAnchorContent(decodeRow(row.kind, row.payloadJson))
        decoded[key] = DecodedRow(row.payloadJson, content)
        return content
    }

    /** 解码器是端口，坏载荷必须落 Undecodable 而不是把卡片整张吞掉（D32）。 */
    private fun decodeRow(kind: PendingKind, payloadJson: String): PendingContent =
        runCatching { decoder.decode(kind, payloadJson) }
            .getOrElse { PendingContent.Undecodable(kind) }

    private fun toAnchorContent(content: PendingContent): PendingAnchorContent? = when (content) {
        is PendingContent.Approval -> PendingAnchorContent(
            title = content.toolName.orEmpty(),
            detail = content.reason,
            // M4 不提交，因此不生成带本地化文案的审批选项（D33）。
            decisionOptions = emptyList(),
        )

        is PendingContent.Question -> PendingAnchorContent(
            // 标题留空：本地化兜底需要资源，由屏幕补（ViewModel 没有 Context）。
            title = "",
            fields = content.questions.map { question ->
                PendingField(
                    id = question.questionId,
                    label = question.text,
                    options = question.options.mapIndexed { index, option ->
                        PendingOption(id = index.toString(), label = option.label)
                    },
                    multiSelect = question.multiSelect,
                    allowFreeText = false,
                    required = true,
                )
            },
        )

        is PendingContent.Undecodable -> null
    }

    private fun oldestCursor(items: List<TimelineItem>): EventSeq? =
        items.asSequence()
            .map { it.primarySeq.value }
            // 0 是“该行没有 seq”的占位（D30），不是可用的 keyset 边界。
            .filter { it > 0L }
            .minOrNull()
            ?.let { EventSeq(it) }

    private data class DecodedRow(
        val payload: String,
        val content: PendingAnchorContent?,
    )

    class Factory(
        private val sessionId: SessionId,
        private val timeline: TimelineRepository,
        private val pending: PendingRepository,
        private val decoder: PendingContentDecoder,
        private val tuning: SyncTuning = SyncTuning.DEFAULT,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ConversationViewModel::class.java))
            return ConversationViewModel(sessionId, timeline, pending, decoder, tuning) as T
        }
    }
}

private fun requestKey(kind: PendingKind, requestId: RequestId): String =
    "${kind.name}:${requestId.value}"
