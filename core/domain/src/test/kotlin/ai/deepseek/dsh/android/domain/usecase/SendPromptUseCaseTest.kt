package ai.deepseek.dsh.android.domain.usecase

import ai.deepseek.dsh.android.domain.FakeClock
import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.TransportFailure
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.SendPromptResult
import ai.deepseek.dsh.android.model.SessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SendPromptUseCaseTest {
    private val sessionId = SessionId("sess-1")

    @Test
    fun `accepted true is delivered and writes nothing durable`() = runTest {
        val durable = RecordingDurableStore()
        val remote = FakeRemote(promptFn = { accepted() })
        val useCase = SendPromptUseCase(remote, { caps() }, FakeClock())

        val delivered = assertIs<SendPromptResult.Delivered>(useCase(sessionId, "hello", "req-1"))

        assertEquals("req-1", delivered.requestId)
        assertEquals(false, delivered.receiptBacked)
        assertEquals(1, remote.promptCount)
        // D28: outbox 只承载通知外发期望；提示词写侧不产生任何持久化行。
        assertEquals(0, durable.batches.size)
    }

    @Test
    fun `receipt capability narrows the unknown window`() = runTest {
        val remote = FakeRemote(promptFn = { accepted(receipt = "rcpt-9") })
        val useCase = SendPromptUseCase(remote, { caps(operations = 1) }, FakeClock())

        val delivered = assertIs<SendPromptResult.Delivered>(useCase(sessionId, "hello", "req-2"))

        assertEquals("rcpt-9", delivered.receiptId)
        assertEquals(true, delivered.receiptBacked)
    }

    @Test
    fun `timeout maps to unknown with exactly one remote call`() = runTest {
        val durable = RecordingDurableStore()
        val remote = FakeRemote(promptFn = { throw RemoteFailure(TransportFailure.Timeout) })
        val useCase = SendPromptUseCase(remote, { caps() }, FakeClock())

        val unknown = assertIs<SendPromptResult.Unknown>(useCase(sessionId, "hello", "req-3"))

        // never auto-retry: the remote prompt call happened exactly once
        assertEquals(1, remote.promptCount)
        assertEquals(DomainError.Timeout, unknown.error)
        assertEquals(0, durable.batches.size)
    }

    @Test
    fun `unreachable also maps to unknown not retry`() = runTest {
        val remote = FakeRemote(promptFn = { throw RemoteFailure(TransportFailure.Unreachable) })
        val useCase = SendPromptUseCase(remote, { caps() }, FakeClock())

        val unknown = assertIs<SendPromptResult.Unknown>(useCase(sessionId, "hi", "req-4"))

        assertEquals(1, remote.promptCount)
        assertEquals(DomainError.Unreachable, unknown.error)
    }

    @Test
    fun `accepted false is a rejection not unknown`() = runTest {
        val durable = RecordingDurableStore()
        val remote = FakeRemote(promptFn = { rejected() })
        val useCase = SendPromptUseCase(remote, { caps() }, FakeClock())

        val result = assertIs<SendPromptResult.Rejected>(useCase(sessionId, "hi", "req-5"))

        assertEquals(1, remote.promptCount)
        assertEquals(0, durable.batches.size)
        assertIs<DomainError.Rejected>(result.error)
    }

    @Test
    fun `auth failure is a definite rejection`() = runTest {
        val remote = FakeRemote(promptFn = { throw RemoteFailure(TransportFailure.Unauthorized) })
        val useCase = SendPromptUseCase(remote, { caps() }, FakeClock())

        val result = assertIs<SendPromptResult.Rejected>(useCase(sessionId, "hi", "req-6"))

        assertEquals(DomainError.AuthExpired, result.error)
    }
}
