package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.database.DshDatabase
import ai.deepseek.dsh.android.domain.Clock
import ai.deepseek.dsh.android.domain.SessionStore
import ai.deepseek.dsh.android.domain.SyncTuning
import ai.deepseek.dsh.android.domain.SystemClock
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.RosterSyncState
import ai.deepseek.dsh.android.domain.repository.SessionKeyset
import ai.deepseek.dsh.android.domain.repository.SessionPage
import ai.deepseek.dsh.android.domain.repository.SessionRepository
import ai.deepseek.dsh.android.domain.repository.SessionSummary
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 会话头部的只读快照。只携带领域需要的四列，避免把 Room 实体泄漏给时间线/诊断的测试。
 * [historyUnsafe] 与 [historyIncomplete] 是两条不同的红线：前者不可重建、后者有洞但仍可重建（D08）。
 */
data class SessionHeadSnapshot(
    val contiguousSeq: EventSeq?,
    val appliedMaxSeq: EventSeq?,
    val historyUnsafe: Boolean,
    val historyIncomplete: Boolean,
    val prunedBelowSeq: EventSeq?,
) {
    fun toPosition(): SessionPosition = SessionPosition(contiguousSeq = contiguousSeq, appliedMaxSeq = appliedMaxSeq)
}

/** 按会话读取 head 的窄接口；生产实现直接包 [DshDatabase]，测试用内存 Flow 即可，无需 Robolectric。 */
interface SessionHeadSource {
    fun observeHead(sessionId: SessionId): Flow<SessionHeadSnapshot?>
}

/** 名册同步水位。`null` 表示“从未同步”，不是 0：0 会被误读成“刚同步过”。 */
interface RosterSyncInfo {
    fun lastSyncedAtMs(): Flow<Long?>
}

/**
 * [SessionRepository] 的 Room 实现。
 *
 * 分页游标的字段名是契约给定的 `(updatedAtMs, sessionId)`，但实现把 `rosterRank` 放进 `updatedAtMs` 槽位：
 * `updatedAt` 会被后台同步改写，不能当稳定游标（D19/G12），而契约没有第三个槽位。`sessionId` 用于
 * 同一 rank 内的破平，只在调用方提供了完整 keyset 读取器时才生效（见 [keysetPage]）。
 *
 * [rosterSyncState] 只从数据库里可观察的“最近同步时间”推导：没有同步水位 ⇒ Unknown；由
 * [SyncTuning.rosterForegroundStaleMs] 判定 Fresh/Stale。Syncing/Failed 是 actor 的运行态，
 * 本仓储没有对应数据源，绝不伪造这两个状态。
 */
class RoomSessionRepository(
    private val serverId: ServerId,
    private val store: SessionStore,
    private val heads: SessionHeadSource,
    private val syncInfo: RosterSyncInfo,
    private val tuning: SyncTuning = SyncTuning.DEFAULT,
    private val clock: Clock = SystemClock(),
    /**
     * 完整 `(rosterRank, sessionId)` keyset 读取器（通常是 `RoomSessionStore::rosterKeysetPage`）。
     * 端口 [SessionStore.rosterPage] 只有 rank，同一 rank 的多行会在续页被跳过；提供本参数即可避免。
     */
    private val keysetPage: ((rank: Long, sessionId: String, limit: Int) -> Flow<List<RosterEntry>>)? = null,
    /**
     * 名册刷新回调，由装配点接 `SyncRequestPort` / `RemoteSource.roster()`。为 null 时 [refreshRoster]
     * 返回 UnavailableCapability，而不是假装成功。
     */
    private val requestRefresh: (suspend (force: Boolean) -> Result<Unit>)? = null,
) : SessionRepository {

    override fun paged(startAfter: SessionKeyset?, pageSize: Int): Flow<SessionPage> {
        val limit = pageSize.coerceAtLeast(1)
        val page = if (startAfter == null) {
            keysetPage?.invoke(Long.MAX_VALUE, "", limit) ?: store.rosterPage(limit, Long.MAX_VALUE)
        } else {
            keysetPage?.invoke(startAfter.updatedAtMs, startAfter.sessionId.value, limit)
                ?: store.rosterPage(limit, startAfter.updatedAtMs)
        }
        return page.flatMapLatest { rows -> toPage(rows, limit) }
    }

    override fun byId(sessionId: SessionId): Flow<SessionSummary?> =
        combine(store.observe(serverId, sessionId), heads.observeHead(sessionId)) { entry, head ->
            entry?.toSummary(head?.historyUnsafe == true)
        }

    override fun title(sessionId: SessionId): Flow<String?> =
        store.observe(serverId, sessionId).map { it?.title }

    override fun running(sessionId: SessionId): Flow<Boolean> =
        store.observe(serverId, sessionId).map { it?.running == true }

    override fun archived(sessionId: SessionId): Flow<Boolean> =
        store.observe(serverId, sessionId).map { it?.archived == true }

    override fun historyUnsafe(sessionId: SessionId): Flow<Boolean> =
        heads.observeHead(sessionId).map { it?.historyUnsafe == true }

    override fun rosterSyncState(): Flow<RosterSyncState> =
        syncInfo.lastSyncedAtMs().map { lastSyncedAtMs ->
            if (lastSyncedAtMs == null || lastSyncedAtMs <= 0L) {
                RosterSyncState.Unknown
            } else {
                // 只在前台阈值内算 Fresh：本仓储不知道当前 owner，保守选更短的时限。
                val ageMs = (clock.wallClockMs() - lastSyncedAtMs).coerceAtLeast(0L)
                if (ageMs <= tuning.rosterForegroundStaleMs) RosterSyncState.Fresh else RosterSyncState.Stale
            }
        }

    override suspend fun refreshRoster(force: Boolean): Result<Unit> {
        val refresh = requestRefresh
            ?: return Result.failure(DomainError.UnavailableCapability("roster_refresh"))
        return refresh(force)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun toPage(rows: List<RosterEntry>, limit: Int): Flow<SessionPage> {
        if (rows.isEmpty()) return flowOf(SessionPage(items = emptyList(), nextKey = null))
        // 每条名册行都要读 head 的 historyUnsafe；页大小固定（默认 30），逐行 Flow 合并比全表扫描便宜。
        val flagged = rows.map { row ->
            heads.observeHead(row.sessionId).map { head -> row to (head?.historyUnsafe == true) }
        }
        return combine(flagged) { flags ->
            val items = rows.mapIndexed { index, row -> row.toSummary(flags[index].second) }
            val last = rows.last()
            // 只有满页才可能有下一页；半页就是到顶。
            val nextKey = if (rows.size == limit) SessionKeyset(last.rosterRank, last.sessionId) else null
            SessionPage(items = items, nextKey = nextKey)
        }
    }

    private fun RosterEntry.toSummary(historyUnsafe: Boolean): SessionSummary = SessionSummary(
        sessionId = sessionId,
        title = title,
        updatedAtMs = updatedAtMs,
        running = running,
        archived = archived,
        historyUnsafe = historyUnsafe,
        workspaceId = workspaceId,
    )
}

/** [SessionHeadSource] 的 Room 实现：只读 `session_head`，不做任何状态机判断。 */
class RoomSessionHeadSource(
    db: DshDatabase,
    serverId: ServerId,
) : SessionHeadSource {

    private val heads = db.sessionHeadDao()
    private val serverIdValue = serverId.value

    override fun observeHead(sessionId: SessionId): Flow<SessionHeadSnapshot?> =
        heads.observeHead(serverIdValue, sessionId.value).map { row ->
            row?.let {
                SessionHeadSnapshot(
                    contiguousSeq = it.contiguousSeq,
                    appliedMaxSeq = it.appliedMaxSeq,
                    historyUnsafe = it.historyUnsafe,
                    historyIncomplete = it.historyIncomplete,
                    prunedBelowSeq = it.prunedBelowSeq,
                )
            }
        }
}

/** [RosterSyncInfo] 的 Room 实现：`server.lastSeenAtMs` 是同步成功时写下的水位（D11）。 */
class RoomRosterSyncInfo(
    db: DshDatabase,
    serverId: ServerId,
) : RosterSyncInfo {

    private val servers = db.serverDao()
    private val serverIdValue = serverId.value

    override fun lastSyncedAtMs(): Flow<Long?> =
        servers.observeServer(serverIdValue).map { row -> row?.lastSeenAtMs?.takeIf { it > 0L } }
}
