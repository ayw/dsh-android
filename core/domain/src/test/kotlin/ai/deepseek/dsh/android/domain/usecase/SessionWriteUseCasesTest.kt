package ai.deepseek.dsh.android.domain.usecase

import ai.deepseek.dsh.android.domain.FakeClock
import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.TransportFailure
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.SessionWriteResult
import kotlinx.coroutines.test.runTest
import ai.deepseek.dsh.android.model.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SessionWriteUseCasesTest {
    private val sessionId = SessionId("sess-1")

    @Test
    fun `cancel accepted is delivered`() = runTest {
        val remote = FakeRemote(cancelFn = { accepted() })
        val result = CancelTurnUseCase(remote, { caps() }, FakeClock())(sessionId)
        assertIs<SessionWriteResult.Delivered>(result)
        assertEquals(1, remote.cancelCount)
    }

    @Test
    fun `cancel forbidden is a definite rejection`() = runTest {
        val remote = FakeRemote(cancelFn = { throw RemoteFailure(TransportFailure.Forbidden("/api/cancel")) })
        val result = CancelTurnUseCase(remote, { caps() }, FakeClock())(sessionId)
        val rejected = assertIs<SessionWriteResult.Rejected>(result)
        assertEquals(DomainError.Forbidden("/api/cancel"), rejected.error)
    }

    @Test
    fun `cancel timeout is unknown without retry`() = runTest {
        val remote = FakeRemote(cancelFn = { throw RemoteFailure(TransportFailure.Timeout) })
        val result = CancelTurnUseCase(remote, { caps() }, FakeClock())(sessionId)
        assertIs<SessionWriteResult.Unknown>(result)
        assertEquals(1, remote.cancelCount)
    }

    @Test
    fun `select model accepted is delivered and model list reads back`() = runTest {
        val remote = FakeRemote(
            selectModelFn = { _, _ -> accepted() },
            modelsFn = { listOf("m1", "m2") },
        )
        val useCase = SelectModelUseCase(remote, { caps() }, FakeClock())

        assertIs<SessionWriteResult.Delivered>(useCase(sessionId, "m2"))
        assertEquals(Result.success(listOf("m1", "m2")), useCase.availableModels(sessionId))
    }

    @Test
    fun `select model timeout is unknown`() = runTest {
        val remote = FakeRemote(selectModelFn = { _, _ -> throw RemoteFailure(TransportFailure.Timeout) })
        val result = SelectModelUseCase(remote, { caps() }, FakeClock())(sessionId, "m2")
        assertIs<SessionWriteResult.Unknown>(result)
    }

    @Test
    fun `rename is a capability-gated absence in v0`() = runTest {
        val source = FakeSessionWriteSource()
        val noCaps = caps() // raw map has no "rename" key
        val gated = RenameSessionUseCase({ noCaps }, source)(sessionId, "new title")

        assertIs<SessionWriteResult.Unavailable>(gated)
        // gated off before any remote call: nothing was sent
        assertEquals(0, source.renameCount)
    }

    @Test
    fun `rename works once the backend advertises the capability`() = runTest {
        val source = FakeSessionWriteSource()
        val withRename = caps(raw = mapOf("rename" to 1))
        val result = RenameSessionUseCase({ withRename }, source)(sessionId, "new title")

        assertIs<SessionWriteResult.Delivered>(result)
        assertEquals(1, source.renameCount)
    }

    @Test
    fun `archive is capability-gated and unarchive has no port at all`() = runTest {
        val source = FakeSessionWriteSource()
        val result = ArchiveSessionUseCase({ caps() }, source)(sessionId, "ws-1")
        val unavailable = assertIs<SessionWriteResult.Unavailable>(result)
        assertEquals(0, source.archiveCount)
        // the structured absence carries its domain-error vocabulary
        assertEquals(DomainError.UnavailableCapability("archiveSession"), unavailable.error)
    }
}
