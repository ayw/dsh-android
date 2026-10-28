package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.SessionId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * G06 reset 顺序（任务书测试 b）：`tombstone → baseline → events → cursor`。
 *
 * reset 与“普通增量”的唯一区别在这里被钉死：
 *  - 游标允许回退（epoch/服务端 reset 之后 seq 从头开始），但仍然是**最后一段**；
 *  - 位置回退走 `putHeads`（无条件覆盖），普通同步走前向守卫 upsert；
 *  - islands 整体替换 = 旧账本被清空后按新连续段重建（D26：合并已在 domain 做完）。
 */
@RunWith(RobolectricTestRunner::class)
class RoomResetOrderTest {

    private lateinit var db: DshDatabase
    private lateinit var journal: RecordingWriteJournal
    private lateinit var store: RoomDurableStore

    @Before
    fun setUp() {
        db = TestData.room()
        journal = RecordingWriteJournal()
        store = RoomDurableStore(db, TestData.clock(), journal, journal)
        TestData.seedPending(
            db,
            TestData.pendingRequest(requestId = "gone-1", status = PendingStatus.Pending),
            TestData.pendingRequest(requestId = "kept-1", status = PendingStatus.Stale),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(kind: PendingKind, requestId: String) =
        runBlocking { db.pendingDao().pending(TestData.serverId.value, kind.name, requestId) }

    @Test
    fun `reset batch writes tombstone then baseline then events then cursor`() = runBlocking {
        // 先建立“已经同步到 100”的旧状态。
        store.commit(
            TestData.fullBatch(
                cursorSeq = 100L,
                frames = listOf(TestData.frame(100)),
                positions = mapOf(TestData.sessionId to TestData.position(100, 100)),
                islands = mapOf(TestData.sessionId to listOf(TestData.island(1, 100))),
                pendingChanges = emptyList(),
                outbox = emptyList(),
                upserts = emptyList(),
                tombstones = emptyList(),
                sweep = null,
            ),
        )
        assertEquals(100L, db.syncStateDao().cursor(TestData.identity.digest, ChannelKind.Mux.value)?.seq)

        val reset = TestData.fullBatch(
            cursorSeq = 1L,
            frames = listOf(TestData.frame(1)),
            positions = mapOf(TestData.sessionId to TestData.position(1, 1)),
            islands = mapOf(TestData.sessionId to listOf(TestData.island(1, 1))),
            pendingChanges = emptyList(),
            outbox = emptyList(),
            upserts = emptyList(),
            tombstones = listOf(TestData.pendingKey(PendingKind.Approval, "gone-1")),
            sweep = TestData.sweep(TestData.sessionId, TestData.pendingKey(PendingKind.Approval, "kept-1")),
        )
        store.commit(reset)

        val order = journal.lastOrder()
        val tombstoneAt = order.indexOf(CommitSection.Tombstones)
        val baselineAt = order.indexOf(CommitSection.Baseline)
        val framesAt = order.indexOf(CommitSection.Frames)
        val projectionAt = order.indexOf(CommitSection.Projection)
        val cursorAt = order.indexOf(CommitSection.Cursor)
        assertTrue("tombstone 必须早于 baseline", tombstoneAt in 0 until baselineAt)
        assertTrue("baseline 必须早于 events", baselineAt < framesAt)
        assertTrue("events 必须早于投影", framesAt < projectionAt)
        assertTrue("cursor 永远是最后一段", cursorAt == order.size - 1)

        // 回退后的游标与位置确实落库（说明 force 覆盖生效，而不是被守卫吃掉）。
        assertEquals(1L, db.syncStateDao().cursor(TestData.identity.digest, ChannelKind.Mux.value)?.seq)
        val head = db.sessionHeadDao().head(TestData.serverId.value, TestData.sessionId.value)
        assertEquals(1L, head?.appliedMaxSeq?.value)
        assertEquals(1L, head?.contiguousSeq?.value)
        assertEquals(listOf(TestData.island(1, 1)), db.islandDao().islands(TestData.serverId.value, TestData.sessionId.value).map { TestData.island(it.startSeq.value, it.endSeq.value) })
    }

    @Test
    fun `baseline sweep tombstones absent keys and revives present ones`() = runBlocking {
        store.commit(
            TestData.fullBatch(
                frames = listOf(TestData.frame(1)),
                pendingChanges = emptyList(),
                outbox = emptyList(),
                upserts = emptyList(),
                tombstones = listOf(TestData.pendingKey(PendingKind.Approval, "gone-1")),
                sweep = TestData.sweep(TestData.sessionId, TestData.pendingKey(PendingKind.Approval, "kept-1")),
            ),
        )

        val gone = row(PendingKind.Approval, "gone-1")
        assertEquals(PendingStatus.Stale, gone?.status)
        assertEquals(TestData.nowMs, gone?.tombstonedAtMs)

        val kept = row(PendingKind.Approval, "kept-1")
        assertNull("baseline 确认到的 key 必须清掉 tombstone", kept?.tombstonedAtMs)
        assertEquals(TestData.nowMs, kept?.sweptAtMs)
    }

    @Test
    fun `session without a batch position keeps its own head row`() = runBlocking {
        val other = SessionId("session-other")
        store.commit(
            TestData.fullBatch(
                positions = mapOf(other to TestData.position(4, 4)),
                islands = mapOf(other to listOf(TestData.island(1, 4))),
                frames = listOf(TestData.frame(4, session = other)),
                pendingChanges = emptyList(),
                outbox = emptyList(),
                upserts = emptyList(),
                tombstones = emptyList(),
                sweep = null,
            ),
        )
        assertEquals(4L, db.sessionHeadDao().head(TestData.serverId.value, other.value)?.appliedMaxSeq?.value)
        assertEquals(1, db.sessionEventDao().eventCount(TestData.serverId.value, other.value).total)
    }
}
