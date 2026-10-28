package ai.deepseek.dsh.android

import android.content.Context
import ai.deepseek.dsh.android.data.ProtocolPendingContentDecoder
import ai.deepseek.dsh.android.data.RoomBackendRepository
import ai.deepseek.dsh.android.data.RoomDiagnosticsRepository
import ai.deepseek.dsh.android.data.RoomDiagnosticsSource
import ai.deepseek.dsh.android.data.RoomPendingRepository
import ai.deepseek.dsh.android.data.RoomPendingRoomDao
import ai.deepseek.dsh.android.data.RoomPendingStore
import ai.deepseek.dsh.android.data.RoomResyncSource
import ai.deepseek.dsh.android.data.RoomRosterSyncInfo
import ai.deepseek.dsh.android.data.RoomServerSource
import ai.deepseek.dsh.android.data.RoomSessionHeadSource
import ai.deepseek.dsh.android.data.RoomSessionRepository
import ai.deepseek.dsh.android.data.RoomSessionStore
import ai.deepseek.dsh.android.data.RoomTimelineEventSource
import ai.deepseek.dsh.android.data.RoomTimelineRepository
import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.domain.PendingContentDecoder
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.SystemClock
import ai.deepseek.dsh.android.domain.repository.BackendRepository
import ai.deepseek.dsh.android.domain.repository.DiagnosticsRepository
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.PendingRepository
import ai.deepseek.dsh.android.domain.repository.SessionRepository
import ai.deepseek.dsh.android.domain.repository.SyncReason
import ai.deepseek.dsh.android.domain.repository.SyncRequestPort
import ai.deepseek.dsh.android.domain.repository.TimelineRepository
import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.ServerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 进程级对象图。手写构造依赖，不用 DI 框架：SyncActor 的生命周期绑定 connection identity，
 * Worker 可能运行在独立进程，作用域必须由代码显式表达。
 *
 * M4 没有配对入口，因此 [_noServer] 之外的服务器图只在 `server(serverId)` 被显式请求时构造；
 * 未配对时读侧走空 `ServerId("")`：列表为空、计数与名册新鲜度都是“未知”，不会伪造 0 或 Fresh。
 */
class AppScope private constructor(private val context: Context) {

    val tuning: SyncTuning = SyncTuning.DEFAULT

    private val clock = SystemClock()

    private val database: DshDatabase = DshDatabase.builder(context.applicationContext).build()

    /** 特性层只拿这个端口解码 pending 内容，不接触 payload JSON（D32）。 */
    val decoder: PendingContentDecoder = ProtocolPendingContentDecoder()

    /**
     * 连接层尚未装配（M6）：认证态恒为未知、能力为空、探活明确报告能力缺失。
     * 这些都不是伪造的“健康”，设置页会按未知渲染。
     */
    val backend: BackendRepository = RoomBackendRepository(
        servers = RoomServerSource(database),
        authStates = { flowOf(AuthState.Unknown) },
        capabilityFlows = { flowOf(null) },
    )

    /** 后台同步属于 M6；当前如实报告“关闭”，而不是注册一个永远不会运行的任务。 */
    val syncRequestPort: SyncRequestPort = object : SyncRequestPort {
        override suspend fun requestImmediate(serverId: ServerId, reason: SyncReason): Result<Unit> =
            Result.failure(DomainError.UnavailableCapability("background_sync"))

        override suspend fun setForeground(active: Boolean): Result<Unit> =
            Result.failure(DomainError.UnavailableCapability("background_sync"))

        override fun isBackgroundSyncEnabled(): Flow<Boolean> = flowOf(false)
    }

    /** 单个服务器作用域的仓储图；所有表都以 `(serverId, sessionId)` 为键（D07）。 */
    fun server(serverId: ServerId): ServerGraph = ServerGraph(serverId)

    inner class ServerGraph(val serverId: ServerId) {
        private val sessionsStore = RoomSessionStore(database, clock, serverId)

        private val heads = RoomSessionHeadSource(database, serverId)

        private val pendingStore = RoomPendingStore(database)

        val sessions: SessionRepository = RoomSessionRepository(
            serverId = serverId,
            store = sessionsStore,
            heads = heads,
            syncInfo = RoomRosterSyncInfo(database, serverId),
            tuning = tuning,
            clock = clock,
            keysetPage = sessionsStore::rosterKeysetPage,
        )

        val timeline: TimelineRepository = RoomTimelineRepository(
            serverId = serverId,
            events = RoomTimelineEventSource(database, serverId),
            heads = heads,
            resync = RoomResyncSource(database, serverId),
            pending = pendingStore,
        )

        val pending: PendingRepository = RoomPendingRepository(
            serverId = serverId,
            store = pendingStore,
            dao = RoomPendingRoomDao(database, serverId),
            resolveUseCase = null,
            tuning = tuning,
            clock = clock,
        )

        val diagnostics: DiagnosticsRepository = RoomDiagnosticsRepository(
            source = RoomDiagnosticsSource(database, serverId, clock),
            pending = pending,
            authState = flowOf(AuthState.Unknown),
            capabilities = flowOf(null),
        )
    }

    companion object {
        fun create(context: Context): AppScope = AppScope(context.applicationContext)
    }
}
