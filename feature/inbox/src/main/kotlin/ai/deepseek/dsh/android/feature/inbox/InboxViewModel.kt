package ai.deepseek.dsh.android.feature.inbox

import ai.deepseek.dsh.android.domain.PendingContentDecoder
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.PendingRepository
import ai.deepseek.dsh.android.domain.repository.SessionRepository
import ai.deepseek.dsh.android.domain.repository.SessionSummary
import ai.deepseek.dsh.android.domain.repository.toDomainError
import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingContent
import ai.deepseek.dsh.android.model.PendingCounts
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.SessionId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 收件箱的一个会话分组；[title] 为 null 表示名册里查不到该会话。 */
data class InboxSection(
    val sessionId: SessionId,
    val title: String?,
    val archived: Boolean,
    val available: Boolean,
    val earliestFirstSeenAtMs: Long,
    val cards: List<InboxCard>,
)

/** 已解码的一行待处理请求；[content] 只来自 [PendingContentDecoder]，本模块不碰 payloadJson。 */
data class InboxCard(
    val sessionId: SessionId,
    val requestId: RequestId,
    val kind: PendingKind,
    val status: PendingStatus,
    val completeness: PendingCompleteness,
    val firstSeenAtMs: Long,
    val anchorSeq: EventSeq?,
    val anchoredToCallId: CallId?,
    val content: PendingContent,
)

/**
 * 只读收件箱状态。
 *
 * 空态是否可信、计数是否未知都由 [PendingCounts] 判定，UI 不再自行推断（D13/G07）。
 */
data class InboxUiState(
    val loading: Boolean = true,
    val sections: List<InboxSection> = emptyList(),
    val dismissed: List<InboxCard> = emptyList(),
    val counts: PendingCounts? = null,
    val countsError: DomainError? = null,
    val rowsError: DomainError? = null,
    val actionError: DomainError? = null,
    val volumeMaySkipSweep: Boolean = false,
) {
    val visibleCount: Int get() = sections.sumOf { it.cards.size }

    /** 计数缺失或完整性未知时只能显示“未知”，不能显示伪造的 0。 */
    val countsUnknown: Boolean get() = counts?.showsUnknown != false

    /** 只有完整性核对通过且计数为 0 时，空收件箱才可信。 */
    val trustedEmpty: Boolean get() = counts?.let { value ->
        value.badge == 0 &&
            (value.completeness == PendingCompleteness.Complete ||
                value.completeness == PendingCompleteness.CompleteByServer)
    } == true

    val showDismissed: Boolean get() = dismissed.isNotEmpty() || (counts?.dismissed ?: 0) > 0
}

/**
 * 全局待处理的只读视图模型（M4）。
 *
 * resolve 不在本里程碑内：`/api/respond` 的请求录制属于 M5，这里不猜测请求体。
 */
class InboxViewModel(
    private val pending: PendingRepository,
    private val sessions: SessionRepository,
    private val decoder: PendingContentDecoder,
    private val tuning: SyncTuning = SyncTuning.DEFAULT,
) : ViewModel() {

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    private var groups: Map<SessionId, List<PendingRequest>> = emptyMap()
    private val sessionMeta = mutableMapOf<SessionId, SessionSummary?>()
    private val sessionJobs = mutableMapOf<SessionId, Job>()
    private var counts: PendingCounts? = null
    private var countsError: DomainError? = null
    private var rowsError: DomainError? = null
    private var actionError: DomainError? = null
    private var rowsLoaded = false
    private var countsLoaded = false
    private val decoded = mutableMapOf<String, DecodedRow>()

    init {
        viewModelScope.launch {
            pending.groupedBySession()
                .catch { failure ->
                    rowsError = failure.toDomainError()
                    rowsLoaded = true
                    publish()
                }
                .collect { next ->
                    groups = next
                    rowsError = null
                    rowsLoaded = true
                    syncSessionSubscriptions(next.keys)
                    publish()
                }
        }
        viewModelScope.launch {
            pending.counts()
                .catch { failure ->
                    counts = null
                    countsError = failure.toDomainError()
                    countsLoaded = true
                    publish()
                }
                .collect { next ->
                    counts = next
                    countsError = null
                    countsLoaded = true
                    publish()
                }
        }
    }

    /** 本地划掉：请求本身仍未结算，之后仍出现在“已划掉”里。 */
    fun dismiss(kind: PendingKind, requestId: RequestId) {
        viewModelScope.launch {
            actionError = null
            pending.dismiss(kind, requestId)
                .onFailure { actionError = it.toDomainError() }
            publish()
        }
    }

    /** 撤销本地划掉。 */
    fun undismiss(kind: PendingKind, requestId: RequestId) {
        viewModelScope.launch {
            actionError = null
            pending.undismiss(kind, requestId)
                .onFailure { actionError = it.toDomainError() }
            publish()
        }
    }

    // 每个会话的标题/归档状态各自订阅，某个会话查不到也不阻塞其余分组渲染。
    private fun syncSessionSubscriptions(ids: Set<SessionId>) {
        sessionJobs.keys.toList().forEach { id ->
            if (id !in ids) {
                sessionJobs.remove(id)?.cancel()
                sessionMeta.remove(id)
            }
        }
        ids.forEach { id ->
            if (sessionJobs[id] == null) {
                sessionJobs[id] = viewModelScope.launch {
                    sessions.byId(id)
                        .catch {
                            if (!sessionMeta.containsKey(id)) sessionMeta[id] = null
                            publish()
                        }
                        .collect { summary ->
                            sessionMeta[id] = summary
                            publish()
                        }
                }
            }
        }
    }

    private fun publish() {
        val (sections, dismissed) = buildSections()
        val knownTotal = sections.sumOf { it.cards.size } + dismissed.size
        _state.value = InboxUiState(
            loading = !rowsLoaded || (!countsLoaded && groups.isEmpty()),
            sections = sections,
            dismissed = dismissed,
            counts = counts,
            countsError = countsError,
            rowsError = rowsError,
            actionError = actionError,
            volumeMaySkipSweep = counts?.showsUnknown == true &&
                knownTotal > tuning.baselineKeySweepSkipThreshold,
        )
    }

    private fun buildSections(): Pair<List<InboxSection>, List<InboxCard>> {
        val active = mutableListOf<InboxCard>()
        val dismissed = mutableListOf<InboxCard>()
        for (rows in groups.values) {
            for (row in rows) {
                if (!row.status.countsTowardBadge) continue
                val card = row.toCard()
                if (row.dismissedAtMs == null) active += card else dismissed += card
            }
        }

        val sections = active
            .sortedWith(cardOrder)
            .groupBy { it.sessionId }
            .map { (sessionId, cards) ->
                val summary = sessionMeta[sessionId]
                InboxSection(
                    sessionId = sessionId,
                    title = summary?.title,
                    archived = summary?.archived == true,
                    available = summary != null,
                    earliestFirstSeenAtMs = cards.first().firstSeenAtMs,
                    cards = cards,
                )
            }
            .sortedWith(compareBy({ it.earliestFirstSeenAtMs }, { it.sessionId.value }))

        dismissed.sortWith(cardOrder)
        val liveKeys = (active + dismissed).map { keyOf(it) }.toSet()
        decoded.keys.retainAll(liveKeys)
        return sections to dismissed
    }

    private fun PendingRequest.toCard(): InboxCard = InboxCard(
        sessionId = sessionId,
        requestId = requestId,
        kind = kind,
        status = status,
        completeness = completeness,
        firstSeenAtMs = firstSeenAtMs,
        anchorSeq = anchorSeq,
        anchoredToCallId = callId,
        content = renderContent(),
    )

    private fun PendingRequest.renderContent(): PendingContent {
        val cacheKey = keyOf(this)
        val cached = decoded[cacheKey]
        if (cached != null && cached.payload == payloadJson) return cached.content
        val content = runCatching { decoder.decode(kind, payloadJson) }
            .getOrElse { PendingContent.Undecodable(kind) }
        decoded[cacheKey] = DecodedRow(payloadJson, content)
        return content
    }

    private data class DecodedRow(val payload: String, val content: PendingContent)

    class Factory(
        private val pending: PendingRepository,
        private val sessions: SessionRepository,
        private val decoder: PendingContentDecoder,
        private val tuning: SyncTuning = SyncTuning.DEFAULT,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(InboxViewModel::class.java))
            return InboxViewModel(pending, sessions, decoder, tuning) as T
        }
    }
}

private val cardOrder = compareBy<InboxCard>({ it.firstSeenAtMs }, { it.requestId.value })

private fun keyOf(card: InboxCard): String =
    "${card.sessionId.value}:${card.kind.name}:${card.requestId.value}"

private fun keyOf(row: PendingRequest): String =
    "${row.sessionId.value}:${row.kind.name}:${row.requestId.value}"
