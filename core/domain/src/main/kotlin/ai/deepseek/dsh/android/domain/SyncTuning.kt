package ai.deepseek.dsh.android.domain

/**
 * 唯一的可调参数来源。数值理由见 docs/TUNING.md；除本文件与测试 fixture 外，源码不得出现这些语义的字面量。
 */
data class SyncTuning(
    val pollWaitMs: Long = 25_000,
    val pollWaitMsWorker: Long = 15_000,
    val pollWaitUnsupportedDelayMs: Long = 2_000,
    val workerMaxRounds: Int = 3,
    val workerRoundBudgetMs: Long = 60_000,
    val backoffInitialMs: Long = 1_500,
    val backoffMultiplier: Double = 2.0,
    val backoffMaxMs: Long = 60_000,
    val backoffJitterLow: Double = 0.8,
    val backoffJitterHigh: Double = 1.2,
    val wsFailureDowngradeThreshold: Int = 3,
    val wsProbeIntervalMs: Long = 30_000,
    val badJsonCloseThreshold: Int = 3,
    val auth401ProbeBudget: Int = 1,
    val auth401BackgroundPauseThreshold: Int = 3,
    val channelMaxBytes: Long = 8L * 1024 * 1024,
    val historyPageSize: Int = 50,
    val historyWindowItems: Int = 180,
    val rosterPageSize: Int = 30,
    val fsListPageSize: Int = 50,
    val fsListSoftCap: Int = 2_000,
    val rosterForegroundStaleMs: Long = 10L * 60 * 1000,
    val rosterWorkerStaleMs: Long = 30L * 60 * 1000,
    val baselineKeySweepSkipThreshold: Int = 400,
    val baselineKeySweepResumeThreshold: Int = 300,
    val needsResyncMaxAttempts: Int = 2,
    val historyRepairPageBudget: Int = 20,
    val islandMergeGap: Long = 1,
    val maxEventsPerSession: Int = 5_000,
    val identityPurgeAfterMs: Long = 7L * 24 * 60 * 60 * 1000,
    val tombstoneTtlMs: Long = 24L * 60 * 60 * 1000,
    val uploadChunkBytes: Int = 4 * 1024 * 1024,
    val fsPreviewMaxBytes: Long = 1024 * 1024,
    val imagePromptMaxBytes: Long = 3L * 1024 * 1024,
    val draftDebounceMs: Long = 2_000,
    val releaseAabBudgetBytes: Long = 12L * 1024 * 1024,
    val releaseApkBudgetBytes: Long = 20L * 1024 * 1024,
) {
    companion object {
        val DEFAULT = SyncTuning()
    }

    init {
        require(pollWaitMsWorker <= pollWaitMs)
        require(baselineKeySweepResumeThreshold < baselineKeySweepSkipThreshold)
        require(rosterWorkerStaleMs > rosterForegroundStaleMs) { "Worker 阈值必须大于周期，否则每轮都拉全量" }
        require(historyWindowItems >= historyPageSize) { "窗口至少容纳一页" }
    }

    fun nextBackoffMs(attempt: Int, random: Double): Long {
        require(random in backoffJitterLow..backoffJitterHigh) { "jitter must be within the configured range" }
        val raw = backoffInitialMs * Math.pow(backoffMultiplier, attempt.toDouble())
        return minOf(raw * random, backoffMaxMs.toDouble()).toLong()
    }
}
