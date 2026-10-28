package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.domain.FakeClock
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.model.SyncOwnerRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * G18（任务书测试 c）：前后台所有权裁决（D10）。
 *
 * 断言的是**语义**而不是实现细节：Room 的 `sync_owner` 单行是跨进程唯一真相，因此“谁在临界区里”
 * 只能由角色 + holder + 接管标记三件事共同决定；崩溃遗留的持有必须能过期，否则所有权永久卡死。
 */
@RunWith(RobolectricTestRunner::class)
class SyncOwnerArbiterTest {

    private lateinit var db: DshDatabase
    private lateinit var store: RoomSyncStateStore
    private lateinit var clock: FakeClock
    private lateinit var arbiter: SyncOwnerArbiter

    @Before
    fun setUp() {
        clock = TestData.clock()
        db = TestData.room()
        store = RoomSyncStateStore(db, clock)
        arbiter = SyncOwnerArbiter(store, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 过期预算是推导值（D12 不新增旋钮），测试按同一个公式复算，避免把数字抄成字面量。 */
    private fun staleMs(): Long = SyncTuning.DEFAULT.workerRoundBudgetMs * SyncTuning.DEFAULT.workerMaxRounds

    private fun snapshot() = runBlocking { store.owner() }

    @Test
    fun workerClaimsIdleOwnerOnceAndSecondWorkerIsBusy() = runBlocking {
        assertEquals(OwnerClaim.Acquired, arbiter.claimForWorker("worker-a"))
        assertEquals(SyncOwnerRole.Worker, snapshot().role)
        // 同一回合内另一个 Worker 实例（进程重启后的等价物）不能插队：Busy 交给退避处理。
        assertEquals(OwnerClaim.Busy, arbiter.claimForWorker("worker-b"))
        assertEquals("worker-a", snapshot().holderId)
    }

    @Test
    fun workerNeverPreemptsForeground() = runBlocking {
        assertEquals(OwnerClaim.Acquired, arbiter.claimForForeground("ui"))
        clock.advance(staleMs() + 1)
        // 就算“过期”的判据已经成立，前台持有也不参与过期通道：用户在场优先。
        assertEquals(OwnerClaim.Busy, arbiter.claimForWorker("worker-a"))
        assertEquals(SyncOwnerRole.Foreground, snapshot().role)
        assertEquals("ui", snapshot().holderId)
    }

    @Test
    fun foregroundPreemptsWorkerAndForcesYield() = runBlocking {
        arbiter.claimForWorker("worker-a")
        assertFalse(arbiter.shouldYield("worker-a"))
        assertEquals(OwnerClaim.Acquired, arbiter.claimForForeground("ui"))
        assertEquals(SyncOwnerRole.Foreground, snapshot().role)
        assertTrue(arbiter.shouldYield("worker-a"))
        // 所有权已经换人，原持有者不能把它清成 None。
        assertFalse(arbiter.releaseWorker("worker-a"))
    }

    @Test
    fun foregroundClaimIsIdempotent() = runBlocking {
        arbiter.claimForForeground("ui")
        val claimedAt = snapshot().claimedAtMs
        clock.advance(1_000)
        assertEquals(OwnerClaim.Acquired, arbiter.claimForForeground("ui"))
        // 幂等：不重新盖时间戳，否则每次重组都会重置持有时长。
        assertEquals(claimedAt, snapshot().claimedAtMs)
    }

    @Test
    fun staleWorkerClaimIsTakenOverByNextWorker() = runBlocking {
        arbiter.claimForWorker("worker-a")
        clock.advance(staleMs())
        // 恰好等于预算还不算过期（严格大于），边界上不会把正在收尾的回合抢走。
        assertEquals(OwnerClaim.Busy, arbiter.claimForWorker("worker-b"))
        clock.advance(1)
        assertEquals(OwnerClaim.Acquired, arbiter.claimForWorker("worker-b"))
        assertEquals("worker-b", snapshot().holderId)
    }

    @Test
    fun handoffFlagYieldsWithoutChangingOwner() = runBlocking {
        arbiter.claimForWorker("worker-a")
        arbiter.requestForegroundHandoff(true)
        assertTrue(arbiter.shouldYield("worker-a"))
        // 标记只是请求：所有者仍是 Worker，前台还没进临界区。
        assertEquals(SyncOwnerRole.Worker, snapshot().role)
        assertEquals(TestData.nowMs, snapshot().foregroundRequestedAtMs ?: -1L)

        arbiter.requestForegroundHandoff(false)
        assertFalse(arbiter.shouldYield("worker-a"))
        assertNull(snapshot().foregroundRequestedAtMs)
    }

    @Test
    fun releaseForegroundClearsOwnerAndHandoffFlag() = runBlocking {
        arbiter.claimForWorker("worker-a")
        arbiter.requestForegroundHandoff(true)
        assertEquals(OwnerClaim.Acquired, arbiter.claimForForeground("ui"))
        assertTrue(arbiter.releaseForeground("ui"))
        assertEquals(SyncOwnerRole.None, snapshot().role)
        assertNull(snapshot().foregroundRequestedAtMs)
        // 释放后 Worker 才进得来。
        assertEquals(OwnerClaim.Acquired, arbiter.claimForWorker("worker-a"))
    }

    @Test
    fun holderCannotReleaseAfterRoleIsRewritten() = runBlocking {
        arbiter.claimForWorker("worker-a")
        // 别的进程把所有权清掉（崩溃恢复的等价物）：原持有者既该交还也不该重复释放。
        store.casOwner(SyncOwnerRole.Worker, SyncOwnerRole.None, "worker-a", clock.wallClockMs())
        assertTrue(arbiter.shouldYield("worker-a"))
        assertFalse(arbiter.releaseWorker("worker-a"))
    }

    @Test
    fun workerReleaseHandsTheCriticalSectionBack() = runBlocking {
        arbiter.claimForWorker("worker-a")
        assertTrue(arbiter.releaseWorker("worker-a"))
        assertEquals(SyncOwnerRole.None, snapshot().role)
        assertEquals(OwnerClaim.Acquired, arbiter.claimForWorker("worker-b"))
    }
}
