package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.model.SyncAttemptResult
import ai.deepseek.dsh.android.domain.SyncTuning

/** Worker 的一次收尾决定：交给 WorkManager 的三态结果。 */
enum class WorkerOutcome { Success, Retry, Cancel }

/**
 * `SyncAttemptResult` → WorkManager 结果的唯一映射表（验收 G18）。
 *
 * 判据是“重试能不能改变结局”，不是“这次算不算失败”：
 * - [SyncAttemptResult.AuthExpired] 要用户重新配对，重试只会形成 401 风暴，所以结束本轮并让 UI 呈现修复动作；
 * - [SyncAttemptResult.Busy] 与 [SyncAttemptResult.Unreachable] 都是暂时性的，交给 WorkManager 退避后重来；
 * - 前台接管意味着同步仍在进行，只是换了所有者，Worker 无事可做；
 * - 后台开关关掉（D24）时任何结果都不再排下一轮：周期任务由端口取消，这里也立刻终止本轮。
 */
class WorkerResultPolicy(
    private val tuning: SyncTuning = SyncTuning.DEFAULT,
) {

    fun outcomeFor(
        result: SyncAttemptResult,
        foregroundActive: Boolean,
        backgroundEnabled: Boolean,
    ): WorkerOutcome {
        if (!backgroundEnabled) return WorkerOutcome.Cancel
        if (foregroundActive) return WorkerOutcome.Success
        return when (result) {
            SyncAttemptResult.Completed -> WorkerOutcome.Success
            SyncAttemptResult.ForegroundTookOver -> WorkerOutcome.Success
            SyncAttemptResult.Busy -> WorkerOutcome.Retry
            SyncAttemptResult.Unreachable -> WorkerOutcome.Retry
            SyncAttemptResult.AuthExpired -> WorkerOutcome.Success
        }
    }

    /**
     * 退避延迟沿用 D12 的 jitter 区间：`random` 必须在 [SyncTuning.nextBackoffMs] 允许的范围内，
     * 调用方给越界值就按契约失败，而不是悄悄夹到一个“差不多”的延迟。
     */
    fun retryDelayMs(attempt: Int, random: Double): Long = tuning.nextBackoffMs(attempt, random)
}
