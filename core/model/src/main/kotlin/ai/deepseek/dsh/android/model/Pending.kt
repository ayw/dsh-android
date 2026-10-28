package ai.deepseek.dsh.android.model

enum class PendingKind {
    Approval,
    Question;

    /** 通知 tag 里的类型段，含分隔冒号；格式随 NotificationTag 一起冻结。 */
    val wirePrefix: String get() = if (this == Approval) "a:" else "q:"
}

/**
 * PENDING → ANSWERING → RESOLVED | STALE | UNKNOWN；
 * PENDING 也可被其他客户端直接 RESOLVED，或在基线成功快照后不存在时转 STALE；
 * UNKNOWN 不是终态，可被基线重新确认回 PENDING。
 */
enum class PendingStatus {
    Pending, Answering, Resolved, Stale, Unknown;

    val countsTowardBadge: Boolean get() = this == Pending || this == Answering || this == Unknown
}

/**
 * v0 没有 pending 读接口，完整性只能由 baseline + events 近似；
 * CompleteByServer 预留给 /mobile/v1/pending，避免 BFF 切换时改枚举。
 */
enum class PendingCompleteness { Complete, Stale, Unknown, CompleteByServer }

data class PendingRequest(
    val serverId: ServerId,
    val kind: PendingKind,
    val requestId: RequestId,
    val sessionId: SessionId,
    val status: PendingStatus,
    val completeness: PendingCompleteness,
    val payloadJson: String,
    val firstSeenAtMs: Long,
    val createdAtMs: Long,
    /** 手动划掉：不减少 resolvedAt 为空的集合，也不等于 tombstone。 */
    val dismissedAtMs: Long? = null,
    val resolvedAtMs: Long? = null,
    val anchorSeq: EventSeq? = null,
    val callId: CallId? = null,
    val toolName: String? = null,
) {
    val visibleInBadge: Boolean get() = status.countsTowardBadge && dismissedAtMs == null
}

/** 收件箱计数：PENDING+ANSWERING+UNKNOWN；RESOLVED/STALE 不计；打开列表不减少。 */
data class PendingCounts(
    val badge: Int?,
    val dismissed: Int,
    val completeness: PendingCompleteness,
) {
    /** sweep 未执行时不能显示数字，只能显示未知。 */
    val showsUnknown: Boolean get() = badge == null || completeness == PendingCompleteness.Unknown
}
