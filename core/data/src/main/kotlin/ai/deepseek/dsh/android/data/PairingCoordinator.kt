package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.CredentialDao
import ai.deepseek.dsh.android.database.ServerCredentialEntity
import ai.deepseek.dsh.android.database.ServerDao
import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.toDomainError
import ai.deepseek.dsh.android.domain.usecase.PairedServer
import ai.deepseek.dsh.android.domain.usecase.PairingParseResult
import ai.deepseek.dsh.android.domain.usecase.PairingProposal
import ai.deepseek.dsh.android.domain.usecase.PairingUseCase
import ai.deepseek.dsh.android.domain.usecase.SecretWriter
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.ServerId
import kotlinx.coroutines.CancellationException

/** 已配对服务器的落库端口。实现由装配点提供（Room 事务/诊断副作用属于它，本类不碰 Room 写侧策略）。 */
fun interface ServerPersister {
    suspend fun persist(server: PairedServer)
}

/**
 * 配对编排（G19）：解析只产出内存提案，**确认是唯一写点**。
 *
 * 写序刻意是“服务器行先、凭据后”：
 *  1. `PairingUseCase.confirm` 先在一个只捕获不落盘的 [SecretWriter] 上跑，借它算出
 *     `PairedServer`（含 https 升级后的 base 与 identity）而不产生任何持久化副作用；
 *  2. 再让 [ServerPersister] 写入服务器行；
 *  3. 最后才经 [SecretWriter] 加密并写入凭据。
 * 这样“没有凭据的服务器行”只是一个惰性残骸（不能建连、下次配对会覆盖），
 * 而反向顺序会留下“没有服务器行的凭据”——那是一条无法被 UI 枚举、也无人清理的秘密。
 * 代价是两步之间崩溃会留下残骸；这正是允许“凭据最后”的条件，要求实现方按惰性行对待。
 *
 * 时间只来自 [PairingUseCase] 注入的 [Clock]；本类自己不读系统时钟、不写库。
 */
class PairingCoordinator(
    private val pairing: PairingUseCase,
    private val secretWriter: SecretWriter,
    private val serverPersister: ServerPersister,
) {

    /** 纯内存解析；多地址去重与非法地址判定全部交给领域用例（唯一权威）。 */
    fun parse(qr: String): PairingParseResult = pairing.parse(qr)

    /**
     * 显式确认。`chosenBase` 必须在提案候选里，否则失败且零写入。
     * 服务器行写入成功、凭据写入失败时返回失败：此时该服务器行不可用，重新配对会覆盖它。
     */
    suspend fun confirm(proposal: PairingProposal, chosenBase: String): Result<PairedServer> {
        val capture = CapturingSecretWriter()
        val confirmation = pairing.confirm(proposal, chosenBase, capture)
        if (confirmation.isFailure) {
            return Result.failure(confirmation.exceptionOrNull() ?: DomainError.Rejected("pairing_rejected"))
        }
        val paired = confirmation.getOrThrow()
        val captured = capture.captured() ?: return Result.failure(DomainError.Rejected("pairing_no_secret"))
        return try {
            serverPersister.persist(paired)
            secretWriter.write(captured.identity, captured.token)
            Result.success(paired)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // toDomainError 的兜底消息是固定串，不会把 token/URL 带进 UI 或日志。
            Result.failure(failure.toDomainError())
        }
    }
}

/**
 * `SecretWriter` 的 Room 实现：`SecretStore` 负责密码学，本类只把 blob 搬进两列。
 * `server_credential` 不存明文（G09.4 的反射检查依赖这一点）。
 *
 * serverId 先按 identity 查已落库的 server 行（确认流程里服务器行先写），
 * 查不到才退回 digest 前缀——与 `PairingUseCase` 的兜底规则一致；
 * 这样带 `serverId` 提示的 QR 也不会让两行指向不同 server。
 */
class RoomSecretWriter(
    private val secretStore: SecretStore,
    private val credentials: CredentialDao,
    private val clock: Clock,
    private val servers: ServerDao? = null,
) : SecretWriter {

    override suspend fun write(identity: ConnectionIdentity, token: String) {
        val blob = secretStore.encrypt(identity, token)
        val now = clock.wallClockMs()
        val serverId = servers?.serverByIdentity(identity.digest)?.serverId
            ?: ServerId(identity.digest.take(SERVER_ID_PREFIX_CHARS))
        credentials.putCredential(
            ServerCredentialEntity(
                identityHash = identity.digest,
                serverId = serverId,
                iv = SecretStore.ivOf(blob),
                ciphertext = blob,
                createdAtMs = now,
                updatedAtMs = now,
            ),
        )
    }

    private companion object {
        const val SERVER_ID_PREFIX_CHARS = 16
    }
}

/** 只捕获一次写入、不接触存储；仅用于在确认前取得 identity 而不落盘。 */
private class CapturingSecretWriter : SecretWriter {
    private var identity: ConnectionIdentity? = null
    private var token: String? = null

    override suspend fun write(identity: ConnectionIdentity, token: String) {
        this.identity = identity
        this.token = token
    }

    fun captured(): CapturedCredential? {
        val id = identity ?: return null
        val secret = token ?: return null
        return CapturedCredential(id, secret)
    }
}

/** 刻意不是 data class：默认的 toString 不能把 token 打出来。 */
private class CapturedCredential(val identity: ConnectionIdentity, val token: String) {
    override fun toString(): String = "CapturedCredential(identity=${identity.digest}, token=[redacted])"
}
