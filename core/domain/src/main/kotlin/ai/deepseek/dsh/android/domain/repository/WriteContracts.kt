package ai.deepseek.dsh.android.domain.repository

import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.WriteResult

/**
 * 写侧交互的类型化结果。核心不变量：**accepted != executed**——`accepted==true` 只表示请求被受理，
 * 不代表已执行；超时/不可达一律收敛为“未知”，UI 显示未知且**永不自动重试**（D13/F5/G08）。
 */

/** SendPromptUseCase 的结果。 */
sealed interface SendPromptResult {
    val requestId: String

    /** accepted==true：已在 outbox 落下期望。[receiptBacked] 为 true 时（operations>=1）附回执，未知窗口收窄。 */
    data class Delivered(override val requestId: String, val receiptId: String?, val receiptBacked: Boolean) :
        SendPromptResult

    /** 明确未被受理（401/403/坏 JSON/accepted:false 之外的拒绝），可安全地由用户手动重试。 */
    data class Rejected(override val requestId: String, val error: DomainError) : SendPromptResult

    /** 结局未知（超时/不可达/accepted 为空）：不重试，UI 显示未知。 */
    data class Unknown(override val requestId: String, val error: DomainError) : SendPromptResult
}

/** ResolvePendingUseCase 的结果：v0 只走前两支 + 超时；409 三分支保留给 v1。 */
sealed interface ResolvePendingResult {
    val requestId: RequestId

    /** accepted:true → 乐观 RESOLVED（等待确认，late resolved 事件不会把它降级）。 */
    data class Confirmed(override val requestId: RequestId) : ResolvePendingResult

    /** accepted:false → STALE，且不重试。 */
    data class Stale(override val requestId: RequestId) : ResolvePendingResult

    /** 超时/不可达/未定 → UNKNOWN，并已请求一次 baseline 刷新（仅超时/不可达触发）。 */
    data class Unknown(override val requestId: RequestId, val error: DomainError) : ResolvePendingResult

    /** 409 already-settled → tombstone。 */
    data class Tombstoned(override val requestId: RequestId) : ResolvePendingResult

    /** 409 stale-revision（v1）→ 重新读取该 revision；v0 allowlist 为空，永不命中。 */
    data class ReReadRequired(override val requestId: RequestId, val revision: String) : ResolvePendingResult

    /** 409 其它 → 冲突标签，保留该行。 */
    data class Conflict(override val requestId: RequestId, val error: DomainError.Conflict) : ResolvePendingResult
}

/** 会话级写操作（cancel/selectModel/rename/fork/archive）的统一结果。 */
sealed interface SessionWriteResult {
    /** 已受理；[receiptBacked] 同上。 */
    data class Delivered(val receiptId: String?, val receiptBacked: Boolean) : SessionWriteResult

    /** 明确未被受理。 */
    data class Rejected(val error: DomainError) : SessionWriteResult

    /** 结局未知：不重试。 */
    data class Unknown(val error: DomainError) : SessionWriteResult

    /** 能力缺失（v0 的 rename/fork/archive）：结构化的“没有这个功能”，非伪造成功。 */
    data class Unavailable(val capability: String) : SessionWriteResult {
        /** 对应的领域错误词汇，便于统一渲染/上报。 */
        val error: DomainError get() = DomainError.UnavailableCapability(capability)
    }
}

/** 409 冲突的三分类动作（纯决策，副作用由用例落地）。 */
enum class ResolveConflictAction { Tombstone, ReReadRevision, KeepWithConflict }

/**
 * 纯函数：把 v1 的 409 机器可读原因分类。`already-settled`→落 tombstone；命中
 * [staleRevisionAllowlist] 的 revision→重读；其余→冲突标签保留。**v0 的 allowlist 为空**，
 * 故除 already-settled 外一律落到 KeepWithConflict（D13/F5）。
 */
fun classifyResolveConflict(
    reason: String?,
    staleRevisionAllowlist: Set<String>,
): ResolveConflictAction = when {
    reason == RESOLVE_REASON_ALREADY_SETTLED -> ResolveConflictAction.Tombstone
    reason != null && reason in staleRevisionAllowlist -> ResolveConflictAction.ReReadRevision
    else -> ResolveConflictAction.KeepWithConflict
}

/** 与网关约定的“已结算”原因串（v1 才出现；v0 无 409）。 */
const val RESOLVE_REASON_ALREADY_SETTLED: String = "already-settled"

/** 能力门：只有后端在整数 capability 表里登记且 >=1 才算允许；未知能力 ⇒ 隐藏（G08）。 */
fun capabilityAllowed(capabilities: BackendCapabilities?, name: String): Boolean =
    capabilities != null && !capabilities.unknown(name) && (capabilities.raw[name] ?: 0) >= 1

/** [WriteResult]（传输返回体）→ 领域写结果的收敛映射：accepted 三态 + operations 回执。 */
fun WriteResult.toSessionWriteResult(receiptBacked: Boolean): SessionWriteResult = when (outcome) {
    WriteResult.WriteOutcome.Accepted, WriteResult.WriteOutcome.ReceiptPending ->
        SessionWriteResult.Delivered(receiptId, receiptBacked)
    WriteResult.WriteOutcome.Rejected ->
        SessionWriteResult.Rejected(DomainError.Rejected("not_accepted"))
    WriteResult.WriteOutcome.Forbidden ->
        SessionWriteResult.Rejected(DomainError.Forbidden("operation"))
    WriteResult.WriteOutcome.Unknown ->
        SessionWriteResult.Unknown(DomainError.Timeout)
}

/** pending 主键里的 kind 落到持久层的字符串表示（与 [PendingStore] 的 `kind: String` 对齐）。 */

