package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.model.SyncAttemptResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * G18（任务书测试 c）：Worker 收尾语义。
 *
 * 这条映射表是 `SyncAttemptResult` → WorkManager 结果的唯一真相，因此断言的是**判据**：
 * 重试能不能改变结局。失败但不该重试的（AuthExpired）与成功但没干活的（前台接管）都归 Success，
 * 只有暂时性结果才 Retry；后台开关（D24）优先级最高，直接 Cancel。
 */
class WorkerResultPolicyTest {

    private val policy = WorkerResultPolicy()

    private fun outcome(
        result: SyncAttemptResult,
        foregroundActive: Boolean = false,
        backgroundEnabled: Boolean = true,
    ): WorkerOutcome = policy.outcomeFor(result, foregroundActive, backgroundEnabled)

    @Test
    fun backgroundSwitchOffCancelsEveryResult() {
        // 开关关掉时不再排下一轮；周期任务由端口取消，这里立刻终止本轮。
        SyncAttemptResult.entries.forEach { result ->
            assertEquals(WorkerOutcome.Cancel, outcome(result, backgroundEnabled = false))
        }
    }

    @Test
    fun foregroundPresenceEndsWorkerRoundSuccessfully() {
        // 所有权已交给前台：这不是失败，Worker 无事可做，重试只会再来抢一次位。
        SyncAttemptResult.entries.forEach { result ->
            assertEquals("foreground wins over $result", WorkerOutcome.Success, outcome(result, foregroundActive = true))
        }
    }

    @Test
    fun transientResultsRetry() {
        assertEquals(WorkerOutcome.Retry, outcome(SyncAttemptResult.Busy))
        assertEquals(WorkerOutcome.Retry, outcome(SyncAttemptResult.Unreachable))
    }

    @Test
    fun terminalResultsEndTheRoundWithoutRetry() {
        assertEquals(WorkerOutcome.Success, outcome(SyncAttemptResult.Completed))
        assertEquals(WorkerOutcome.Success, outcome(SyncAttemptResult.ForegroundTookOver))
        // 401 需要用户重新配对；Worker 重试只会形成 401 风暴，修复动作交给 UI。
        assertEquals(WorkerOutcome.Success, outcome(SyncAttemptResult.AuthExpired))
    }

    @Test
    fun everyOutcomeIsReachableAndNothingIsSilentlyDropped() {
        // 三态各有归属：漏掉任何一态，说明映射表把某种结局悄悄归进了别的桶。
        val reachable = SyncAttemptResult.entries
            .flatMap { listOf(outcome(it), outcome(it, foregroundActive = true), outcome(it, backgroundEnabled = false)) }
            .toSet()
        assertEquals(WorkerOutcome.entries.toSet(), reachable)
    }

    @Test
    fun retryDelayComesFromTheSingleTuningSource() {
        val tuning = SyncTuning.DEFAULT
        assertEquals(tuning.nextBackoffMs(0, 1.0), policy.retryDelayMs(0, 1.0))
        assertEquals(tuning.nextBackoffMs(3, 1.0), policy.retryDelayMs(3, 1.0))
        // 上限夹在 backoffMaxMs：退避不能无限增长。
        val attemptTowardCeiling = 10
        assertEquals(tuning.backoffMaxMs, policy.retryDelayMs(attemptTowardCeiling, 1.0))
    }

    @Test
    fun jitterOutsideConfiguredRangeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { policy.retryDelayMs(0, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { policy.retryDelayMs(0, 1.5) }
    }
}
