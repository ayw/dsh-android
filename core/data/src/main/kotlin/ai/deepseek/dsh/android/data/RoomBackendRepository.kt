package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.domain.repository.BackendRepository
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.ServerDescriptor
import ai.deepseek.dsh.android.domain.repository.domainCall
import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.BackendHealth
import ai.deepseek.dsh.android.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 服务器行的可外泄字段。刻意只有 id/标签/base URL 三个值：凭据、身份摘要、密文一律不进这个类型，
 * 这样 [ServerDescriptor] 的“无秘密”约束在类型层面就成立。
 */
data class ServerRecord(
    val serverId: ServerId,
    val label: String,
    val storedBase: String,
)

interface ServerSource {
    fun observeServers(): Flow<List<ServerRecord>>
}

/**
 * [BackendRepository] 的 Room 实现。
 *
 * 认证态与能力是连接层的瞬时事实，不能从 `server` 表推出来，因此由装配点注入按 server 的 Flow；
 * 本类只负责把它们与持久化的服务器行拼成 [ServerDescriptor]。`probeNow` 同样只委派给注入的探活函数，
 * 没有注入就返回 UnavailableCapability —— 不伪造一次成功的健康检查。
 */
class RoomBackendRepository(
    private val servers: ServerSource,
    private val authStates: (ServerId) -> Flow<AuthState>,
    private val capabilityFlows: (ServerId) -> Flow<BackendCapabilities?>,
    private val probe: (suspend (ServerId) -> Result<BackendHealth?>)? = null,
) : BackendRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun servers(): Flow<List<ServerDescriptor>> =
        servers.observeServers().flatMapLatest { records ->
            if (records.isEmpty()) {
                flowOf(emptyList())
            } else {
                val perServer = records.map { record ->
                    combine(
                        authStates(record.serverId),
                        capabilityFlows(record.serverId),
                    ) { auth, capabilities ->
                        ServerDescriptor(
                            serverId = record.serverId,
                            label = record.label,
                            storedBase = record.storedBase,
                            lastKnownAuthState = auth,
                            capabilities = capabilities,
                        )
                    }
                }
                combine(perServer) { descriptors -> descriptors.toList() }
            }
        }

    override fun authState(serverId: ServerId): Flow<AuthState> = authStates(serverId)

    override fun capabilities(serverId: ServerId): Flow<BackendCapabilities?> = capabilityFlows(serverId)

    override suspend fun probeNow(serverId: ServerId): Result<BackendHealth?> = domainCall {
        val call = probe ?: throw DomainError.UnavailableCapability("health_probe")
        // 注入方已经返回 Result；成功只代表“拿到/没拿到健康快照”，不代表后端一定健康。
        call(serverId).getOrThrow()
    }
}

/** [ServerSource] 的 Room 实现：只映射安全字段，不读 `server_credential`。 */
class RoomServerSource(
    db: DshDatabase,
) : ServerSource {

    private val servers = db.serverDao()

    override fun observeServers(): Flow<List<ServerRecord>> =
        servers.observeServers().map { rows ->
            rows.map { row ->
                ServerRecord(
                    serverId = row.serverId,
                    label = row.label,
                    storedBase = row.baseUrl,
                )
            }
        }
}
