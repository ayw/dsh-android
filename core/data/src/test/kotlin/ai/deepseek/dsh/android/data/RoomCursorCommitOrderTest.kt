package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DiagnosticsCounters
import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.domain.DurableStore
import ai.deepseek.dsh.android.domain.FakeClock
import ai.deepseek.dsh.android.model.ChannelKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * G06 写侧（任务书测试 a）：一次 `commit` 就是一个事务，段落顺序固定，**cursor 永远最后**；
 * 中途失败（注入异常 = 崩溃等价物）时游标不前进、同批的其它写入也整体不存在。
 *
 * 证据来自 [RecordingWriteJournal] 记录的段落序列 + 直接读 Room 的行，而不是日志。
 */
@RunWith(RobolectricTestRunner::class)
class RoomCursorCommitOrderTest {

    private lateinit var db: DshDatabase
    private lateinit var journal: RecordingWriteJournal
    private lateinit var store: RoomDurableStore
    private lateinit var clock: FakeClock

    @Before
    fun setUp() {
        db = TestData.room()
        journal = RecordingWriteJournal()
        clock = TestData.clock()
        store = RoomDurableStore(db, clock, journal, journal)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun cursorSeq(): Long? =
        runBlocking { db.syncStateDao().cursor(TestData.identity.digest, ChannelKind.Mux.value)?.seq }

    private fun eventCount(session: ai.deepseek.dsh.android.model.SessionId = TestData.sessionId): Int =
        runBlocking { db.sessionEventDao().eventCount(TestData.serverId.value, session.value).total }

    @Test
    fun `every section of a full batch is written in the fixed order`() = runBlocking {
        store.commit(TestData.fullBatch())

        assertEquals(1, journal.orders().size)
        assertEquals(CommitSection.ordered, journal.lastOrder())
    }

    @Test
    fun `cursor is the last section, right after outbox`() = runBlocking {
        store.commit(TestData.fullBatch())

        val order = journal.lastOrder()
        assertEquals(CommitSection.Cursor, order.last())
        assertEquals(CommitSection.Outbox, order[order.size - 2])
        assertEquals(CommitSection.Tombstones, order.first())
    }

    @Test
    fun `projection failure leaves the cursor exactly where it was`() = runBlocking {
        store.commit(TestData.fullBatch(cursorSeq = 5L, frames = listOf(TestData.frame(1))))
        assertEquals(5L, cursorSeq())

        journal.failAt(index = 1, section = CommitSection.Projection)
        val second = TestData.fullBatch(cursorSeq = 9L, frames = listOf(TestData.frame(2)))
        assertThrows(IllegalStateException::class.java) { runBlocking { store.commit(second) } }

        assertEquals("崩溃批次不得推进游标", 5L, cursorSeq())
        assertEquals("崩溃批次的帧也整体回滚", 1, eventCount())
        // 记录里停在 Frames：崩溃发生在 Projection 之前，因此后半段一个都没执行。
        assertEquals(listOf(CommitSection.Tombstones, CommitSection.Baseline, CommitSection.Frames), journal.lastOrder())
    }

    @Test
    fun `crash before the cursor section rolls back the whole batch`() = runBlocking {
        store.commit(
            TestData.fullBatch(
                cursorSeq = 5L,
                frames = listOf(TestData.frame(1)),
                pendingChanges = emptyList(),
                upserts = emptyList(),
            ),
        )
        journal.failAt(index = 1, section = CommitSection.Outbox)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.commit(
                    TestData.fullBatch(
                        cursorSeq = 8L,
                        frames = listOf(TestData.frame(7)),
                        pendingChanges = listOf(TestData.pendingRequest(requestId = "req-9")),
                        upserts = emptyList(),
                    ),
                )
            }
        }

        assertEquals(5L, cursorSeq())
        assertEquals(1, eventCount())
        assertEquals(
            "外发行属于被回滚的批次",
            0,
            runBlocking { db.outboxDao().takeNew(TestData.identity.digest, limit = 10).count { it.requestId.value == "req-9" } },
        )
        assertNull(runBlocking { db.pendingDao().pending(TestData.serverId.value, "Approval", "req-9") })
    }

    @Test
    fun `replaying the same batch twice keeps one row per seq`() = runBlocking {
        val batch = TestData.fullBatch(cursorSeq = 5L, frames = listOf(TestData.frame(1), TestData.frame(2)))
        store.commit(batch)
        store.commit(batch)

        assertEquals("同 seq 重复帧是整行替换，不是第二行", 2, eventCount())
        assertEquals(5L, cursorSeq())
        assertEquals(2, journal.orders().size)
    }

    @Test
    fun `outbox rows land under the identity of the batch and stay New`() = runBlocking {
        store.commit(TestData.fullBatch(outbox = listOf(TestData.outboxEntry(requestId = "req-1"))))

        val taken = runBlocking { db.outboxDao().takeNew(TestData.identity.digest, limit = 10) }
        assertEquals(1, taken.size)
        assertEquals("req-1", taken.single().requestId.value)
        assertEquals(ai.deepseek.dsh.android.model.OutboxState.New, taken.single().state)
    }

    @Test
    fun `diagnostics delta is accumulated inside the same transaction`() = runBlocking {
        store.commit(TestData.fullBatch(diagnostics = DurableStore.DiagnosticsDelta(2, 3, 4)))

        assertEquals(2L, runBlocking { db.diagnosticsDao().counter(DiagnosticsCounters.BAD_JSON) }?.value)
        assertEquals(3L, runBlocking { db.diagnosticsDao().counter(DiagnosticsCounters.WS_DOWNGRADE) }?.value)
        assertEquals(4L, runBlocking { db.diagnosticsDao().counter(DiagnosticsCounters.RETENTION_TRIMMED) }?.value)
    }
}
