package ai.deepseek.dsh.android.feature.sessions

import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.PendingRepository
import ai.deepseek.dsh.android.domain.repository.RosterSyncState
import ai.deepseek.dsh.android.domain.repository.SessionKeyset
import ai.deepseek.dsh.android.domain.repository.SessionPage
import ai.deepseek.dsh.android.domain.repository.SessionRepository
import ai.deepseek.dsh.android.domain.repository.SessionSummary
import ai.deepseek.dsh.android.domain.repository.toDomainError
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingCounts
import ai.deepseek.dsh.android.model.SessionId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 列表的四种可判定形态；空态是否可信由 [SessionsUiState.trustedEmpty] 再判一次（D13）。 */
enum class SessionsListPhase { Loading, Empty, Content, Error }

/**
 * 会话列表的一行。标题为 null 时由 UI 用资源文案兜底，绝不回退到会话 id
 * （id 不是用户可见事实，也不是标题）。[pendingCount] 是本会话**可见的**待处理数：
 * 仅当大于 0 才显示徽标，等于 0 时不显示（不显示不等于伪造 0，D13 只约束聚合计数）。
 */
data class SessionRow(
    val sessionId: SessionId,
    val title: String?,
    val updatedAtMs: Long,
    val running: Boolean,
    val archived: Boolean,
    val historyUnsafe: Boolean,
    val pendingCount: Int,
)

/**
 * 只读会话名册状态。
 *
 * [counts] 为 null 与 [countsUnknown] 都为真时同样表示“未知”：任何地方都不能把它当成 0。
 * 只有完整性核对通过且计数为零时，空名单才是可信的。
 */
data class SessionsUiState(
    val phase: SessionsListPhase = SessionsListPhase.Loading,
    val rows: List<SessionRow> = emptyList(),
    val rosterSyncState: RosterSyncState = RosterSyncState.Unknown,
    val counts: PendingCounts? = null,
    val countsError: DomainError? = null,
    val loadError: DomainError? = null,
    val actionError: DomainError? = null,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
) {
    /** 计数缺失或完整性未知时只能显示“未知”，不能显示伪造的 0。 */
    val countsUnknown: Boolean get() = counts?.showsUnknown != false

    /** 可信空名单：完整性核对通过且计数为 0。 */
    val trustedEmpty: Boolean get() = phase == SessionsListPhase.Empty &&
        counts?.let { value ->
            value.badge == 0 &&
                (value.completeness == PendingCompleteness.Complete ||
                    value.completeness == PendingCompleteness.CompleteByServer)
        } == true

    /** 名册刷新失败但仍有缓存行时，列表可读但不是最新。 */
    val rosterMayBeStale: Boolean
        get() = rosterSyncState == RosterSyncState.Stale || rosterSyncState == RosterSyncState.Failed
}

/**
 * 会话列表视图模型（M4 只读）。
 *
 * 分页完全走 [SessionRepository.paged] 的 keyset：首页传 null，后续页传上一页的
 * [SessionPage.nextKey]；`nextKey == null` 即到底。没有 offset，也不依赖 androidx.paging（D19/D31）。
 * 已加载行按 sessionId 去重，刷新失败时保留现有行，只暴露 [SessionsUiState.actionError]。
 */
class SessionsViewModel(
    private val sessions: SessionRepository,
    private val pending: PendingRepository,
    private val tuning: SyncTuning = SyncTuning.DEFAULT,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    private var nextKey: SessionKeyset? = null
    private var loadGeneration = 0
    private var refreshJob: Job? = null

    // 已加载行的单一真相：按 sessionId 去重、保持首次出现顺序；pending 变化时据此重算行。
    private var loadedItems: List<SessionSummary> = emptyList()
    private var canLoadMore = false
    private var pendingBySession: Map<SessionId, Int> = emptyMap()

    init {
        viewModelScope.launch {
            pending.counts()
                .catch { failure ->
                    _state.update { it.copy(countsError = failure.toDomainError()) }
                }
                .collect { next ->
                    _state.update { it.copy(counts = next, countsError = null) }
                }
        }
        viewModelScope.launch {
            // 行内徽标只看本会话可见的 pending；与聚合完整性可未知无关（>0 才显示）。
            pending.groupedBySession().collect { grouped ->
                pendingBySession = grouped.mapValues { entry -> entry.value.count { it.visibleInBadge } }
                refreshRowPendingOnly()
            }
        }
        viewModelScope.launch {
            sessions.rosterSyncState()
                .catch { emit(RosterSyncState.Failed) }
                .collect { next ->
                    _state.update { it.copy(rosterSyncState = next) }
                }
        }
        startPageLoad(startAfter = null, replace = true)
    }

    /**
     * 强制重拉名册。失败时保留已显示行，只记录 [SessionsUiState.actionError]；
     * 成功后才重置到第一页，避免刷新期间列表先闪成空态。
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true, actionError = null) }
            val result = try {
                sessions.refreshRoster(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure.toDomainError())
            }
            result.fold(
                onSuccess = {
                    _state.update { it.copy(refreshing = false) }
                    startPageLoad(startAfter = null, replace = true)
                },
                onFailure = { failure ->
                    _state.update {
                        it.copy(refreshing = false, actionError = failure.toDomainError())
                    }
                },
            )
        }
    }

    /** 按上一页的 nextKey 续跑；重复调用与到底后调用都被忽略。 */
    fun loadMore() {
        val key = nextKey ?: return
        val current = _state.value
        if (current.refreshing || current.loadingMore || current.phase == SessionsListPhase.Loading) {
            return
        }
        startPageLoad(startAfter = key, replace = false)
    }

    private fun startPageLoad(startAfter: SessionKeyset?, replace: Boolean) {
        val generation = if (replace) {
            loadGeneration += 1
            loadGeneration
        } else {
            loadGeneration
        }
        if (replace) {
            // 第一页重新加载会作废旧分页结果，顺手清掉残留的“加载更多”指示。
            _state.update { it.copy(loadingMore = false) }
        } else {
            _state.update { it.copy(loadingMore = true, loadError = null) }
        }
        viewModelScope.launch {
            val page = try {
                sessions.paged(startAfter, tuning.rosterPageSize).first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (generation == loadGeneration) {
                    onPageFailure(failure.toDomainError())
                }
                return@launch
            }
            if (generation != loadGeneration) return@launch
            onPage(page, replace)
        }
    }

    private fun onPageFailure(error: DomainError) {
        _state.update { current ->
            current.copy(
                phase = if (loadedItems.isEmpty()) {
                    SessionsListPhase.Error
                } else {
                    SessionsListPhase.Content
                },
                loadError = error,
                loadingMore = false,
            )
        }
    }

    private fun onPage(page: SessionPage, replace: Boolean) {
        nextKey = page.nextKey
        canLoadMore = page.nextKey != null
        val base = if (replace) emptyList() else loadedItems
        val seen = base.mapTo(mutableSetOf()) { it.sessionId }
        // 后台同步可能让下一页重新带回已显示过的会话：按 sessionId 去重，绝不移除已见过的行，
        // 于是 keyset 重排既不产生重复，也不会让某一项凭空消失（D19/G12 的客户端兜底）。
        loadedItems = base + page.items.filter { seen.add(it.sessionId) }
        renderRows()
    }

    /** 从 [loadedItems] 重算行并刷新状态；分页结果走这里，会一并复位 phase/loadError。 */
    private fun renderRows() {
        val rows = loadedItems.map { it.toRow(pendingBySession[it.sessionId] ?: 0) }
        _state.update {
            it.copy(
                phase = if (rows.isEmpty()) SessionsListPhase.Empty else SessionsListPhase.Content,
                rows = rows,
                canLoadMore = canLoadMore,
                loadingMore = false,
                loadError = null,
            )
        }
    }

    /** pending 单独变化时只刷新行内容，不动 phase/loadError，避免冲掉刚出现的加载错误横幅。 */
    private fun refreshRowPendingOnly() {
        val rows = loadedItems.map { it.toRow(pendingBySession[it.sessionId] ?: 0) }
        _state.update { it.copy(rows = rows) }
    }

    class Factory(
        private val sessions: SessionRepository,
        private val pending: PendingRepository,
        private val tuning: SyncTuning = SyncTuning.DEFAULT,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SessionsViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return SessionsViewModel(sessions, pending, tuning) as T
        }
    }
}

private fun SessionSummary.toRow(pendingCount: Int): SessionRow = SessionRow(
    sessionId = sessionId,
    title = title,
    updatedAtMs = updatedAtMs,
    running = running,
    archived = archived,
    historyUnsafe = historyUnsafe,
    pendingCount = pendingCount,
)
