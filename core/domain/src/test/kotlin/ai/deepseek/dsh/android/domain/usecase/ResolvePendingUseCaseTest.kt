package ai.deepseek.dsh.android.domain.usecase

import ai.deepseek.dsh.android.domain.FakeClock
import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.TransportFailure
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.ResolvePendingResult
import ai.deepseek.dsh.android.domain.repository.SyncReason
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolvePendingUseCaseTest {
    private val serverId = ServerId("srv")
    private val sessionId = SessionId("sess-1")
    private val requestId = RequestId("r1")

    private fun pendingRow(status: PendingStatus) = PendingRequest(
        serverId = serverId,
        kind = PendingKind.Approval,
        requestId = requestId,
        sessionId = sessionId,
        status = status,
        completeness = PendingCompleteness.Complete,
        payloadJson = "{}",
        firstSeenAtMs = 0L,
        createdAtMs = 0L,
    )

    @Test
    fun `accepted true writes an optimistic resolved pending`() = runTest {
        val store = InMemoryPendingStore(listOf(pendingRow(PendingStatus.Pending)))
        val remote = FakeRemote(resolveFn = { accepted() })
        val sync = FakeSyncRequestPort()
        val useCase = ResolvePendingUseCase(serverId, remote, store, sync, FakeClock())

        val result = useCase(sessionId, PendingKind.Approval, requestId, "{\"ok\":1}", "idem-1")

        assertIs<ResolvePendingResult.Confirmed>(result)
        val row = store.rows().single()
        assertEquals(PendingStatus.Resolved, row.status)
        assertNotNull(row.resolvedAtMs)
        // a confirmed write requests no baseline refresh
        assertTrue(sync.requests.isEmpty())
    }

    @Test
    fun `accepted false marks stale and never retries`() = runTest {
        val store = InMemoryPendingStore(listOf(pendingRow(PendingStatus.Pending)))
        val remote = FakeRemote(resolveFn = { rejected() })
        val sync = FakeSyncRequestPort()
        val useCase = ResolvePendingUseCase(serverId, remote, store, sync, FakeClock())

        val result = useCase(sessionId, PendingKind.Approval, requestId, "{}", "idem-1")

        assertIs<ResolvePendingResult.Stale>(result)
        val row = store.rows().single()
        assertEquals(PendingStatus.Stale, row.status)
        assertNull(row.resolvedAtMs)
        // STALE is terminal for this attempt: exactly one remote call, no auto-retry
        assertEquals(1, remote.resolveCount)
        assertTrue(sync.requests.isEmpty())
    }

    @Test
    fun `timeout maps to unknown and requests a baseline refresh without retry`() = runTest {
        val store = InMemoryPendingStore(listOf(pendingRow(PendingStatus.Pending)))
        val remote = FakeRemote(resolveFn = { throw RemoteFailure(TransportFailure.Timeout) })
        val sync = FakeSyncRequestPort()
        val useCase = ResolvePendingUseCase(serverId, remote, store, sync, FakeClock())

        val result = useCase(sessionId, PendingKind.Approval, requestId, "{}", "idem-1")

        assertIs<ResolvePendingResult.Unknown>(result)
        assertEquals(listOf(serverId to SyncReason.BaselineRefresh), sync.requests)
        // row stays PENDING (UNKNOWN is re-confirmable by baseline), and the remote call happened once
        assertEquals(PendingStatus.Pending, store.rows().single().status)
        assertEquals(1, remote.resolveCount)
    }

    @Test
    fun `409 already-settled becomes a tombstone`() = runTest {
        val store = InMemoryPendingStore(listOf(pendingRow(PendingStatus.Pending)))
        val useCase = ResolvePendingUseCase(serverId, FakeRemote(), store, FakeSyncRequestPort(), FakeClock())

        val result = useCase.applyConflict(sessionId, PendingKind.Approval, requestId, reason = "already-settled")

        assertIs<ResolvePendingResult.Tombstoned>(result)
        assertEquals(1, store.tombstoneCalls.size)
        assertEquals(PendingKind.Approval, store.tombstoneCalls.single().first)
        assertEquals(RequestId("r1"), store.tombstoneCalls.single().second)
    }

    @Test
    fun `409 stale-revision requests a re-read only when the revision is allowlisted`() = runTest {
        val store = InMemoryPendingStore(listOf(pendingRow(PendingStatus.Pending)))
        val useCase = ResolvePendingUseCase(
            serverId,
            FakeRemote(),
            store,
            FakeSyncRequestPort(),
            FakeClock(),
            staleRevisionAllowlist = setOf("rev-5"),
        )

        val result = useCase.applyConflict(sessionId, PendingKind.Approval, requestId, reason = "rev-5")

        assertIs<ResolvePendingResult.ReReadRequired>(result)
        assertEquals("rev-5", result.revision)
        assertTrue(store.tombstoneCalls.isEmpty())
    }

    @Test
    fun `v0 empty allowlist collapses every non-settled 409 to a kept conflict`() = runTest {
        val store = InMemoryPendingStore(listOf(pendingRow(PendingStatus.Pending)))
        // v0 default: empty allowlist
        val useCase = ResolvePendingUseCase(serverId, FakeRemote(), store, FakeSyncRequestPort(), FakeClock())

        val fromReason = useCase.applyConflict(sessionId, PendingKind.Approval, requestId, reason = "stale-revision")
        assertIs<ResolvePendingResult.Conflict>(fromReason)
        assertEquals(DomainError.Conflict, fromReason.error)

        // null reason (v0 ports cannot carry a 409 sub-reason) also keeps the row
        assertIs<ResolvePendingResult.Conflict>(
            useCase.applyConflict(sessionId, PendingKind.Approval, requestId, reason = null),
        )
        // the row was never modified and never tombstoned
        assertEquals(PendingStatus.Pending, store.rows().single().status)
        assertTrue(store.tombstoneCalls.isEmpty())
    }

    @Test
    fun `optimistic resolved survives a late resolved event without a second write`() = runTest {
        val store = InMemoryPendingStore(listOf(pendingRow(PendingStatus.Pending)))
        val remote = FakeRemote(resolveFn = { accepted() })
        val useCase = ResolvePendingUseCase(serverId, remote, store, FakeSyncRequestPort(), FakeClock())

        assertIs<ResolvePendingResult.Confirmed>(useCase(sessionId, PendingKind.Approval, requestId, "{}", "idem-1"))
        val upsertsAfterConfirm = store.upserts.size

        val stillResolved = useCase.applyRemoteConfirmation(sessionId, PendingKind.Approval, requestId)

        assertTrue(stillResolved)
        assertEquals(PendingStatus.Resolved, store.rows().single().status)
        // idempotency: the late event must not reopen or rewrite the row
        assertEquals(upsertsAfterConfirm, store.upserts.size)
    }
}
