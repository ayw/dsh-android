package ai.deepseek.dsh.android.feature.inbox

import ai.deepseek.dsh.android.domain.PendingContentDecoder
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.PendingRepository
import ai.deepseek.dsh.android.domain.repository.ResolvePendingResult
import ai.deepseek.dsh.android.domain.repository.RosterSyncState
import ai.deepseek.dsh.android.domain.repository.SessionKeyset
import ai.deepseek.dsh.android.domain.repository.SessionPage
import ai.deepseek.dsh.android.domain.repository.SessionRepository
import ai.deepseek.dsh.android.domain.repository.SessionSummary
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingContent
import ai.deepseek.dsh.android.model.PendingCounts
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.QuestionOption
import ai.deepseek.dsh.android.model.QuestionPrompt
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {

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
    fun groupingAndOrder_followEarliestFirstSeen() = runTest {
        val sessionA = SessionId("a")
        val sessionB = SessionId("b")
        val rows = listOf(
            pending(sessionA, "a2", firstSeenAtMs = 300),
            pending(sessionA, "a1", firstSeenAtMs = 100),
            pending(sessionB, "b1", firstSeenAtMs = 200),
        )
        val viewModel = viewModel(
            pending = FakePendingRepository(rows, countsOf(rows)),
            sessions = FakeSessionRepository(
                mapOf(
                    sessionA to summary(sessionA, "Alpha"),
                    sessionB to summary(sessionB, "Beta"),
                ),
            ),
        )

        viewModel.state.test {
            val state = awaitMatching { it.sections.size == 2 && it.sections.all { s -> s.title != null } }

            assertFalse(state.loading)
            assertEquals(sessionA, state.sections[0].sessionId)
            assertEquals(sessionB, state.sections[1].sessionId)
            assertEquals(listOf("Alpha", "Beta"), state.sections.map { it.title })
            assertEquals(listOf("a1", "a2"), state.sections[0].cards.map { it.requestId.value })
            assertEquals(listOf("b1"), state.sections[1].cards.map { it.requestId.value })
            assertTrue(state.sections[0].cards.all { it.content is PendingContent.Approval })
        }
    }

    @Test
    fun unknownCompleteness_isUnknownNotEmpty() = runTest {
        val viewModel = viewModel(
            pending = FakePendingRepository(
                initial = emptyList(),
                initialCounts = PendingCounts(
                    badge = 0,
                    dismissed = 0,
                    completeness = PendingCompleteness.Unknown,
                ),
            ),
            sessions = FakeSessionRepository(emptyMap()),
        )

        viewModel.state.test {
            val state = awaitMatching { !it.loading && it.counts != null }
            assertEquals(0, state.counts?.badge)
            assertTrue(state.countsUnknown)
            assertFalse(state.trustedEmpty)
            assertEquals(0, state.visibleCount)
        }
    }

    @Test
    fun undecodableAndThrowingDecoder_stillRenderRow() = runTest {
        val rows = listOf(
            pending(SessionId("s"), "bad", firstSeenAtMs = 100, payloadJson = "garbage"),
            pending(SessionId("s"), "boom", firstSeenAtMs = 200, payloadJson = "throw"),
        )
        val viewModel = viewModel(
            pending = FakePendingRepository(rows, countsOf(rows)),
            sessions = FakeSessionRepository(mapOf(SessionId("s") to summary(SessionId("s"), "S"))),
        )

        viewModel.state.test {
            val state = awaitMatching { it.visibleCount == 2 }
            val cards = state.sections.single().cards
            assertEquals(listOf("bad", "boom"), cards.map { it.requestId.value })
            assertTrue(cards.all { it.content is PendingContent.Undecodable })
            assertEquals(PendingKind.Approval, cards[0].kind)
        }
    }

    @Test
    fun dismiss_movesRowToDismissedAndDropsBadge() = runTest {
        val session = SessionId("s")
        val row = pending(session, "r1", firstSeenAtMs = 100)
        val repository = FakePendingRepository(listOf(row), countsOf(listOf(row)))
        val viewModel = viewModel(
            pending = repository,
            sessions = FakeSessionRepository(mapOf(session to summary(session, "S"))),
        )

        viewModel.state.test {
            awaitMatching { it.visibleCount == 1 }
            viewModel.dismiss(PendingKind.Approval, RequestId("r1"))
            val state = awaitMatching { it.dismissed.size == 1 && it.counts?.badge == 0 }

            assertEquals(listOf(PendingKind.Approval to RequestId("r1")), repository.dismissCalls)
            assertEquals(0, state.visibleCount)
            assertEquals(0, state.counts?.badge)
            assertEquals(1, state.counts?.dismissed)
            assertEquals(RequestId("r1"), state.dismissed.single().requestId)
            assertTrue(state.showDismissed)
        }
    }

    @Test
    fun undismiss_restoresRowAndBadge() = runTest {
        val session = SessionId("s")
        val row = pending(session, "r1", firstSeenAtMs = 100, dismissedAtMs = 1)
        val repository = FakePendingRepository(listOf(row), countsOf(listOf(row)))
        val viewModel = viewModel(
            pending = repository,
            sessions = FakeSessionRepository(mapOf(session to summary(session, "S"))),
        )

        viewModel.state.test {
            awaitMatching { it.dismissed.size == 1 }
            viewModel.undismiss(PendingKind.Approval, RequestId("r1"))
            val state = awaitMatching { it.visibleCount == 1 && it.counts?.badge == 1 }

            assertEquals(listOf(PendingKind.Approval to RequestId("r1")), repository.undismissCalls)
            assertTrue(state.dismissed.isEmpty())
            assertEquals(1, state.counts?.badge)
            assertEquals(0, state.counts?.dismissed)
        }
    }

    @Test
    fun countsFailure_surfacesTypedErrorAndKeepsRows() = runTest {
        val session = SessionId("s")
        val row = pending(session, "r1", firstSeenAtMs = 100)
        val viewModel = viewModel(
            pending = FakePendingRepository(
                initial = listOf(row),
                initialCounts = countsOf(listOf(row)),
                countsFailure = DomainError.Unreachable,
            ),
            sessions = FakeSessionRepository(mapOf(session to summary(session, "S"))),
        )

        viewModel.state.test {
            val state = awaitMatching {
                it.countsError != null && it.sections.singleOrNull()?.cards?.size == 1
            }

            assertEquals(DomainError.Unreachable, state.countsError)
            assertNull(state.rowsError)
            assertNull(state.counts)
            assertEquals("r1", state.sections.single().cards.single().requestId.value)
        }
    }

    private fun viewModel(
        pending: PendingRepository,
        sessions: SessionRepository,
    ): InboxViewModel = InboxViewModel(
        pending = pending,
        sessions = sessions,
        decoder = FakeDecoder(),
    )
}

private suspend fun ReceiveTurbine<InboxUiState>.awaitMatching(
    predicate: (InboxUiState) -> Boolean,
): InboxUiState {
    var current = awaitItem()
    while (!predicate(current)) {
        current = awaitItem()
    }
    return current
}

private class FakeDecoder : PendingContentDecoder {
    val calls = mutableListOf<String>()

    override fun decode(kind: PendingKind, payloadJson: String): PendingContent {
        calls += payloadJson
        return when (payloadJson) {
            "question" -> PendingContent.Question(
                listOf(
                    QuestionPrompt(
                        questionId = "q1",
                        header = "Scope",
                        text = "Which scope?",
                        multiSelect = false,
                        options = listOf(QuestionOption(label = "repo", description = null)),
                    ),
                ),
            )

            "throw" -> throw IllegalStateException("fixture payload is not decodable")
            "garbage" -> PendingContent.Undecodable(kind)
            else -> PendingContent.Approval(toolName = "shell", reason = "fixture approval")
        }
    }
}

private class FakePendingRepository(
    initial: List<PendingRequest>,
    initialCounts: PendingCounts,
    private val countsFailure: DomainError? = null,
) : PendingRepository {

    private val rows = MutableStateFlow(initial)
    private val countsState = MutableStateFlow(initialCounts)
    val dismissCalls = mutableListOf<Pair<PendingKind, RequestId>>()
    val undismissCalls = mutableListOf<Pair<PendingKind, RequestId>>()

    override fun groupedBySession(): Flow<Map<SessionId, List<PendingRequest>>> =
        rows.map { list -> list.groupBy { it.sessionId } }

    override fun forSession(sessionId: SessionId): Flow<List<PendingRequest>> =
        rows.map { list -> list.filter { it.sessionId == sessionId } }

    override fun counts(): Flow<PendingCounts> {
        val failure = countsFailure
        return if (failure == null) countsState else flow<PendingCounts> { throw failure }
    }

    override suspend fun dismiss(kind: PendingKind, requestId: RequestId): Result<Unit> {
        dismissCalls += kind to requestId
        rows.update { list ->
            list.map {
                if (it.kind == kind && it.requestId == requestId) it.copy(dismissedAtMs = 1) else it
            }
        }
        syncCounts()
        return Result.success(Unit)
    }

    override suspend fun undismiss(kind: PendingKind, requestId: RequestId): Result<Unit> {
        undismissCalls += kind to requestId
        rows.update { list ->
            list.map {
                if (it.kind == kind && it.requestId == requestId) it.copy(dismissedAtMs = null) else it
            }
        }
        syncCounts()
        return Result.success(Unit)
    }

    override suspend fun resolve(
        sessionId: SessionId,
        kind: PendingKind,
        requestId: RequestId,
        valueJson: String,
        idempotencyKey: String,
    ): Result<ResolvePendingResult> = error("resolve is out of scope for the read-only inbox")

    private fun syncCounts() {
        countsState.value = PendingCounts(
            badge = rows.value.count { it.visibleInBadge },
            dismissed = rows.value.count { it.dismissedAtMs != null },
            completeness = countsState.value.completeness,
        )
    }
}

private class FakeSessionRepository(
    private val summaries: Map<SessionId, SessionSummary>,
) : SessionRepository {

    override fun paged(startAfter: SessionKeyset?, pageSize: Int): Flow<SessionPage> =
        flowOf(SessionPage(items = emptyList(), nextKey = null))

    override fun byId(sessionId: SessionId): Flow<SessionSummary?> =
        flowOf(summaries[sessionId])

    override fun title(sessionId: SessionId): Flow<String?> =
        byId(sessionId).map { it?.title }

    override fun running(sessionId: SessionId): Flow<Boolean> =
        byId(sessionId).map { it?.running == true }

    override fun archived(sessionId: SessionId): Flow<Boolean> =
        byId(sessionId).map { it?.archived == true }

    override fun historyUnsafe(sessionId: SessionId): Flow<Boolean> =
        byId(sessionId).map { it?.historyUnsafe == true }

    override fun rosterSyncState(): Flow<RosterSyncState> = flowOf(RosterSyncState.Fresh)

    override suspend fun refreshRoster(force: Boolean): Result<Unit> = Result.success(Unit)
}

private fun pending(
    sessionId: SessionId,
    requestId: String,
    firstSeenAtMs: Long,
    payloadJson: String = "approval",
    dismissedAtMs: Long? = null,
): PendingRequest = PendingRequest(
    serverId = ServerId("server"),
    kind = PendingKind.Approval,
    requestId = RequestId(requestId),
    sessionId = sessionId,
    status = PendingStatus.Pending,
    completeness = PendingCompleteness.Complete,
    payloadJson = payloadJson,
    firstSeenAtMs = firstSeenAtMs,
    createdAtMs = firstSeenAtMs,
    dismissedAtMs = dismissedAtMs,
)

private fun countsOf(
    rows: List<PendingRequest>,
    completeness: PendingCompleteness = PendingCompleteness.Complete,
): PendingCounts = PendingCounts(
    badge = rows.count { it.visibleInBadge },
    dismissed = rows.count { it.dismissedAtMs != null },
    completeness = completeness,
)

private fun summary(sessionId: SessionId, title: String): SessionSummary = SessionSummary(
    sessionId = sessionId,
    title = title,
    updatedAtMs = 1,
    running = false,
    archived = false,
    historyUnsafe = false,
    workspaceId = null,
)
