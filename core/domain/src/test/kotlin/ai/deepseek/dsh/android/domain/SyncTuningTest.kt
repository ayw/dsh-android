package ai.deepseek.dsh.android.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 默认值必须真的能构造出来：`SyncTuning.DEFAULT` 若在 `<clinit>` 里抛，所有读它的测试只会看到
 * `NoClassDefFoundError`，真实原因（哪条 require  violated）被完全吞掉。
 */
class SyncTuningTest {

    @Test
    fun `DEFAULT satisfies every invariant`() {
        assertEquals(50, SyncTuning.DEFAULT.historyPageSize)
        assertEquals(180, SyncTuning.DEFAULT.historyWindowItems)
        assertEquals(1, SyncTuning.DEFAULT.islandMergeGap)
    }

    @Test
    fun `worker poll wait never exceeds the foreground poll wait`() {
        assertFailsWith<IllegalArgumentException> { SyncTuning(pollWaitMs = 1_000, pollWaitMsWorker = 2_000) }
    }

    @Test
    fun `sweep resume threshold stays below the skip threshold`() {
        assertFailsWith<IllegalArgumentException> {
            SyncTuning(baselineKeySweepSkipThreshold = 300, baselineKeySweepResumeThreshold = 300)
        }
    }

    @Test
    fun `worker roster threshold must exceed the foreground threshold`() {
        assertFailsWith<IllegalArgumentException> {
            SyncTuning(rosterForegroundStaleMs = 30L * 60 * 1000, rosterWorkerStaleMs = 10L * 60 * 1000)
        }
    }

    @Test
    fun `window holds at least one page`() {
        assertFailsWith<IllegalArgumentException> { SyncTuning(historyPageSize = 50, historyWindowItems = 49) }
    }

    @Test
    fun `jitter outside the configured range is rejected rather than silently clamped`() {
        val tuning = SyncTuning.DEFAULT
        assertFailsWith<IllegalArgumentException> { tuning.nextBackoffMs(attempt = 1, random = 1.9) }
        assertEquals(1_500L, tuning.nextBackoffMs(attempt = 0, random = 1.0))
        assertEquals(tuning.backoffMaxMs, tuning.nextBackoffMs(attempt = 20, random = 1.0))
    }
}
