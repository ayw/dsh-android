package ai.deepseek.dsh.android.feature.conversation

import ai.deepseek.dsh.android.domain.PendingContentDecoder
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.HistoryWindow
import ai.deepseek.dsh.android.domain.repository.PendingRepository
import ai.deepseek.dsh.android.domain.repository.ResolvePendingResult
import ai.deepseek.dsh.android.domain.repository.TimelineGapState
import ai.deepseek.dsh.android.domain.repository.TimelineRepository
import ai.deepseek.dsh.android.model.EventSeq
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
import ai.deepseek.dsh.android.model.TimelineItem
import ai.deepseek.dsh.android.model.TimelineSnapshot
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
class ConversationViewModelTest {

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
    fun pendingContent_mapsDecodedApprovalAndQuestion() = runTest {
        val approvalItem = anchor("a1", 10, "approval-1", PendingKind.Approval)
        val questionItem = anchor("q1", 11, "question-1", PendingKind.Question)
        val viewModel = viewModel(
            timeline = FakeTimeline(MutableStateFlow(snapshot(approvalItem, questionItem))),
            pending = FakePending(
                listOf(
                    pendingRow("approval-1", PendingKind.Approval, "approval"),
                    pendingRow("question-1", PendingKind.Question, "question"),
                ),
            ),
            decoder = FakeDecoder { kind, payload ->
                when (payload) {
                    "approval" -> PendingContent.Approval(
                        toolName = "write_file",
                        reason = "edits docs",
                    )

                    "question" -> PendingContent.Question(
                        listOf(
                            QuestionPrompt(
                                questionId = "scope",
                                header = "Scope",
                                text = "Which scope?",
                                multiSelect = true,
                                options = listOf(
                                    QuestionOption(label = "repo", description = null),
                                    QuestionOption(label = "tests", description = "unit tests"),
                                ),
                            ),
                        ),
                    )

                    else -> PendingContent.Undecodable(kind)
                }
            },
        )

        viewModel.state.test {
            val state = awaitMatching { it.pendingContents.size == 2 }

            val approval = requireNotNull(state.pendingContents["a1"])
            assertEquals("write_file", approval.title)
            assertEquals("edits docs", approval.detail)
            assertTrue(approval.decisionOptions.isEmpty())

            val question = requireNotNull(state.pendingContents["q1"])
            val field = question.fields.single()
            assertEquals("scope", field.id)
            assertEquals("Which scope?", field.label)
            assertEquals(listOf("repo", "tests"), field.options.map { it.label })
            assertTrue(field.multiSelect)
        }
    }

    @Test
    fun undecodableOrThrowingPayload_yieldsNullContent() = runTest {
        val undecodable = anchor("u1", 10, "undecodable", PendingKind.Approval)
        val throwing = anchor("u2", 11, "throwing", PendingKind.Question)
        val viewModel = viewModel(
            timeline = FakeTimeline(MutableStateFlow(snapshot(undecodable, throwing))),
            pending = FakePending(
                listOf(
                    pendingRow("undecodable", PendingKind.Approval, "garbage"),
                    pendingRow("throwing", PendingKind.Question, "boom"),
                ),
            ),
            decoder = FakeDecoder { kind, payload ->
                if (payload == "boom") error("fixture payload is not decodable")
                PendingContent.Undecodable(kind)
            },
        )

        viewModel.state.test {
            val state = awaitMatching { it.pendingContents.size == 2 }

            assertTrue(state.pendingContents.containsKey("u1"))
            assertNull(state.pendingContents["u1"])
            assertTrue(state.pendingContents.containsKey("u2"))
            assertNull(state.pendingContents["u2"])
        }
    }

    @Test
    fun loadOlder_usesOldestSeqAndDedupesByKey() = runTest {
        val timeline = FakeTimeline(
            snapshots = MutableStateFlow(snapshot(user("u10", 10), user("u11", 11))),
            pages = mutableListOf(
                HistoryWindow(
                    sessionId = sessionId,
                    items = listOf(user("u5", 5), user("u6", 6), user("u10", 10)),
                    nextBeforeSeq = EventSeq(5),
                    hasMore = true,
                ),
                HistoryWindow(
                    sessionId = sessionId,
                    items = listOf(user("u2", 2)),
                    nextBeforeSeq = EventSeq(2),
                    hasMore = false,
                ),
            ),
        )
        val viewModel = viewModel(timeline = timeline)

        viewModel.state.test {
            awaitMatching { !it.initialLoading && it.items.size == 2 }

            viewModel.loadOlder()
            val first = awaitMatching { it.items.size == 4 && !it.historyLoading }
            assertEquals(listOf("u5", "u6", "u10", "u11"), first.items.map { it.key })
            assertEquals(
                listOf<Pair<EventSeq?, Int>>(EventSeq(10) to SyncTuning.DEFAULT.historyPageSize),
                timeline.requests,
            )
            assertTrue(first.hasMoreHistory)

            viewModel.loadOlder()
            val second = awaitMatching { it.items.size == 5 && !it.historyLoading }
            assertEquals(listOf("u2", "u5", "u6", "u10", "u11"), second.items.map { it.key })
            assertEquals(EventSeq(5), timeline.requests[1].first)
            assertFalse(second.hasMoreHistory)
        }
    }

    @Test
    fun gapState_propagatesEachVariant() = runTest {
        val gaps = MutableStateFlow<TimelineGapState>(
            TimelineGapState.Missing(expectedSeq = EventSeq(7), arrivedSeq = EventSeq(9)),
        )
        val viewModel = viewModel(
            timeline = FakeTimeline(
                snapshots = MutableStateFlow(snapshot(user("u1", 1))),
                gaps = gaps,
            ),
        )

        viewModel.state.test {
            val missing = awaitMatching {
                it.gapState is TimelineGapState.Missing &&
                    ConversationNoticeKind.SeqMissing in it.notices
            }
            val gap = missing.gapState as TimelineGapState.Missing
            assertEquals(EventSeq(7), gap.expectedSeq)
            assertEquals(EventSeq(9), gap.arrivedSeq)
            assertTrue(ConversationNoticeKind.VisibleHistoryNotContext in missing.notices)

            gaps.value = TimelineGapState.HistoryIncomplete
            val incomplete = awaitMatching {
                it.gapState is TimelineGapState.HistoryIncomplete &&
                    ConversationNoticeKind.HistoryIncomplete in it.notices
            }
            assertFalse(ConversationNoticeKind.SeqMissing in incomplete.notices)

            gaps.value = TimelineGapState.HistoryUnsafe("session/mutation")
            val unsafe = awaitMatching {
                it.gapState is TimelineGapState.HistoryUnsafe &&
                    ConversationNoticeKind.HistoryUnsafe in it.notices
            }
            assertEquals("session/mutation", unsafe.historyUnsafeType)
            assertFalse(ConversationNoticeKind.HistoryIncomplete in unsafe.notices)
        }
    }

    @Test
    fun dismissFailure_surfacesActionErrorAndNeverResolves() = runTest {
        val pending = FakePending(
            initial = listOf(pendingRow("approval-1", PendingKind.Approval, "approval")),
            dismissResult = Result.failure(DomainError.Timeout),
        )
        val viewModel = viewModel(
            timeline = FakeTimeline(
                MutableStateFlow(snapshot(anchor("a1", 1, "approval-1", PendingKind.Approval))),
            ),
            pending = pending,
            decoder = FakeDecoder { _, _ ->
                PendingContent.Approval(toolName = "shell", reason = null)
            },
        )

        viewModel.state.test {
            awaitMatching { it.pendingContents.isNotEmpty() }

            viewModel.dismiss(PendingKind.Approval, RequestId("approval-1"))
            val state = awaitMatching { it.actionError != null }

            assertEquals(DomainError.Timeout, state.actionError)
            assertEquals(
                listOf(PendingKind.Approval to RequestId("approval-1")),
                pending.dismissCalls,
            )
            assertTrue(state.pendingContents.containsKey("a1"))
        }
        assertEquals(0, pending.resolveCalls)
    }

    @Test
    fun toggleGroup_flipsExpansion() = runTest {
        val viewModel = viewModel(
            timeline = FakeTimeline(MutableStateFlow(snapshot(user("u1", 1)))),
        )

        viewModel.state.test {
            awaitMatching { !it.initialLoading }

            viewModel.toggleGroup("group-1")
            assertTrue(awaitMatching { "group-1" in it.expandedGroups }.expandedGroups.contains("group-1"))

            viewModel.toggleGroup("group-1")
            assertFalse(awaitMatching { "group-1" !in it.expandedGroups }.expandedGroups.contains("group-1"))
        }
    }

    private fun viewModel(
        timeline: TimelineRepository,
        pending: PendingRepository = FakePending(emptyList()),
        decoder: PendingContentDecoder = FakeDecoder { kind, _ -> PendingContent.Undecodable(kind) },
    ): ConversationViewModel = ConversationViewModel(
        sessionId = sessionId,
        timeline = timeline,
        pending = pending,
        decoder = decoder,
        tuning = SyncTuning.DEFAULT,
    )

    private companion object {
        val sessionId = SessionId("session")
    }
}

private suspend fun ReceiveTurbine<ConversationUiState>.awaitMatching(
    predicate: (ConversationUiState) -> Boolean,
): ConversationUiState {
    var current = awaitItem()
    while (!predicate(current)) {
        current = awaitItem()
    }
    return current
}

private class FakeDecoder(
    private val block: (PendingKind, String) -> PendingContent,
) : PendingContentDecoder {
    override fun decode(kind: PendingKind, payloadJson: String): PendingContent =
        block(kind, payloadJson)
}

private class FakeTimeline(
    private val snapshots: MutableStateFlow<TimelineSnapshot>,
    private val gaps: MutableStateFlow<TimelineGapState> = MutableStateFlow(TimelineGapState.FullyCovered),
    private val pages: MutableList<HistoryWindow> = mutableListOf(),
) : TimelineRepository {

    val requests = mutableListOf<Pair<EventSeq?, Int>>()

    override fun snapshot(sessionId: SessionId): Flow<TimelineSnapshot> = snapshots

    override fun gapState(sessionId: SessionId): Flow<TimelineGapState> = gaps

    override suspend fun historyPage(
        sessionId: SessionId,
        beforeSeq: EventSeq?,
        maxMessages: Int,
    ): Result<HistoryWindow> {
        requests += beforeSeq to maxMessages
        if (pages.isEmpty()) return Result.failure(DomainError.Rejected("no page fixture"))
        return Result.success(pages.removeAt(0))
    }
}

private class FakePending(
    initial: List<PendingRequest>,
    private val dismissResult: Result<Unit> = Result.success(Unit),
    private val undismissResult: Result<Unit> = Result.success(Unit),
) : PendingRepository {

    private val rows = MutableStateFlow(initial)
    val dismissCalls = mutableListOf<Pair<PendingKind, RequestId>>()
    val undismissCalls = mutableListOf<Pair<PendingKind, RequestId>>()
    var resolveCalls = 0
        private set

    override fun groupedBySession(): Flow<Map<SessionId, List<PendingRequest>>> =
        rows.map { list -> list.groupBy { it.sessionId } }

    override fun forSession(sessionId: SessionId): Flow<List<PendingRequest>> =
        rows.map { list -> list.filter { it.sessionId == sessionId } }

    override fun counts(): Flow<PendingCounts> = rows.map { list ->
        PendingCounts(
            badge = list.count { it.visibleInBadge },
            dismissed = list.count { it.dismissedAtMs != null },
            completeness = PendingCompleteness.Complete,
        )
    }

    override suspend fun dismiss(kind: PendingKind, requestId: RequestId): Result<Unit> {
        dismissCalls += kind to requestId
        return dismissResult
    }

    override suspend fun undismiss(kind: PendingKind, requestId: RequestId): Result<Unit> {
        undismissCalls += kind to requestId
        return undismissResult
    }

    override suspend fun resolve(
        sessionId: SessionId,
        kind: PendingKind,
        requestId: RequestId,
        valueJson: String,
        idempotencyKey: String,
    ): Result<ResolvePendingResult> {
        resolveCalls += 1
        return Result.failure(DomainError.Rejected("M4 is read-only"))
    }
}

private fun snapshot(vararg items: TimelineItem): TimelineSnapshot = TimelineSnapshot(
    sessionId = SessionId("session"),
    items = items.toList(),
    contiguousSeq = EventSeq(20),
    hasGap = false,
    historyUnsafe = false,
    runningTurn = false,
)

private fun anchor(
    key: String,
    seq: Long,
    requestId: String,
    kind: PendingKind,
): TimelineItem.PendingAnchor = TimelineItem.PendingAnchor(
    key = key,
    primarySeq = EventSeq(seq),
    requestId = RequestId(requestId),
    kind = kind,
    anchoredToCallId = null,
)

private fun user(key: String, seq: Long): TimelineItem.User = TimelineItem.User(
    key = key,
    primarySeq = EventSeq(seq),
    text = "message $key",
    wallClockMs = 1L,
)

private fun pendingRow(
    requestId: String,
    kind: PendingKind,
    payloadJson: String,
): PendingRequest = PendingRequest(
    serverId = ServerId("server"),
    kind = kind,
    requestId = RequestId(requestId),
    sessionId = SessionId("session"),
    status = PendingStatus.Pending,
    completeness = PendingCompleteness.Complete,
    payloadJson = payloadJson,
    firstSeenAtMs = 1L,
    createdAtMs = 1L,
)
