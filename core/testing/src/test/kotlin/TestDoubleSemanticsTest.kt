import ai.deepseek.dsh.android.domain.DurableStore
import ai.deepseek.dsh.android.domain.SyncOwnerRecord
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.NotificationTag
import ai.deepseek.dsh.android.model.OutboxState
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.SyncOwnerRole
import ai.deepseek.dsh.android.model.TransportCursor
import ai.deepseek.dsh.android.model.TransportFrame
import ai.deepseek.dsh.android.testing.FakeRemoteSource
import ai.deepseek.dsh.android.testing.Fixtures
import ai.deepseek.dsh.android.testing.InMemoryDurableStore
import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.TransportFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 替身自己必须先被测：如果 `InMemoryDurableStore` 的回滚或 `FakeRemoteSource` 的一次性失败注入是假的，
 * 后面所有拿它们写的 golden 都会得到一个看起来很绿的实际无意义的结论。
 */
class TestDoubleSemanticsTest {

    private val identity = Fixtures.identity(1)
    private val server = Fixtures.serverId
    private val session = Fixtures.sessionId(1)

    private fun batch(
        cursor: Boolean = true,
        frames: Int = 1,
    ) = DurableStore.ApplyBatch(
        identity = identity,
        serverId = server,
        frames = (1..frames).map { Fixtures.frame("session/event", it.toLong()) },
        upserts = listOf(
            DurableStore.ProjectionUpsert(session, ai.deepseek.dsh.android.model.ProjectionFamily.Session, "k", Fixtures.seq(1), "{}"),
        ),
        pendingChanges = listOf(Fixtures.pending()),
        outboxEntries = listOf(
            DurableStore.OutboxEntry(
                kind = PendingKind.Approval,
                requestId = RequestId("req-1"),
                tag = NotificationTag.of(identity, PendingKind.Approval, RequestId("req-1")),
                state = OutboxState.New,
            ),
        ),
        cursorAfter = if (cursor) TransportCursor(identity, Fixtures.mux, 9, "epoch-1") else null,
        positionsAfter = mapOf(session to SessionPosition(Fixtures.seq(9), Fixtures.seq(9))),
        islands = mapOf(session to emptyList()),
        needsResync = emptyMap(),
    )

    @Test
    fun `cursor is always the last section written`() = runBlocking {
        val store = InMemoryDurableStore()
        store.commit(batch())
        assertEquals("cursor", store.writeOrder.last().last())
        assertEquals(9L, store.cursor(identity, Fixtures.mux)?.sinceSeq)
    }

    @Test
    fun `a failing commit mutates nothing, including the cursor`() = runBlocking {
        val store = InMemoryDurableStore()
        store.commit(batch())
        val cursorBefore = store.cursor(identity, Fixtures.mux)?.sinceSeq
        store.failNextCommit = IllegalStateException("transaction blew up")
        runCatching { store.commit(batch(frames = 3)) }
            .onFailure { assertEquals("transaction blew up", it.message) }
        assertEquals("失败批次不得留下帧", 1, store.frames().size)
        assertEquals("失败批次不得推进 cursor", cursorBefore, store.cursor(identity, Fixtures.mux)?.sinceSeq)
        assertEquals("失败批次不得写 outbox", 1, store.outbox().size)
    }

    @Test
    fun `owner cas only succeeds from the expected role`() = runBlocking {
        val store = InMemoryDurableStore()
        assertTrue(store.casOwner(SyncOwnerRole.None, SyncOwnerRole.Worker, "worker-1", 1L))
        assertFalse("前台已持有时 worker 抢不到", store.casOwner(SyncOwnerRole.None, SyncOwnerRole.Foreground, "fg", 2L))
        assertTrue(store.casOwner(SyncOwnerRole.Worker, SyncOwnerRole.Foreground, "fg", 3L))
        assertEquals(SyncOwnerRole.Foreground, store.owner().role)
        assertEquals("fg", store.owner().holderId)
    }

    @Test
    fun `identity purge clears cursors and tags but not sessions`() = runBlocking {
        val store = InMemoryDurableStore()
        store.commit(batch())
        val retiredAt = 1_000L
        val grace = 7L * 86_400_000L
        store.markIdentityRetired(identity, retiredAtMs = retiredAt)
        // DAO 谓词是 `retiredAtMs + graceMs <= nowMs`：恰好到界才算过期。
        assertEquals(
            "宽限期未到",
            emptyList<ConnectionIdentity>(),
            store.purgeEligibleIdentities(nowMs = retiredAt + grace - 1, graceMs = grace),
        )
        val purged = store.purgeEligibleIdentities(nowMs = retiredAt + grace, graceMs = grace)
        assertEquals(listOf(identity), purged)
        assertNull(store.cursor(identity, Fixtures.mux))
        assertTrue(store.outbox().isEmpty())
        assertEquals("投影行不随身份清理", 1, store.pendingRows().size)
        assertEquals("幂等续跑：再清一次为空", emptyList<ConnectionIdentity>(), store.purgeEligibleIdentities(99_000_000L, 1L))
    }

    @Test
    fun `sweep tombstones rows missing from a baseline that did carry one`() = runBlocking {
        val store = InMemoryDurableStore()
        store.commit(batch())
        val swept = store.sweepAbsent(server, presentKeys = emptySet(), nowMs = 0L)
        assertEquals(1, swept)
        assertTrue(store.pendingRows().isEmpty())
    }

    @Test
    fun `dismiss hides from badge without deleting the row`() = runBlocking {
        val store = InMemoryDurableStore()
        store.upsert(Fixtures.pending())
        store.dismiss(server, PendingKind.Approval, RequestId("req-1"), 5L)
        val row = store.pendingRows().single()
        assertEquals(5L, row.dismissedAtMs)
        assertFalse("划掉后不计入徽标", row.visibleInBadge)
        assertEquals("行仍然存在", 1, store.pendingRows().size)
    }

    @Test
    fun `injected remote failure fires exactly once`() = runBlocking {
        val source = FakeRemoteSource()
        source.failNext("prompt", TransportFailure.Unauthorized)
        val first = runCatching { source.prompt(ai.deepseek.dsh.android.model.PromptRequest(session, "hi")) }
        assertTrue(first.exceptionOrNull() is RemoteFailure)
        assertEquals(1, source.callCount("prompt"))
        source.prompt(ai.deepseek.dsh.android.model.PromptRequest(session, "hi"))
        assertEquals("失败只注入一次", 2, source.callCount("prompt"))
    }

    @Test
    fun `cancelled poll never returns a batch`() = runBlocking {
        val source = FakeRemoteSource()
        source.pollDelayMs = 5_000L
        var delivered = false
        val job = launch {
            runCatching {
                source.poll(ai.deepseek.dsh.android.model.PollRequest(Fixtures.mux, 0, "epoch-1", 25_000L))
            }.onSuccess { delivered = true }
        }
        withTimeout(1_000L) { while (source.pollRequests.isEmpty()) { kotlinx.coroutines.yield() } }
        job.cancelAndJoin()
        assertFalse("被取消的 poll 不能提交批次", delivered)
    }

    @Test
    fun `stream frames reach the installed listener and stop after close`() = runBlocking {
        val source = FakeRemoteSource()
        val received = mutableListOf<TransportFrame>()
        val handle = source.openStream(Fixtures.mux) { received += it }
        source.emit(Fixtures.frame("session/event", 1))
        assertEquals(1, received.size)
        handle.close.invoke()
        runCatching { source.emit(Fixtures.frame("session/event", 2)) }.onFailure {
            assertTrue("关闭后不应再有监听者", it is IllegalStateException)
        }
        assertEquals(1, received.size)
    }

    @Test
    fun `notification tag format is frozen`() {
        val tag = NotificationTag.of(identity, PendingKind.Approval, RequestId("abc"))
        assertEquals("${identity.digest}:a:abc", tag.value)
        assertEquals(
            "${identity.digest}:q:abc",
            NotificationTag.of(identity, PendingKind.Question, RequestId("abc")).value,
        )
    }
}
