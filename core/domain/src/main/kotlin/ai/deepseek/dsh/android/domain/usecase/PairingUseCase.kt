package ai.deepseek.dsh.android.domain.usecase

import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.IdentityDigest
import ai.deepseek.dsh.android.model.ServerId

/**
 * 配对：解析 `dshremote://pair?token=...&server=...`（多地址 QR）→ 去重 → 产出**惰性** [PairingProposal]，
 * 只有显式 [PairingUseCase.confirm] 才落盘（经 [SecretWriter]，本层不碰 KeyStore）。
 * 身份 digest 一律走 `:core:model` 的 [IdentityDigest.of]（唯一权威，F7），逐字节匹配遗留客户端。
 *
 * 安全规则（G19）：
 * - http→https 只在**同 host:port** 时升级，并持久化 https base；
 * - 跨 host:port 的重定向**不携带 Authorization**（[decideRedirectAuth]）。
 * 所有时间经注入 [Clock]，无 `System.currentTimeMillis()`。
 */

enum class PairingScheme { Http, Https }

/** 单个候选地址：base 已去尾斜杠；digest = sha256(base + "\n" + token)。 */
data class PairingCandidate(
    val base: String,
    val scheme: PairingScheme,
    val identityDigest: String,
)

/** 未确认的配对提案：内存对象，不产生任何持久化副作用。[token] 为全权限凭据，绝不落日志。 */
data class PairingProposal(
    val serverIdHint: String?,
    val token: String,
    val candidates: List<PairingCandidate>,
    /** 当前将连接的地址走明文 HTTP 时为 true：确认卡必须展示 G19 的明文警告。 */
    val insecureHttp: Boolean = false,
) {
    /** 令牌指纹：sha256 前缀，只用于让用户核对“是同一枚令牌”，不含令牌原文的任何信息。 */
    val tokenFingerprint: String get() = sha256Hex(token).take(FINGERPRINT_CHARS)
}

/** 手动配对的解析结果：地址与令牌分开报告，UI 据此分别标红两个字段。 */
sealed interface ManualPairingParse {
    data class Proposal(val proposal: PairingProposal) : ManualPairingParse
    data object InvalidAddress : ManualPairingParse
    data object InvalidToken : ManualPairingParse
}

sealed interface PairingParseResult {
    data class Proposal(val proposal: PairingProposal) : PairingParseResult
    data class Invalid(val error: DomainError) : PairingParseResult
}

/** 确认后交回给 `:core:data` 落 Room 的描述符（不含 token）。 */
data class PairedServer(
    val serverId: ServerId,
    val identity: ConnectionIdentity,
    val storedBase: String,
    val upgradedToHttps: Boolean,
    val addedAtMs: Long,
)

/** 凭据写入端口：`:core:data` 用 AndroidKeyStore AES-256-GCM 实现（D14）；本层不做任何加解密。 */
interface SecretWriter {
    suspend fun write(identity: ConnectionIdentity, token: String)
}

/** 重定向时是否携带 Authorization。 */
enum class RedirectAuthDisposition { Carry, Strip }

/**
 * 纯函数：提取 URL 的 host:port 权限段——**保留大小写、保留端口、不省略默认端口、不做任何归一**。
 * 无 scheme:// 前缀或权限为空返回 null。
 */
fun urlAuthority(url: String): String? {
    val sep = url.indexOf("://")
    if (sep < 0) return null
    val afterScheme = url.substring(sep + 3)
    val authorityEnd = afterScheme.indexOf('/')
    val authority = if (authorityEnd < 0) afterScheme else afterScheme.substring(0, authorityEnd)
    return authority.ifEmpty { null }
}

private fun urlScheme(url: String): PairingScheme? =
    when (url.substringBefore("://", "").lowercase()) {
        "http" -> PairingScheme.Http
        "https" -> PairingScheme.Https
        else -> null
    }

/** 同 host:port 的 http→https 升级才允许（G19）。 */
fun isHttpsUpgradeAllowed(fromUrl: String, toUrl: String): Boolean {
    val fromAuth = urlAuthority(fromUrl) ?: return false
    val toAuth = urlAuthority(toUrl) ?: return false
    return urlScheme(fromUrl) == PairingScheme.Http &&
        urlScheme(toUrl) == PairingScheme.Https &&
        fromAuth == toAuth
}

/** 跨 host:port 一律 Strip；同 host:port（含同源 http→https）才 Carry。 */
fun decideRedirectAuth(originalUrl: String, redirectUrl: String): RedirectAuthDisposition {
    val a = urlAuthority(originalUrl) ?: return RedirectAuthDisposition.Strip
    val b = urlAuthority(redirectUrl) ?: return RedirectAuthDisposition.Strip
    return if (a == b) RedirectAuthDisposition.Carry else RedirectAuthDisposition.Strip
}

private const val QR_PREFIX = "dshremote://"
private const val QR_HOST = "pair"
private const val SERVER_ID_PREFIX_LEN = 16

class PairingUseCase(private val clock: Clock) {

    /** 解析并去重多地址；不写任何存储。任一必需字段缺失或地址非法即 [PairingParseResult.Invalid]。 */
    fun parse(qr: String): PairingParseResult {
        val trimmed = qr.trim()
        if (!trimmed.startsWith(QR_PREFIX)) return invalid("scheme")
        val rest = trimmed.removePrefix(QR_PREFIX)
        val queryIndex = rest.indexOf('?')
        val authority = if (queryIndex < 0) rest else rest.substring(0, queryIndex)
        val query = if (queryIndex < 0) "" else rest.substring(queryIndex + 1)
        if (authority != QR_HOST) return invalid("host")

        val params = parseQuery(query)
        val token = params.lastOrNull { it.first == "token" }?.second?.trim().orEmpty()
        if (token.isEmpty()) return invalid("token")
        val serverIdHint = params.firstOrNull { it.first == "serverId" || it.first == "name" }
            ?.second?.trim()?.takeIf { it.isNotEmpty() }

        val addresses = params.filter { it.first == "server" }
            .flatMap { it.second.split(',') }
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }

        val seen = LinkedHashSet<String>()
        val candidates = ArrayList<PairingCandidate>()
        for (base in addresses) {
            if (!seen.add(base)) continue
            val scheme = urlScheme(base) ?: return invalid("address")
            candidates.add(PairingCandidate(base, scheme, IdentityDigest.of(base, token)))
        }
        if (candidates.isEmpty()) return invalid("no_address")
        return PairingParseResult.Proposal(PairingProposal(serverIdHint, token, candidates))
    }

    /**
     * 显式确认后才落盘：校验 chosenBase 属于候选；若 chosen 是 http 且存在同 host:port 的 https 候选，
     * 升级为该 https base 并持久化（G19）；写凭据经 [SecretWriter.write]。chosenBase 不在候选里 → 失败且不写。
     */
    suspend fun confirm(
        proposal: PairingProposal,
        chosenBase: String,
        secretWriter: SecretWriter,
    ): Result<PairedServer> {
        val chosen = proposal.candidates.firstOrNull { it.base == chosenBase.trim().trimEnd('/') }
            ?: return Result.failure(DomainError.Rejected("unknown_address"))

        val httpsPeer = proposal.candidates.firstOrNull {
            it.scheme == PairingScheme.Https && urlAuthority(it.base) == urlAuthority(chosen.base)
        }
        val persistedBase = if (chosen.scheme == PairingScheme.Http && httpsPeer != null) httpsPeer.base else chosen.base

        val digest = IdentityDigest.of(persistedBase, proposal.token)
        val identity = ConnectionIdentity(digest)
        secretWriter.write(identity, proposal.token)
        val serverId = proposal.serverIdHint?.let { ServerId(it) } ?: ServerId(digest.substring(0, SERVER_ID_PREFIX_LEN))
        return Result.success(
            PairedServer(
                serverId = serverId,
                identity = identity,
                storedBase = persistedBase,
                upgradedToHttps = persistedBase != chosen.base,
                addedAtMs = clock.wallClockMs(),
            ),
        )
    }

    private fun parseQuery(query: String): List<Pair<String, String>> =
        if (query.isEmpty()) emptyList()
        else query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) null
            else {
                val eq = pair.indexOf('=')
                if (eq < 0) pair to "" else pair.substring(0, eq) to pair.substring(eq + 1)
            }
        }

    private fun invalid(reason: String): PairingParseResult.Invalid =
        PairingParseResult.Invalid(DomainError.Rejected("pairing_qr:$reason"))
}
