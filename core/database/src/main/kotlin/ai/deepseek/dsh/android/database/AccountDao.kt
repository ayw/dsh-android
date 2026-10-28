package ai.deepseek.dsh.android.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * `server`：已配对网关与其当前连接身份。
 *
 * `identityHash` 是本模块唯一的“当前身份”指针：退役清理的 A→B→A 保护就是查它（见 [IdentityDao]）。
 * `baseUrl` 逐字节保存（digest 依赖它：不去尾斜杠、不小写 host、不省略默认端口、不归一路径）。
 */
@Dao
interface ServerDao {

    @Upsert
    suspend fun putServers(servers: List<ServerEntity>)

    @Query("SELECT * FROM server ORDER BY addedAtMs ASC")
    fun observeServers(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM server WHERE serverId = :serverId")
    fun observeServer(serverId: String): Flow<ServerEntity?>

    @Query("SELECT * FROM server WHERE serverId = :serverId")
    suspend fun server(serverId: String): ServerEntity?

    @Query("SELECT * FROM server WHERE identityHash = :identityHash LIMIT 1")
    suspend fun serverByIdentity(identityHash: String): ServerEntity?

    /** 重新配对 / 轮换（D14）：新摘要写在这里，旧摘要由调用方登记进 [IdentityDao]。 */
    @Query("UPDATE server SET identityHash = :identityHash, baseUrl = :baseUrl, lastSeenAtMs = :nowMs WHERE serverId = :serverId")
    suspend fun rotateIdentity(serverId: String, identityHash: String, baseUrl: String, nowMs: Long): Int

    /** 名册/同步成功的时间戳（D11 的 `rosterAgeMs` 由 `nowMs - lastSeenAtMs` 得到）。 */
    @Query("UPDATE server SET lastSeenAtMs = :nowMs WHERE serverId = :serverId")
    suspend fun markSyncedAt(serverId: String, nowMs: Long): Int

    @Query("DELETE FROM server WHERE serverId = :serverId")
    suspend fun deleteServer(serverId: String): Int

    @Query("SELECT COUNT(*) AS total FROM server")
    suspend fun serverCount(): CountRow
}

/**
 * `server_credential`：只有 IV 与密文两列（G09.4 的反射检查依赖“没有明文列”这一点）。
 * 加解密在 `:core:data` 的 SecretStore（AndroidKeyStore AES-256-GCM），本模块不接触明文。
 */
@Dao
interface CredentialDao {

    @Upsert
    suspend fun putCredentials(credentials: List<ServerCredentialEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCredential(credential: ServerCredentialEntity): Long

    @Query("SELECT * FROM server_credential WHERE identityHash = :identityHash")
    suspend fun credential(identityHash: String): ServerCredentialEntity?

    @Query("SELECT * FROM server_credential WHERE serverId = :serverId ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun latestCredentialForServer(serverId: String): ServerCredentialEntity?

    @Query("SELECT * FROM server_credential WHERE serverId = :serverId ORDER BY createdAtMs DESC LIMIT 1")
    fun observeLatestCredentialForServer(serverId: String): Flow<ServerCredentialEntity?>

    @Query("SELECT * FROM server_credential ORDER BY createdAtMs ASC")
    fun observeCredentials(): Flow<List<ServerCredentialEntity>>

    @Query("DELETE FROM server_credential WHERE identityHash = :identityHash")
    suspend fun deleteCredential(identityHash: String): Int

    @Query("DELETE FROM server_credential WHERE serverId = :serverId")
    suspend fun deleteCredentialsForServer(serverId: String): Int

    @Query("SELECT COUNT(*) AS total FROM server_credential")
    suspend fun credentialCount(): CountRow
}
