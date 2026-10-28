package ai.deepseek.dsh.android.feature.sessions

import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.PendingRepository
import ai.deepseek.dsh.android.domain.repository.ResolvePendingResult
import ai.deepseek.dsh.android.domain.repository.RosterSyncState
import ai.deepseek.dsh.android.domain.repository.SessionKeyset
import ai.deepseek.dsh.android.domain.repository.SessionPage
import ai.deepseek.dsh.android.domain.repository.SessionRepository
import ai.deepseek.dsh.android.domain.repository.SessionSummary
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingCounts
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun firstPageThenLoadMore_dedupesBySessionIdAndStopsAtNullKey() = runTest {
        val firstKey = SessionKeyset(updatedAtMs = 300, sessionId = SessionId("s1"))
        val repository = FakeSessionRepository(
            pages = mapOf(
                null to SessionPage(
                    items = listOf(
                        summary(SessionId("s1"), "One", updatedAtMs = 300),
                        summary(SessionId("s2"), "Two", updatedAtMs = 200),
                    ),
                    nextKey = firstKey,
                ),
                firstKey to SessionPage(
                    // s2 重复出现：keyset 页之间可能因后台同步留下重叠，按 id 去重。
                    items = listOf(
                        summary(SessionId("s2"), "Two", updatedAtMs = 200),
                        summary(SessionId("s3"), "Three", updatedAtMs = 100),
                    ),
                    nextKey = null,
                ),
            ),
        )
        val viewModel = SessionsViewModel(
            sessions = repository,
            pending = FakePendingRepository(counts = counts(1)),
            tuning = SyncTuning(rosterPageSize = 2),
        )

        viewModel.state.test {
            awaitMatching { it.rows.size == 2 && it.canLoadMore }
            viewModel.loadMore()
            val state = awaitMatching { it.rows.size == 3 && !it.canLoadMore }

            assertEquals(listOf("s1", "s2", "s3"), state.rows.map { it.sessionId.value })
            assertEquals(SessionsListPhase.Content, state.phase)
            assertEquals(listOf(2, 2), repository.pageSizes)
            assertEquals(listOf(null, firstKey), repository.requested)
        }
    }

    @Test
    fun reorderedPagesAcrossPagination_doNotSkipOrDuplicate() = runTest {
        val keyA = SessionKeyset(updatedAtMs = 300, sessionId = SessionId("s1"))
        val keyB = SessionKeyset(updatedAtMs = 100, sessionId = SessionId("s3"))
        // 后台同步重排 keyset：第二页重新带回 s2，第三页又带回已显示过的 s1，都不得成为重复。
        val repository = FakeSessionRepository(
            pages = mapOf(
                null to SessionPage(
                    items = listOf(
                        summary(SessionId("s1"), "One", 300),
                        summary(SessionId("s2"), "Two", 200),
                    ),
                    nextKey = keyA,
                ),
                keyA to SessionPage(
                    items = listOf(
                        summary(SessionId("s2"), "Two", 200),
                        summary(SessionId("s3"), "Three", 100),
                    ),
                    nextKey = keyB,
                ),
                keyB to SessionPage(
                    items = listOf(
                        summary(SessionId("s1"), "One", 300),
                        summary(SessionId("s4"), "Four", 50),
                    ),
                    nextKey = null,
                ),
            ),
        )
        val viewModel = SessionsViewModel(
            sessions = repository,
            pending = FakePendingRepository(counts = counts(0)),
            tuning = SyncTuning(rosterPageSize = 2),
        )

        viewModel.state.test {
            awaitMatching { it.rows.size == 2 }
            viewModel.loadMore()
            awaitMatching { it.rows.size == 3 }
            viewModel.loadMore()
            val state = awaitMatching { it.rows.size == 4 && !it.canLoadMore }

            // 无重复、无遗漏，且首次出现顺序保持稳定。
            assertEquals(listOf("s1", "s2", "s3", "s4"), state.rows.map { it.sessionId.value })
            assertEquals(listOf(null, keyA, keyB), repository.requested)
        }
    }

    @Test
    fun loadMore_isIgnoredWhilePreviousPageInFlight() = runTest {
        val firstKey = SessionKeyset(updatedAtMs = 300, sessionId = SessionId("s1"))
        val repository = GatedSessionRepository(
            homePage = SessionPage(
                items = listOf(summary(SessionId("s1"), "One", updatedAtMs = 300)),
                nextKey = firstKey,
            ),
        )
        val viewModel = SessionsViewModel(
            sessions = repository,
            pending = FakePendingRepository(counts = counts(1)),
        )

        viewModel.state.test {
            awaitMatching { it.rows.size == 1 && it.canLoadMore }
            // 第一次调用触发一次分页并挂起在途；紧接着第二次调用必须被重入保护吞掉。
            viewModel.loadMore()
            viewModel.loadMore()
            assertEquals(listOf(null, firstKey), repository.requested)
            assertTrue(awaitMatching { it.loadingMore }.loadingMore)

            repository.nextGate.complete(
                SessionPage(
                    items = listOf(summary(SessionId("s2"), "Two", updatedAtMs = 200)),
                    nextKey = null,
                ),
            )
            val state = awaitMatching { it.rows.size == 2 && !it.canLoadMore }
            assertEquals(listOf("s1", "s2"), state.rows.map { it.sessionId.value })
            assertFalse(state.loadingMore)
            // 挂起期间没有多余的续跑请求，只发了两次 paged（首页 + 一页在途）。
            assertEquals(listOf(null, firstKey), repository.requested)
        }
    }

    @Test
    fun perRowPendingBadge_reflectsVisibleCountsAndUpdatesOnReemit() = runTest {
        val repository = FakeSessionRepository(
            pages = mapOf(
                null to SessionPage(
                    items = listOf(
                        summary(SessionId("s1"), "One", updatedAtMs = 300),
                        summary(SessionId("s2"), "Two", updatedAtMs = 200),
                    ),
                    nextKey = null,
                ),
            ),
        )
        val pending = FakePendingRepository(
            counts = counts(2),
            grouped = mapOf(
                SessionId("s1") to listOf(
                    visiblePending(SessionId("s1"), "a1"),
                    // 已解决项不计入行内徽标。
                    visiblePending(SessionId("s1"), "a2").copy(status = PendingStatus.Resolved),
                ),
            ),
        )
        val viewModel = SessionsViewModel(sessions = repository, pending = pending)

        viewModel.state.test {
            val state = awaitMatching { it.rows.size == 2 }
            // s1 只有 1 个可见 pending；s2 没有 pending（不显示徽标，但状态里计数为 0）。
            assertEquals(1, state.rows.first { it.sessionId.value == "s1" }.pendingCount)
            assertEquals(0, state.rows.first { it.sessionId.value == "s2" }.pendingCount)

            pending.submitGrouped(
                mapOf(SessionId("s2") to listOf(visiblePending(SessionId("s2"), "b1"))),
            )
            val updated = awaitMatching { it.rows.firstOrNull { r -> r.sessionId.value == "s2" }?.pendingCount == 1 }
            assertEquals(1, updated.rows.first { it.sessionId.value == "s2" }.pendingCount)
        }
    }

    @Test
    fun unknownCounts_neverBecomeTrustedEmptyOrZero() = runTest {
        val viewModel = SessionsViewModel(
            sessions = FakeSessionRepository(
                pages = mapOf(null to SessionPage(items = emptyList(), nextKey = null)),
            ),
            // badge == 0 但完整性未核对：这正是绝不能当 0 的形状（D13）。
            pending = FakePendingRepository(
                counts = PendingCounts(
                    badge = 0,
                    dismissed = 0,
                    completeness = PendingCompleteness.Unknown,
                ),
            ),
        )

        viewModel.state.test {
            val state = awaitMatching {
                it.phase == SessionsListPhase.Empty && it.counts != null
            }

            assertEquals(0, state.counts?.badge)
            assertTrue(state.countsUnknown)
            assertFalse(state.trustedEmpty)
            assertTrue(state.rows.isEmpty())
        }
    }

    @Test
    fun refreshFailure_keepsRowsAndExposesActionError() = runTest {
        val repository = FakeSessionRepository(
            pages = mapOf(
                null to SessionPage(
                    items = listOf(summary(SessionId("s1"), "One", updatedAtMs = 100)),
                    nextKey = null,
                ),
            ),
            refreshResult = Result.failure(DomainError.Unreachable),
        )
        val viewModel = SessionsViewModel(
            sessions = repository,
            pending = FakePendingRepository(counts = counts(1)),
        )

        viewModel.state.test {
            awaitMatching { it.rows.size == 1 }
            viewModel.refresh()
            val state = awaitMatching { it.actionError != null }

            assertEquals(DomainError.Unreachable, state.actionError)
            assertEquals(listOf("s1"), state.rows.map { it.sessionId.value })
            assertEquals(SessionsListPhase.Content, state.phase)
            assertFalse(state.refreshing)
            assertEquals(listOf(true), repository.refreshForces)
        }
    }

    private fun counts(badge: Int): PendingCounts = PendingCounts(
        badge = badge,
        dismissed = 0,
        completeness = PendingCompleteness.Complete,
    )
}

private suspend fun ReceiveTurbine<SessionsUiState>.awaitMatching(
    predicate: (SessionsUiState) -> Boolean,
): SessionsUiState {
    var current = awaitItem()
    while (!predicate(current)) {
        current = awaitItem()
    }
    return current
}

private class FakeSessionRepository(
    private val pages: Map<SessionKeyset?, SessionPage>,
    private val refreshResult: Result<Unit> = Result.success(Unit),
) : SessionRepository {

    val pageSizes = mutableListOf<Int>()
    val requested = mutableListOf<SessionKeyset?>()
    val refreshForces = mutableListOf<Boolean>()

    override fun paged(startAfter: SessionKeyset?, pageSize: Int): Flow<SessionPage> {
        requested += startAfter
        pageSizes += pageSize
        return flowOf(pages[startAfter] ?: SessionPage(items = emptyList(), nextKey = null))
    }

    override fun byId(sessionId: SessionId): Flow<SessionSummary?> = flowOf(null)

    override fun title(sessionId: SessionId): Flow<String?> = flowOf(null)

    override fun running(sessionId: SessionId): Flow<Boolean> = flowOf(false)

    override fun archived(sessionId: SessionId): Flow<Boolean> = flowOf(false)

    override fun historyUnsafe(sessionId: SessionId): Flow<Boolean> = flowOf(false)

    override fun rosterSyncState(): Flow<RosterSyncState> = flowOf(RosterSyncState.Fresh)

    override suspend fun refreshRoster(force: Boolean): Result<Unit> {
        refreshForces += force
        return refreshResult
    }
}

/** 第二页挂起在 [nextGate] 上，用来验证 loadMore 的重入保护。 */
private class GatedSessionRepository(
    private val homePage: SessionPage,
) : SessionRepository {

    val requested = mutableListOf<SessionKeyset?>()
    val nextGate = CompletableDeferred<SessionPage>()

    override fun paged(startAfter: SessionKeyset?, pageSize: Int): Flow<SessionPage> {
        requested += startAfter
        return if (startAfter == null) {
            flowOf(homePage)
        } else {
            flow { emit(nextGate.await()) }
        }
    }

    override fun byId(sessionId: SessionId): Flow<SessionSummary?> = flowOf(null)

    override fun title(sessionId: SessionId): Flow<String?> = flowOf(null)

    override fun running(sessionId: SessionId): Flow<Boolean> = flowOf(false)

    override fun archived(sessionId: SessionId): Flow<Boolean> = flowOf(false)

    override fun historyUnsafe(sessionId: SessionId): Flow<Boolean> = flowOf(false)

    override fun rosterSyncState(): Flow<RosterSyncState> = flowOf(RosterSyncState.Fresh)

    override suspend fun refreshRoster(force: Boolean): Result<Unit> = Result.success(Unit)
}

private class FakePendingRepository(
    counts: PendingCounts,
    grouped: Map<SessionId, List<PendingRequest>> = emptyMap(),
) : PendingRepository {

    private val countsFlow = MutableStateFlow(counts)
    private val groupedFlow = MutableStateFlow(grouped)

    fun submitGrouped(next: Map<SessionId, List<PendingRequest>>) {
        groupedFlow.value = next
    }

    override fun groupedBySession(): Flow<Map<SessionId, List<PendingRequest>>> = groupedFlow

    override fun forSession(sessionId: SessionId): Flow<List<PendingRequest>> =
        groupedFlow.map { it[sessionId] ?: emptyList() }

    override fun counts(): Flow<PendingCounts> = countsFlow

    override suspend fun dismiss(kind: PendingKind, requestId: RequestId): Result<Unit> =
        Result.success(Unit)

    override suspend fun undismiss(kind: PendingKind, requestId: RequestId): Result<Unit> =
        Result.success(Unit)

    override suspend fun resolve(
        sessionId: SessionId,
        kind: PendingKind,
        requestId: RequestId,
        valueJson: String,
        idempotencyKey: String,
    ): Result<ResolvePendingResult> =
        error("resolve is out of scope for the read-only session list")
}

private fun summary(
    sessionId: SessionId,
    title: String,
    updatedAtMs: Long,
): SessionSummary = SessionSummary(
    sessionId = sessionId,
    title = title,
    updatedAtMs = updatedAtMs,
    running = false,
    archived = false,
    historyUnsafe = false,
    workspaceId = null,
)

private fun visiblePending(
    sessionId: SessionId,
    requestId: String,
): PendingRequest = PendingRequest(
    serverId = ServerId("srv-test"),
    kind = PendingKind.Approval,
    requestId = RequestId(requestId),
    sessionId = sessionId,
    status = PendingStatus.Pending,
    completeness = PendingCompleteness.Complete,
    payloadJson = "{}",
    firstSeenAtMs = 0L,
    createdAtMs = 0L,
)
