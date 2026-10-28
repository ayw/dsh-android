package ai.deepseek.dsh.android.domain.usecase

import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.RemoteFailure
import ai.deepseek.dsh.android.domain.RemoteSource
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.SendPromptResult
import ai.deepseek.dsh.android.domain.repository.toDomainError
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.PromptAttachment
import ai.deepseek.dsh.android.model.PromptRequest
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.WriteResult

/**
 * 发送提示词的写侧交互。规则来自 DECISIONS：**accepted != executed**。
 * - `accepted==true`：返回 `Delivered`，operations>=1 时用回执把 UNKNOWN 窗口收窄；
 * - 超时/不可达：一律 `Unknown`，**永不自动重试**（重试属于用户显式动作）。
 *
 * 本类不写 `DurableStore`：outbox 的唯一含义是通知外发期望（D28），提示词既没有通知 tag，
 * 也没有读取这一行的消费者，落一条只会把「写请求」和「系统通知」两件事混进同一张表。
 *
 * 无 Android/OkHttp/Room/WorkManager 依赖：全部端口注入，`:core:data` 负责组装。
 */
class SendPromptUseCase(
    private val remote: RemoteSource,
    private val capabilities: () -> BackendCapabilities?,
    @Suppress("UNUSED_PARAMETER") clock: Clock,
) {
    /**
     * @param requestId 幂等/追踪键，由调用方（`:core:data`/UI）提供，用于把结果关联回这一次发送；
     *   本类不生成键。
     */
    suspend operator fun invoke(
        sessionId: SessionId,
        text: String,
        requestId: String,
        attachments: List<PromptAttachment> = emptyList(),
    ): SendPromptResult {
        val outcome = try {
            remote.prompt(PromptRequest(sessionId, text, attachments))
        } catch (remoteFailure: RemoteFailure) {
            return onTransportFailure(requestId, toDomainError(remoteFailure))
        }
        return onWriteResult(requestId, outcome)
    }

    private fun onTransportFailure(requestId: String, error: DomainError): SendPromptResult = when (error) {
        // 结局未知：请求可能已落到服务端，绝不自动重试。
        DomainError.Timeout, DomainError.Unreachable -> SendPromptResult.Unknown(requestId, error)
        // 明确被拒（401/403/坏 JSON）：请求未被受理，可安全交给用户手动重试。
        else -> SendPromptResult.Rejected(requestId, error)
    }

    private fun onWriteResult(requestId: String, result: WriteResult): SendPromptResult =
        when {
            result.accepted == true ->
                SendPromptResult.Delivered(requestId, result.receiptId, capabilities()?.hasReceipts == true)
            result.accepted == false ->
                SendPromptResult.Rejected(requestId, DomainError.Rejected("not_accepted"))
            else ->
                // accepted 为空：结局未知，不重试。
                SendPromptResult.Unknown(requestId, DomainError.Timeout)
        }
}
