package ai.deepseek.dsh.android.model

@JvmInline
value class ServerId(val value: String)

@JvmInline
value class SessionId(val value: String)

/** 会话内单调序号，来自 SessionEvent.seq。 */
@JvmInline
value class EventSeq(val value: Long)

/** 传输层通道：mux（会话帧）与 host（会话名册/状态）。 */
@JvmInline
value class ChannelKind(val value: String) {
    companion object {
        val Mux = ChannelKind("mux")
        val Host = ChannelKind("host")
    }
}

/** pending 请求的标识：approval 取 approval/asked 的 data.id，question 取 ask_user_question 的 callId。 */
@JvmInline
value class RequestId(val value: String)

@JvmInline
value class CallId(val value: String)

/**
 * 通知 tag，格式冻结为 `digest + ":" + wirePrefix + id`（即 `<64hex>:a:<requestId>`）。
 * 取消通知靠它精确命中，因此格式是契约而非实现细节：改动会让旧版本发出的通知永远撤不掉。
 * 只由本工厂构造，字符串拼接不散落到 data/UI 层。
 */
@JvmInline
value class NotificationTag(val value: String) {
    init {
        require(Regex("^[0-9a-f]{64}:[aq]:.+").matches(value)) { "notification tag format is frozen" }
    }

    companion object {
        fun of(identity: ConnectionIdentity, kind: PendingKind, requestId: RequestId): NotificationTag =
            NotificationTag("${identity.digest}:${kind.wirePrefix}${requestId.value}")
    }
}

@JvmInline
value class ConnectionIdentity(val digest: String) {
    init {
        require(digest.matches(Regex("^[0-9a-f]{64}$"))) { "identity digest must be lowercase sha256 hex" }
    }
}

/**
 * 一次连接的身份派生：sha256(stored base 去掉末尾斜杠 + "\n" + token)，UTF-8，小写 hex。
 * 权威公式来自 dsh-Remote 遗留 Android 客户端，逐字节匹配；不得用 HttpUrl 归一化、不得小写 host、
 * 不得省略默认端口、不得归一路径。
 */
object IdentityDigest {
    fun of(storedBase: String, token: String): String {
        val base = storedBase.trimEnd('/')
        val bytes = (base + "\n" + token).toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
