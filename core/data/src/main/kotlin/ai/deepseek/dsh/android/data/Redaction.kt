package ai.deepseek.dsh.android.data

/**
 * 数据层“该登记哪些秘密”的唯一推导点（D16/G09.2）。
 *
 * 这里**不实现日志**：日志门面只有 `:core:network` 的 `DshLog` 一个。数据层的问题是
 * “一轮传输里哪些字符串必须交给 `DshLog.redact`”，如果每个调用点各自截取 URL/票据，
 * 迟早有人漏掉 query 或带上 `?` 前缀导致匹配失败。集中成一个纯函数后，
 * 调用点只做 `DshLog.redact(x)` 的循环，测试也能用同一份清单断言“确实登记全了”。
 */
data object Redaction {

    /**
     * 返回应登记给 `DshLog.redact` 的原始字符串：
     *  - 凭据 token（全权限，泄露即等于设备被接管）；
     *  - ws ticket（90s 内最多 4 次使用，仍是可直接连入的凭据）；
     *  - URL 的 query 段（ticket/epoch 等都在这里，且文件路径/提示词也可能拼进查询参数）。
     * 返回顺序稳定、已去重；空值不登记（`DshLog.redact` 也会忽略空串，但提前滤掉让清单可断言）。
     */
    fun registrableSecrets(credential: String, wsTicket: String?, url: String?): List<String> {
        val out = LinkedHashSet<String>()
        if (credential.isNotEmpty()) out += credential
        if (!wsTicket.isNullOrEmpty()) out += wsTicket
        queryOf(url)?.let { out += it }
        return out.toList()
    }

    /** 测试与防御性断言共用：消息里是否还残留任一秘密。 */
    fun messageContainsAny(message: String, secrets: List<String>): Boolean =
        secrets.any { it.isNotEmpty() && message.contains(it) }

    /** 只取 `?` 与 `#` 之间的原文；去掉 `?` 前缀，因为登记的是 query 内容本身。 */
    private fun queryOf(url: String?): String? {
        val raw = url?.substringAfter('?', "")?.substringBefore('#')?.trim()
        return raw?.takeIf { it.isNotEmpty() }
    }
}
