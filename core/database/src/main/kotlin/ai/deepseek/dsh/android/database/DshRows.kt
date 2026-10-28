package ai.deepseek.dsh.android.database

/**
 * 聚合查询的行对象。刻意只用基础类型（String / Long / Int / Boolean / 可空版本）：
 * 它们是 SQL 计算结果，不是领域对象，列别名即字段名；类型转换由 `:core:data` 的适配器完成。
 */

/** `COUNT(*) AS total`。 */
data class CountRow(val total: Int)

/** 会话主键对（跨表清理用）。 */
data class SessionKeyRow(val serverId: String, val sessionId: String)

/** 每个会话的原始帧行数，保留裁剪只遍历超限的会话（避免全库扫描）。 */
data class SessionEventCountRow(val serverId: String, val sessionId: String, val eventCount: Int)

/** 全局 pending 聚合（收件箱计数、completeness 的时间判断）。 */
data class PendingBadgeRow(
    val badge: Int,
    val dismissed: Int,
    val pendingCount: Int,
    val answeringCount: Int,
    val unknownCount: Int,
    val resolvedCount: Int,
    val staleCount: Int,
    val tombstonedCount: Int,
    val pendingKeyCount: Int,
    val maxAsOfSeq: Long?,
    val lastSweptAtMs: Long?,
)

/** 按会话分组的 pending 聚合（会话卡片 chip 与时间线锚点）。 */
data class PendingSessionGroupRow(
    val sessionId: String,
    val badge: Int,
    val dismissed: Int,
    val unknownCount: Int,
    val staleCount: Int,
    val tombstonedCount: Int,
    val maxAsOfSeq: Long?,
)

/** 需要取消/撤回的系统通知 tag 聚合。 */
data class OutboxTagRow(val notificationTag: String, val requestCount: Int)

/** 单行所有者状态（三列一次读出，CAS 后回读用）。 */
data class SyncOwnerStateRow(
    val role: String,
    val holderId: String,
    val foregroundRequestedAtMs: Long?,
)

/**
 * 诊断快照的持久化部分（G10 / D23）。
 * 计数型字段（badJson / wsDowngrade / retentionTrimmed）走 `diagnostics_counters`，
 * 瞬时字段（authState、lastPollWaitSupported、capabilities、rosterAgeMs）不在这里：
 * 前者来自 `auth_scope_failures` 与 [SyncOwnerStateRow]，后者属于连接层内存状态。
 */
data class DiagnosticsAggregateRow(
    val needsResyncCount: Int,
    val degradedResyncCount: Int,
    val historyUnsafeSessions: Int,
    val historyIncompleteSessions: Int,
    val laggingSessions: Int,
    val maxContiguousLag: Long?,
    val contiguousLagSum: Long?,
    val retiredIdentities: Int,
    val cursorRows: Int,
    val liveSessions: Int,
    val tombstonedSessions: Int,
    val pendingBadge: Int,
    val pendingRows: Int,
    val outboxNew: Int,
    val authScopeFailureScopes: Int,
    val authScopeFailureCount: Long,
)
