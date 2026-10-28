package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.domain.PendingContentDecoder
import ai.deepseek.dsh.android.model.PendingContent
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.protocol.decodePendingContent

/**
 * `PendingContentDecoder` 的唯一实现（D32）：特性模块只注入端口，JSON 解析只发生在 `:core:protocol`。
 * 本类不做任何额外兜底——`decodePendingContent` 已经把坏 JSON 收敛为 `Undecodable`，
 * 在这里再加一层 try/catch 只会制造第二套“什么算坏”的判断。
 */
class ProtocolPendingContentDecoder : PendingContentDecoder {
    override fun decode(kind: PendingKind, payloadJson: String): PendingContent =
        decodePendingContent(kind, payloadJson)
}
