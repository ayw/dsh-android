package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.DurableStore
import ai.deepseek.dsh.android.domain.FakeClock
import ai.deepseek.dsh.android.domain.PendingStore
import ai.deepseek.dsh.android.domain.RemoteSource
import ai.deepseek.dsh.android.domain.SessionStore
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.PendingRepository
import ai.deepseek.dsh.android.domain.repository.ResolvePendingResult
import ai.deepseek.dsh.android.domain.repository.RosterSyncState
import ai.deepseek.dsh.android.domain.repository.SessionKeyset
import ai.deepseek.dsh.android.domain.repository.SyncReason
import ai.deepseek.dsh.android.domain.repository.SyncRequestPort
import ai.deepseek.dsh.android.domain.usecase.ResolvePendingUseCase
import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.EventBatch
import ai.deepseek.dsh.android.model.HistoryPage
import ai.deepseek.dsh.android.model.HistoryRequest
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingCounts
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.PollRequest
import ai.deepseek.dsh.android.model.PromptRequest
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ResolveRequest
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.StreamHandle
import ai.deepseek.dsh.android.model.TransportCursor
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.model.WriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

/**
 * 纯 JVM：只用手写 fake 验证读侧仓储的边界与“未知不伪装成 0/成功”的不变量。
 * 不启动 Room、不依赖 Robolectric —— 生产 adapter 是独立的薄类，不参与本测试的类加载。
 */
class RoomRepositoriesTest {

    // ── 会话分页 ──────────────────────────────────────────────

    @Test
    fun `paging follows the roster rank keyset and only emits nextKey on a full page`() = runTest {
        val rows = listOf(
            roster("s-1", rank = 9, updatedAt = 100),
            roster("s-2", rank = 7, updatedAt = 200),
            roster("s-3", rank = 5, updatedAt = 300),
        )
        val store = FakeSessionStore(rows)
        val repo = sessionRepository(store, unsafe = setOf(SESSION_2))

        val first = repo.paged(startAfter = null, pageSize = 2).first()
        assertEquals(listOf(SESSION_1, SESSION_2), first.items.map { it.sessionId })
        assertEquals(SessionKeyset(updatedAtMs = 7, sessionId = SESSION_2), first.nextKey)
        // 首屏不传 offset，用最大 rank 进入。
        assertEquals(Long.MAX_VALUE, store.lastOffsetRank)
        assertTrue(first.items.single { it.sessionId == SESSION_2 }.historyUnsafe)
        assertFalse(first.items.single { it.sessionId == SESSION_1 }.historyUnsafe)

        val second = repo.paged(startAfter = first.nextKey, pageSize = 2).first()
        assertEquals(listOf(SESSION_3), second.items.map { it.sessionId })
        assertNull(second.nextKey)
        assertEquals(7L, store.lastOffsetRank)

        // 满页 = 可能还有下一页，即使正好只剩这些行；半页 = 明确到顶。
        val full = repo.paged(startAfter = null, pageSize = 3).first()
        assertEquals(SessionKeyset(updatedAtMs = 5, sessionId = SESSION_3), full.nextKey)
    }

    @Test
    fun `roster sync state is unknown before the first sync and ages into stale`() = runTest {
        val clock = FakeClock(startWallClockMs = 10_000_000L)
        val sync = MutableStateFlow<Long?>(null)
        val repo = sessionRepository(
            store = FakeSessionStore(emptyList()),
            clock = clock,
            syncInfo = object : RosterSyncInfo {
                override fun lastSyncedAtMs(): Flow<Long?> = sync
            },
        )

        assertEquals(RosterSyncState.Unknown, repo.rosterSyncState().first())

        sync.value = clock.wallClockMs() - 1_000L
        assertEquals(RosterSyncState.Fresh, repo.rosterSyncState().first())

        clock.advance(SyncTuning.DEFAULT.rosterForegroundStaleMs + 1_000L)
        assertEquals(RosterSyncState.Stale, repo.rosterSyncState().first())
    }

    @Test
    fun `refresh without a wired callable fails instead of claiming success`() = runTest {
        val repo = sessionRepository(FakeSessionStore(emptyList()))
        val result = repo.refreshRoster(force = true)
        assertTrue(result.isFailure)
        assertIs<DomainError.UnavailableCapability>(result.exceptionOrNull())
    }

    // ── pending 计数 ─────────────────────────────────────────

    @Test
    fun `counts stay unknown until a baseline sweep actually runs`() = runTest {
        val dao = FakePendingRoomDao()
        val repo = pendingRepository(dao)
        dao.badge.value = PendingBadgeSnapshot(badge = 0, dismissed = 0, pendingKeyCount = 0, lastSweptAtMs = null)

        val counts = repo.counts().first()
        assertEquals(PendingCompleteness.Unknown, counts.completeness)
        assertNull(counts.badge, "a never-swept set must render unknown, not 0")
        assertTrue(counts.showsUnknown)
    }

    @Test
    fun `a row with unknown completeness nulls the badge`() = runTest {
        val dao = FakePendingRoomDao()
        dao.rows.value = listOf(pendingRequest(PendingCompleteness.Unknown))
        dao.badge.value = PendingBadgeSnapshot(badge = 1, dismissed = 0, pendingKeyCount = 1, lastSweptAtMs = 42)
        val repo = pendingRepository(dao)

        val counts = repo.counts().first()
        assertEquals(PendingCompleteness.Unknown, counts.completeness)
        assertNull(counts.badge)
    }

    @Test
    fun `a completed sweep exposes the numeric badge`() = runTest {
        val dao = FakePendingRoomDao()
        dao.rows.value = listOf(pendingRequest(PendingCompleteness.Complete))
        dao.badge.value = PendingBadgeSnapshot(badge = 2, dismissed = 1, pendingKeyCount = 2, lastSweptAtMs = 42)
        val repo = pendingRepository(dao)

        val counts = repo.counts().first()
        assertEquals(PendingCompleteness.Complete, counts.completeness)
        assertEquals(2, counts.badge)
        assertEquals(1, counts.dismissed)
        assertFalse(counts.showsUnknown)
    }

    @Test
    fun `a skipped sweep degrades completeness even with complete rows`() = runTest {
        val dao = FakePendingRoomDao()
        dao.rows.value = listOf(pendingRequest(PendingCompleteness.Complete))
        dao.badge.value = PendingBadgeSnapshot(
            badge = 1,
            dismissed = 0,
            pendingKeyCount = SyncTuning.DEFAULT.baselineKeySweepSkipThreshold + 1,
            lastSweptAtMs = 42,
        )
        val repo = pendingRepository(dao)

        val counts = repo.counts().first()
        assertEquals(PendingCompleteness.Unknown, counts.completeness)
        assertNull(counts.badge)
    }

    @Test
    fun `dismissing a missing row is a typed failure but an already-dismissed row succeeds`() = runTest {
        val dao = FakePendingRoomDao()
        val repo = pendingRepository(dao)

        dao.exists = false
        assertTrue(repo.dismiss(PendingKind.Approval, RequestId("r-1")).isFailure)

        dao.exists = true // DAO 更新 0 行但行还在：已经划掉，幂等成功。
        assertTrue(repo.dismiss(PendingKind.Approval, RequestId("r-1")).isSuccess)
    }

    // ── 后端描述符 ───────────────────────────────────────────

    @Test
    fun `server descriptors never expose credentials or identity digests`() = runTest {
        val capabilities = MutableStateFlow<BackendCapabilities?>(null)
        val repo = RoomBackendRepository(
            servers = object : ServerSource {
                override fun observeServers(): Flow<List<ServerRecord>> =
                    flowOf(listOf(ServerRecord(SERVER, label = "home", storedBase = "https://gw.example")))
            },
            authStates = { flowOf(AuthState.Connected) },
            capabilityFlows = { capabilities },
        )

        val descriptor = repo.servers().first().single()
        assertEquals(SERVER, descriptor.serverId)
        assertEquals("home", descriptor.label)
        assertEquals("https://gw.example", descriptor.storedBase)
        assertEquals(AuthState.Connected, descriptor.lastKnownAuthState)

        val fields = descriptor.javaClass.declaredFields.map { it.name }.toSet()
        for (secret in listOf("token", "ticket", "credential", "ciphertext", "iv", "identityHash")) {
            assertFalse(fields.any { it.equals(secret, ignoreCase = true) }, "descriptor leaked $secret")
        }
    }

    @Test
    fun `probe now is unavailable without a callable and delegates otherwise`() = runTest {
        val withoutProbe = backendRepository(probe = null)
        assertTrue(withoutProbe.probeNow(SERVER).isFailure)

        val health = BackendHealth(ok = true, gatewayVersion = "test", upstreamOk = true, capabilities = capabilities())
        val withProbe = backendRepository(probe = { Result.success(health) })
        assertEquals(health, withProbe.probeNow(SERVER).getOrNull())
    }

    // ── 诊断 ─────────────────────────────────────────────────

    @Test
    fun `degraded flips on resync and history flags while clean snapshots stay healthy`() = runTest {
        val source = FakeDiagnosticsSource()
        val repo = diagnosticsRepository(source)

        val clean = repo.snapshot().first()
        assertFalse(clean.degraded)

        source.aggregate.value = DiagnosticsAggregate(
            needsResyncCount = 1,
            historyIncompleteSessions = 0,
            historyUnsafeSessions = 0,
        )
        assertTrue(repo.snapshot().first().degraded, "needsResync must degrade")

        source.aggregate.value = DiagnosticsAggregate(
            needsResyncCount = 0,
            historyIncompleteSessions = 1,
            historyUnsafeSessions = 0,
        )
        assertTrue(repo.snapshot().first().degraded, "historyIncomplete must degrade")

        source.aggregate.value = DiagnosticsAggregate(
            needsResyncCount = 0,
            historyIncompleteSessions = 0,
            historyUnsafeSessions = 1,
        )
        assertTrue(repo.snapshot().first().degraded, "historyUnsafe must degrade")

        source.aggregate.value = DiagnosticsAggregate(0, 0, 0)
        source.counters.value = DiagnosticsCountersSnapshot(
            badJsonCount = 0,
            wsDowngradeCount = 0,
            retentionTrimmedCount = 0,
            baselineSweepSkipped = true,
        )
        assertTrue(repo.snapshot().first().degraded, "skipped baseline sweep must degrade")
    }

    @Test
    fun `diagnostics compose only real counters cursors and pending completeness`() = runTest {
        val source = FakeDiagnosticsSource()
        val cursor = TransportCursor(
            identity = ai.deepseek.dsh.android.model.ConnectionIdentity("a".repeat(64)),
            channel = ChannelKind.Mux,
            sinceSeq = 99,
            epoch = "e1",
        )
        source.cursors.value = mapOf(ChannelKind.Mux to cursor)
        source.counters.value = DiagnosticsCountersSnapshot(
            badJsonCount = 3,
            wsDowngradeCount = 2,
            retentionTrimmedCount = 7,
            baselineSweepSkipped = false,
        )
        source.rosterAge.value = 1_234L
        source.retired.value = 4
        val counts = MutableStateFlow(
            PendingCounts(badge = null, dismissed = 0, completeness = PendingCompleteness.Unknown),
        )
        val repo = diagnosticsRepository(source, pendingCounts = counts)

        val snapshot = repo.snapshot().first()
        assertEquals(3, snapshot.badJsonCount)
        assertEquals(2, snapshot.wsDowngradeCount)
        assertEquals(7, snapshot.retentionTrimmedCount)
        assertEquals(cursor, snapshot.transportCursorPerChannel[ChannelKind.Mux])
        assertEquals(1_234L, snapshot.rosterAgeMs)
        assertEquals(4, snapshot.identityRetiredPendingPurge)
        assertEquals(PendingCompleteness.Unknown, snapshot.pendingCompleteness)
        assertNull(snapshot.lastPollWaitSupported, "no connection source means unknown, not false")
    }

    // ── 构造辅助 ─────────────────────────────────────────────

    private fun sessionRepository(
        store: SessionStore,
        unsafe: Set<SessionId> = emptySet(),
        clock: Clock = FakeClock(),
        syncInfo: RosterSyncInfo = object : RosterSyncInfo {
            override fun lastSyncedAtMs(): Flow<Long?> = flowOf(null)
        },
    ): RoomSessionRepository = RoomSessionRepository(
        serverId = SERVER,
        store = store,
        heads = object : SessionHeadSource {
            override fun observeHead(sessionId: SessionId): Flow<SessionHeadSnapshot?> = flowOf(
                SessionHeadSnapshot(
                    contiguousSeq = null,
                    appliedMaxSeq = null,
                    historyUnsafe = sessionId in unsafe,
                    historyIncomplete = false,
                    prunedBelowSeq = null,
                ),
            )
        },
        syncInfo = syncInfo,
        clock = clock,
    )

    private fun pendingRepository(dao: FakePendingRoomDao): RoomPendingRepository =
        RoomPendingRepository(
            serverId = SERVER,
            store = FakePendingStore,
            dao = dao,
            resolveUseCase = ResolvePendingUseCase(
                serverId = SERVER,
                remote = NoRemote,
                pendingStore = FakePendingStore,
                syncRequestPort = NoSyncRequestPort,
                clock = FakeClock(),
            ),
        )

    private fun backendRepository(probe: (suspend (ServerId) -> Result<BackendHealth?>)?): RoomBackendRepository =
        RoomBackendRepository(
            servers = object : ServerSource {
                override fun observeServers(): Flow<List<ServerRecord>> = flowOf(emptyList())
            },
            authStates = { flowOf(AuthState.Unknown) },
            capabilityFlows = { flowOf(null) },
            probe = probe,
        )

    private fun diagnosticsRepository(
        source: FakeDiagnosticsSource,
        pendingCounts: MutableStateFlow<PendingCounts> = MutableStateFlow(
            PendingCounts(badge = 0, dismissed = 0, completeness = PendingCompleteness.Complete),
        ),
    ): RoomDiagnosticsRepository = RoomDiagnosticsRepository(
        source = source,
        pending = object : PendingRepository {
            override fun groupedBySession(): Flow<Map<SessionId, List<PendingRequest>>> = flowOf(emptyMap())

            override fun forSession(sessionId: SessionId): Flow<List<PendingRequest>> = flowOf(emptyList())

            override fun counts(): Flow<PendingCounts> = pendingCounts

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
            ): Result<ResolvePendingResult> = error("unused")
        },
        authState = flowOf(AuthState.Connected),
        capabilities = flowOf(capabilities()),
    )

    private fun roster(id: String, rank: Long, updatedAt: Long) = RosterEntry(
        sessionId = SessionId(id),
        title = "title-$id",
        updatedAtMs = updatedAt,
        running = false,
        archived = false,
        workspaceId = null,
        rosterRank = rank,
    )

    private fun pendingRequest(completeness: PendingCompleteness) = PendingRequest(
        serverId = SERVER,
        kind = PendingKind.Approval,
        requestId = RequestId("r-1"),
        sessionId = SESSION_1,
        status = PendingStatus.Pending,
        completeness = completeness,
        payloadJson = "{}",
        firstSeenAtMs = 1,
        createdAtMs = 1,
    )

    private fun capabilities() = BackendCapabilities(
        wsTicket = 1,
        eventPolling = 1,
        eventEpoch = 1,
        operations = 1,
        workspaceFiles = 1,
        resumableUploads = 0,
        fileInbox = 0,
        imagePromptTransport = 0,
        raw = mapOf("wsTicket" to 1),
    )

    private companion object {
        val SERVER = ServerId("srv-1")
        val SESSION_1 = SessionId("s-1")
        val SESSION_2 = SessionId("s-2")
        val SESSION_3 = SessionId("s-3")
    }
}

// ── fakes ────────────────────────────────────────────────────

private class FakeSessionStore(entries: List<RosterEntry>) : SessionStore {
    private val rows = MutableStateFlow(entries)
    var lastOffsetRank: Long? = null
        private set

    override fun rosterPage(limit: Int, offsetRank: Long): Flow<List<RosterEntry>> {
        lastOffsetRank = offsetRank
        return rows.map { all ->
            all.filter { it.rosterRank < offsetRank }
                .sortedWith(compareByDescending<RosterEntry> { it.rosterRank }.thenByDescending { it.sessionId.value })
                .take(limit)
        }
    }

    override suspend fun replaceRoster(serverId: ServerId, entries: List<RosterEntry>, syncedAtMs: Long) = unsupported()

    override suspend fun tombstoneAbsent(serverId: ServerId, absentSessionIds: Set<SessionId>): Int = unsupported()

    override suspend fun position(serverId: ServerId, sessionId: SessionId): SessionPosition? = unsupported()

    override suspend fun applyPosition(serverId: ServerId, sessionId: SessionId, position: SessionPosition) = unsupported()

    override fun observe(serverId: ServerId, sessionId: SessionId): Flow<RosterEntry?> =
        rows.map { all -> all.firstOrNull { it.sessionId == sessionId } }
}

private object FakePendingStore : PendingStore {
    override fun observePending(serverId: ServerId): Flow<List<PendingRequest>> = flowOf(emptyList())

    override fun observeForSession(serverId: ServerId, sessionId: SessionId): Flow<List<PendingRequest>> =
        flowOf(emptyList())

    override suspend fun upsert(request: PendingRequest) = unsupported()

    override suspend fun dismiss(
        serverId: ServerId,
        kind: PendingKind,
        requestId: RequestId,
        nowMs: Long,
    ) = unsupported()

    override suspend fun tombstone(
        serverId: ServerId,
        kind: PendingKind,
        requestId: RequestId,
        nowMs: Long,
    ) = unsupported()

    override suspend fun sweepAbsent(
        serverId: ServerId,
        presentKeys: Set<DurableStore.PendingKey>,
        nowMs: Long,
    ): Int = unsupported()
}

private class FakePendingRoomDao : PendingRoomDao {
    val rows = MutableStateFlow<List<PendingRequest>>(emptyList())
    val badge = MutableStateFlow(PendingBadgeSnapshot(0, 0, 0, null))
    var exists: Boolean = true

    override fun observeAll(): Flow<List<PendingRequest>> = rows

    override fun observeBadge(): Flow<PendingBadgeSnapshot> = badge

    override suspend fun dismiss(kind: PendingKind, requestId: RequestId, nowMs: Long): Int = 0

    override suspend fun undismiss(kind: PendingKind, requestId: RequestId): Int = 0

    override suspend fun exists(kind: PendingKind, requestId: RequestId): Boolean = exists
}

private class FakeDiagnosticsSource : DiagnosticsSource {
    val aggregate = MutableStateFlow(DiagnosticsAggregate(0, 0, 0))
    val counters = MutableStateFlow(DiagnosticsCountersSnapshot(0, 0, 0, false))
    val cursors = MutableStateFlow<Map<ChannelKind, TransportCursor>>(emptyMap())
    val lag = MutableStateFlow<Map<SessionId, Long>>(emptyMap())
    val rosterAge = MutableStateFlow<Long?>(null)
    val retired = MutableStateFlow(0)

    override fun observeAggregate(): Flow<DiagnosticsAggregate> = aggregate

    override fun observeCounters(): Flow<DiagnosticsCountersSnapshot> = counters

    override fun observeCursors(): Flow<Map<ChannelKind, TransportCursor>> = cursors

    override fun observeContiguousLag(): Flow<Map<SessionId, Long>> = lag

    override fun observeRosterAgeMs(): Flow<Long?> = rosterAge

    override fun observeIdentityRetiredPendingPurge(): Flow<Int> = retired
}

private object NoSyncRequestPort : SyncRequestPort {
    override suspend fun requestImmediate(serverId: ServerId, reason: SyncReason): Result<Unit> = Result.success(Unit)

    override suspend fun setForeground(active: Boolean): Result<Unit> = Result.success(Unit)

    override fun isBackgroundSyncEnabled(): Flow<Boolean> = flowOf(true)
}

private object NoRemote : RemoteSource {
    override suspend fun health(): BackendHealth = unsupported()

    override suspend fun roster(): List<RosterEntry> = unsupported()

    override suspend fun history(request: HistoryRequest): HistoryPage = unsupported()

    override suspend fun poll(request: PollRequest): EventBatch = unsupported()

    override suspend fun openStream(
        channel: ChannelKind,
        onFrame: suspend (TransportFrame) -> Unit,
    ): StreamHandle = unsupported()

    override suspend fun prompt(request: PromptRequest): WriteResult = unsupported()

    override suspend fun resolve(request: ResolveRequest): WriteResult = unsupported()

    override suspend fun cancel(sessionId: SessionId): WriteResult = unsupported()

    override suspend fun selectModel(sessionId: SessionId, modelId: String): WriteResult = unsupported()

    override suspend fun models(sessionId: SessionId): List<String> = unsupported()
}

private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by this test")
