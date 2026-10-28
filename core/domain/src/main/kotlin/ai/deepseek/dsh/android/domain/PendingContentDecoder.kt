package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.PendingContent
import ai.deepseek.dsh.android.model.PendingKind

/**
 * 待处理卡片内容的解码端口（D32）。实现位于 `:core:data`（唯一允许依赖 `:core:protocol` 的层），
 * 特性模块只消费类型化结果，不解析 JSON，也不接触协议 DTO。
 */
fun interface PendingContentDecoder {
    fun decode(kind: PendingKind, payloadJson: String): PendingContent
}
