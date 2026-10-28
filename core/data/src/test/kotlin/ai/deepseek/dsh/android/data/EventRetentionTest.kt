package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DiagnosticsCounters
import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.database.SessionHeadEntity
import ai.deepseek.dsh.android.database.toEntity
import ai.deepseek.dsh.android.domain.FakeClock
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.model.EventSeq
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
 * G12（任务书测试 d）：大数据的保留裁剪。
 *
 * 裁剪是**本机容量决定**，不是服务器上的空洞：所以两条必须同时成立 ——
 * 界由 `maxEventsPerSession` 给（D12，测试也按同一公式复算），并且删过的会话要留下
 * `prunedBelowSeq` + `historyIncomplete`，让 UI 能说“历史可能不完整”而不是假装完整（D07/D08）。
 */
@RunWith(RobolectricTestRunner::class)
class EventRetentionTest {

    private lateinit var db: DshDatabase
    private lateinit var clock: FakeClock

    @Before
    fun setUp() {
        clock = TestData.clock()
        db = TestData.room()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun retention(cap: Int) = EventRetention(db, clock, SyncTuning(maxEventsPerSession = cap))

    private suspend fun seedEvents(seqRange: IntRange, session: SessionId = TestData.sessionId) {
        db.sessionEventDao().upsertEvents(
            seqRange.map { TestData.frame(it.toLong(), session = session).toEntity(TestData.serverId, clock.wallClockMs()) },
        )
    }

    private suspend fun seedHead(contiguous: Long?, session: SessionId = TestData.sessionId) {
        db.sessionHeadDao().putHeads(
            listOf(
                SessionHeadEntity(
                    serverId = TestData.serverId,
                    sessionId = session,
                    contiguousSeq = contiguous?.let { EventSeq(it) },
                    appliedMaxSeq = contiguous?.let { EventSeq(it) },
                ),
            ),
        )
    }

    private suspend fun storedSeqs(session: SessionId = TestData.sessionId): List<Long> =
        db.sessionEventDao()
            .eventsFrom(TestData.serverId.value, session.value, 1L, CAP_READ)
            .map { it.seq.value }

    @Test
    fun trimsOldestRowsDownToCapAndRecordsTheFloor() = runBlocking {
        seedEvents(1..10)
        seedHead(contiguous = 10)
        val trimmed = retention(CAP).trim().getOrThrow()

        assertEquals(5, trimmed)
        // 留最新 cap 行：删的是最老的，不是中间的。
        assertEquals((6L..10L).toList(), storedSeqs())

        val snapshot = retention(CAP).snapshot(TestData.serverId, TestData.sessionId).getOrThrow()
        assertEquals(5, snapshot.storedEvents)
        assertEquals(6L, snapshot.prunedBelowSeq ?: -1L)
        assertEquals(CAP, snapshot.capPerSession)
        // 裁过就必须承认历史可能不完整（D08）：UI 不能把裁掉的旧历史说成完整历史。
        assertTrue(db.sessionHeadDao().head(TestData.serverId.value, TestData.sessionId.value)!!.historyIncomplete)
    }

    @Test
    fun neverTouchesTheUncoveredFrontier() = runBlocking {
        // 投影只到 3：候选界 6 之后的 3~5 属于未覆盖前缀，一行都不能删。
        seedEvents(1..10)
        seedHead(contiguous = 3)
        assertEquals(2, retention(CAP).trim().getOrThrow())
        assertEquals((3L..10L).toList(), storedSeqs())

        // 界记的是夹后的位置而不是候选界：说成 6 会让历史修复跳过还在的 3~5。
        val snapshot = retention(CAP).snapshot(TestData.serverId, TestData.sessionId).getOrThrow()
        assertEquals(3L, snapshot.prunedBelowSeq ?: -1L)
        assertEquals(8, snapshot.storedEvents)
    }

    @Test
    fun sessionWithoutAHeadRowKeepsEveryRow() = runBlocking {
        // 没有 head 行 → 没有连续前缀 → 守卫整体为 NULL：宁可占容量，也不自造空洞。
        seedEvents(1..10)
        assertEquals(0, retention(CAP).trim().getOrThrow())
        assertEquals(10, storedSeqs().size)
        assertNull(retention(CAP).snapshot(TestData.serverId, TestData.sessionId).getOrThrow().prunedBelowSeq)
    }

    @Test
    fun sessionsUnderCapAreNotTrimmed() = runBlocking {
        seedEvents(1..CAP)
        seedHead(contiguous = CAP.toLong())
        assertEquals(0, retention(CAP).trim().getOrThrow())
        assertEquals(CAP, storedSeqs().size)
    }

    @Test
    fun trimIsIdempotentAndDoesNotDoubleCountDiagnostics() = runBlocking {
        seedEvents(1..10)
        seedHead(contiguous = 10)
        assertEquals(5, retention(CAP).trim().getOrThrow())
        assertEquals(5, db.diagnosticsDao().counter(DiagnosticsCounters.RETENTION_TRIMMED)!!.value)
        // 第二遍已经没有超量会话：计数不再增长，重跑不报错。
        assertEquals(0, retention(CAP).trim().getOrThrow())
        assertEquals(5, db.diagnosticsDao().counter(DiagnosticsCounters.RETENTION_TRIMMED)!!.value)
    }

    @Test
    fun eachSessionIsTrimmedAgainstItsOwnCap() = runBlocking {
        // 上限是每会话而不是全库：另一个会话超量不会让这个会话被删。
        seedEvents(1..10)
        seedHead(contiguous = 10)
        seedEvents(1..3, session = TestData.otherSession)
        seedHead(contiguous = 3, session = TestData.otherSession)

        assertEquals(5, retention(CAP).trim().getOrThrow())
        assertEquals(3, storedSeqs(TestData.otherSession).size)
    }

    private companion object {
        const val CAP = 5
        const val CAP_READ = 1_000
    }
}
