package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.IdentityDigest
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F7（docs/DECISIONS.md）：`sha256(base.replace(/\/+$/,"") + "\n" + token)`，UTF-8，小写 hex。
 *
 * 公式被钉两次：一条手写 golden 向量（不依赖生产实现的任何中间步骤），以及 JDK
 * `MessageDigest`/`HexFormat` 的独立重算。两处都只允许“去掉 base 末尾斜杠”这一种归一化。
 */
class IdentityDigestTest {

    private val goldenBase = "https://dsh.example.com:8443/gw"
    private val goldenToken = "tok_ABC123"

    /** 手写 golden：sha256("https://dsh.example.com:8443/gw" + "\n" + "tok_ABC123")。 */
    private val goldenHex = "68ed474cd76419bb7cef7b8b3079673c28f1add02ab0f73d4890aa5dabf7c68c"

    /** 同一公式的独立实现：只按 F7 原文去掉末尾斜杠，其余字节原样进入 preimage。 */
    private fun expected(storedBase: String, token: String, separator: String = "\n"): String {
        val canonical = storedBase.replace(Regex("/+$"), "")
        val bytes = (canonical + separator + token).toByteArray(Charsets.UTF_8)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    @Test
    fun identityDigestMatchesTheHandWrittenGoldenVector() {
        assertEquals(goldenHex, IdentityDigest.of(goldenBase, goldenToken))
    }

    @Test
    fun identityDigestIsLowercaseSha256HexAcceptedByTheModel() {
        val digest = IdentityDigest.of(goldenBase, goldenToken)
        assertEquals(64, digest.length)
        assertTrue(digest.matches(Regex("^[0-9a-f]{64}$")), "digest must be lowercase hex, was $digest")
        // ConnectionIdentity 的 init 是第二道钉：大写或截短的 hex 会在这里抛。
        assertEquals(digest, ConnectionIdentity(digest).digest)
    }

    @Test
    fun identityDigestIsSha256OfBaseNewlineTokenInUtf8() {
        assertEquals(expected(goldenBase, goldenToken), IdentityDigest.of(goldenBase, goldenToken))
        // 非 ASCII token 钉 UTF-8（而不是平台的默认字符集）。
        assertEquals(
            "95c7e53662742a33e9423a6718f015d1e765ad0b53dba86de87ae4d645d5b12e",
            IdentityDigest.of("https://example.test/gw", "token-口令"),
        )
        assertEquals(expected("https://example.test/gw", "token-口令"), IdentityDigest.of("https://example.test/gw", "token-口令"))
        // 分隔符是 "\n"，不是空串、也不是 "\u0000"（Web 客户端的公式不得被参照）。
        assertNotEquals(expected(goldenBase, goldenToken, separator = ""), IdentityDigest.of(goldenBase, goldenToken))
        assertNotEquals(expected(goldenBase, goldenToken, separator = "\u0000"), IdentityDigest.of(goldenBase, goldenToken))
    }

    @Test
    fun identityDigestStripsOnlyTrailingSlashesFromTheBase() {
        val digest = IdentityDigest.of(goldenBase, goldenToken)
        assertEquals(goldenHex, IdentityDigest.of("$goldenBase/", goldenToken))
        assertEquals(goldenHex, IdentityDigest.of("$goldenBase///", goldenToken))
        assertEquals(digest, IdentityDigest.of("$goldenBase////", goldenToken))
        // token 里的斜杠不是 base 的末尾斜杠，不能被裁掉。
        assertEquals(expected(goldenBase, "tok/"), IdentityDigest.of(goldenBase, "tok/"))
        assertNotEquals(IdentityDigest.of(goldenBase, "tok/"), IdentityDigest.of(goldenBase, "tok"))
        // 全是斜杠的 base 归一化成空串，而不是被丢弃成 null。
        assertEquals(expected("", goldenToken), IdentityDigest.of("///", goldenToken))
    }

    @Test
    fun identityDigestDoesNotNormalizeCasePortOrPath() {
        val upperHost = "https://DSH.Example.com/Gateway"
        val lowerHost = "https://dsh.example.com/Gateway"
        assertEquals(expected(upperHost, "t"), IdentityDigest.of(upperHost, "t"))
        assertNotEquals(IdentityDigest.of(lowerHost, "t"), IdentityDigest.of(upperHost, "t"))

        // 默认端口不得被省略或补全：:443 与无端口是两个身份。
        assertNotEquals(
            IdentityDigest.of("https://dsh.example.com/gw", "t"),
            IdentityDigest.of("https://dsh.example.com:443/gw", "t"),
        )

        // 路径只受末尾斜杠规则影响，不做大小写/点段/前缀归一化。
        assertNotEquals(
            IdentityDigest.of("https://dsh.example.com/Gateway", "t"),
            IdentityDigest.of("https://dsh.example.com/gateway", "t"),
        )
        assertNotEquals(
            IdentityDigest.of("https://dsh.example.com/gw", "t"),
            IdentityDigest.of("https://dsh.example.com/gw/internal", "t"),
        )
        assertNotEquals(
            IdentityDigest.of("https://dsh.example.com/gw", "t"),
            IdentityDigest.of("https://dsh.example.com/gw/./", "t"),
        )
    }

    @Test
    fun identityDigestDiffersPerTokenForTheSameBase() {
        val first = IdentityDigest.of(goldenBase, "token-a")
        val second = IdentityDigest.of(goldenBase, "token-b")
        assertNotEquals(first, second)
        assertEquals(first, IdentityDigest.of(goldenBase, "token-a"))
        assertEquals(expected(goldenBase, "token-b"), second)
        // token 轮换（重新配对）才是更换身份的唯一入口：新 token 不共享任何可比较前缀。
        assertNotEquals(first.take(8), second.take(8))
    }
}
