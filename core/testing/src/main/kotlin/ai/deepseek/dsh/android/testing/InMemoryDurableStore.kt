package ai.deepseek.dsh.android.testing

import ai.deepseek.dsh.android.domain.DurableStore
import ai.deepseek.dsh.android.domain.PendingStore
import ai.deepseek.dsh.android.domain.SessionStore
import ai.deepseek.dsh.android.domain.SyncOwnerRecord
import ai.deepseek.dsh.android.domain.SyncStateStore
import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.NeedsResync
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingRequest
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SessionPosition
import ai.deepseek.dsh.android.model.SyncOwnerRole
import ai.deepseek.dsh.android.model.TransportCursor
import ai.deepseek.dsh.android.model.TransportFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** pending 主键与 DECISIONS §6 一致：(serverId, kind, requestId)，sessionId 只是属性。 */
data class PendingRowKey(
    val serverId: ServerId,
    val kind: PendingKind,
    val requestId: RequestId,
)

/**
 * Room 缺席时的 JVM 替身：同时实现四个端口，并把 `commit` 当作真事务对待——
 * 要么整批生效，要么完全不生效。`failNextCommit` 模拟“事务中途抛异常”，
 * 用来钉死“batch 失败时 transport cursor 不前进”这条不变量。
 *
 * `pendingChanges` 是整行替换：状态单调性由 PendingReducer 负责，不由存储层负责。
 */
class InMemoryDurableStore : DurableStore, SyncStateStore, SessionStore, PendingStore {

    private val lock = Any()

    private val frameLog = mutableListOf<TransportFrame>()
    private val upsertLog = mutableListOf<DurableStore.ProjectionUpsert>()
    private val outboxLog = mutableListOf<DurableStore.OutboxEntry>()
    private val cursorMap = mutableMapOf<Pair<String, String>, TransportCursor>()
    private val positionMap = mutableMapOf<Pair<ServerId, SessionId>, SessionPosition>()
    private val islandMap = mutableMapOf<SessionId, MutableList<AppliedIsland>>()
    private val resyncMap = mutableMapOf<SessionId, NeedsResync>()
    private val pendingMap = mutableMapOf<PendingRowKey, PendingRequest>()
    private val retiredMap = mutableMapOf<ConnectionIdentity, Long>()
    private val scopeFailureMap = mutableMapOf<String, Int>()

    private val rosterFlow = MutableStateFlow<List<RosterEntry>>(emptyList())
    private val pendingFlow = MutableStateFlow<Map<PendingRowKey, PendingRequest>>(emptyMap())

    /** 每次 commit 记录的写入段顺序，用于断言 cursor 永远写在最后。 */
    val writeOrder = mutableListOf<List<String>>()
    val commits = mutableListOf<DurableStore.ApplyBatch>()

    /** 设置后下一次 `commit` 在任何写入之前抛出，模拟事务失败。 */
    var failNextCommit: Throwable? = null

    var ownerRecord = SyncOwnerRecord(SyncOwnerRole.None, null, 0L)
        private set
    var foregroundRequestedAtMs: Long? = null
        private set

    // ---- DurableStore ----

    override suspend fun commit(batch: DurableStore.ApplyBatch) = synchronized(lock) {
        failNextCommit?.let {
            failNextCommit = null
            throw it
        }
        val order = mutableListOf<String>()
        if (batch.frames.isNotEmpty()) {
            frameLog += batch.frames
            order += "frames"
        }
        if (batch.upserts.isNotEmpty()) {
            upsertLog += batch.upserts
            order += "upserts"
        }
        if (batch.pendingChanges.isNotEmpty()) {
            batch.pendingChanges.forEach { pendingMap[keyOf(it)] = it }
            pendingFlow.value = pendingMap.toMap()
            order += "pending"
        }
        if (batch.outboxEntries.isNotEmpty()) {
            outboxLog += batch.outboxEntries
            order += "outbox"
        }
        if (batch.positionsAfter.isNotEmpty()) {
            batch.positionsAfter.forEach { (sessionId, position) ->
                positionMap[batch.serverId to sessionId] = position
            }
            order += "positions"
        }
        if (batch.islands.isNotEmpty()) {
            batch.islands.forEach { (sessionId, spans) -> islandMap[sessionId] = spans.toMutableList() }
            order += "islands"
        }
        batch.needsResync.forEach { (sessionId, value) -> resyncMap[sessionId] = value }
        batch.baselineSweep?.let { sweep ->
            val doomed = pendingMap.keys
                .filter { it.serverId == batch.serverId }
                .filter { key ->
                    val request = pendingMap.getValue(key)
                    request.sessionId == sweep.sessionId &&
                        DurableStore.PendingKey(key.kind, key.requestId) !in sweep.presentKeys
                }
            doomed.forEach { pendingMap -= it }
            pendingFlow.value = pendingMap.toMap()
            order += "sweep"
        }
        if (batch.tombstones.isNotEmpty()) {
            batch.tombstones.forEach { key ->
                pendingMap -= PendingRowKey(batch.serverId, key.kind, key.requestId)
            }
            pendingFlow.value = pendingMap.toMap()
            order += "tombstones"
        }
        batch.cursorAfter?.let { cursorMap[keyOf(it)] = it }
        if (batch.cursorAfter != null) order += "cursor"
        writeOrder += order
        commits += batch
        Unit
    }

    // ---- SyncStateStore ----

    override suspend fun cursor(identity: ConnectionIdentity, channel: ChannelKind): TransportCursor? =
        synchronized(lock) { cursorMap[identity.digest to channel.value] }

    override suspend fun putCursor(cursor: TransportCursor) = synchronized(lock) {
        cursorMap[keyOf(cursor)] = cursor
        Unit
    }

    override suspend fun clearTransportCursors(identity: ConnectionIdentity) = synchronized(lock) {
        cursorMap.keys.filter { it.first == identity.digest }.forEach { cursorMap -= it }
        Unit
    }

    override suspend fun owner(): SyncOwnerRecord = synchronized(lock) { ownerRecord }

    override suspend fun casOwner(
        expected: SyncOwnerRole,
        next: SyncOwnerRole,
        holderId: String,
        nowMs: Long,
    ): Boolean = synchronized(lock) {
        if (ownerRecord.role != expected) {
            false
        } else {
            ownerRecord = SyncOwnerRecord(next, holderId, nowMs)
            true
        }
    }

    override suspend fun setForegroundRequestedAt(nowMs: Long?) = synchronized(lock) {
        foregroundRequestedAtMs = nowMs
        Unit
    }

    override suspend fun markIdentityRetired(identity: ConnectionIdentity, retiredAtMs: Long) =
        synchronized(lock) {
            retiredMap[identity] = retiredAtMs
            Unit
        }

    override suspend fun purgeEligibleIdentities(
        nowMs: Long,
        graceMs: Long,
    ): List<ConnectionIdentity> = synchronized(lock) {
        val eligible = retiredMap.filter { nowMs - it.value >= graceMs }.keys.toList()
        eligible.forEach { identity ->
            cursorMap.keys.filter { it.first == identity.digest }.forEach { cursorMap -= it }
            outboxLog.removeAll { it.tag.value.startsWith("${identity.digest}:") }
            scopeFailureMap.remove(identity.digest)
            retiredMap.remove(identity)
        }
        eligible
    }

    // ---- SessionStore ----

    override fun rosterPage(limit: Int, offsetRank: Long): Flow<List<RosterEntry>> =
        rosterFlow.map { entries ->
            entries.filter { it.rosterRank < offsetRank }
                .sortedByDescending { it.rosterRank }
                .take(limit)
        }

    override suspend fun replaceRoster(
        serverId: ServerId,
        entries: List<RosterEntry>,
        syncedAtMs: Long,
    ) = synchronized(lock) {
        rosterFlow.value = entries.toList()
        Unit
    }

    override suspend fun tombstoneAbsent(serverId: ServerId, absentSessionIds: Set<SessionId>): Int =
        synchronized(lock) {
            val before = rosterFlow.value
            val kept = before.filter { it.sessionId !in absentSessionIds }
            rosterFlow.value = kept
            before.size - kept.size
        }

    override suspend fun position(serverId: ServerId, sessionId: SessionId): SessionPosition? =
        synchronized(lock) { positionMap[serverId to sessionId] }

    override suspend fun applyPosition(serverId: ServerId, sessionId: SessionId, position: SessionPosition) =
        synchronized(lock) {
            positionMap[serverId to sessionId] = position
            Unit
        }

    override fun observe(serverId: ServerId, sessionId: SessionId): Flow<RosterEntry?> =
        rosterFlow.map { entries -> entries.firstOrNull { it.sessionId == sessionId } }

    // ---- PendingStore ----

    override fun observePending(serverId: ServerId): Flow<List<PendingRequest>> =
        pendingFlow.map { rows ->
            rows.values.filter { it.serverId == serverId }.sortedWith(
                compareBy({ it.firstSeenAtMs }, { it.requestId.value }),
            )
        }

    override fun observeForSession(serverId: ServerId, sessionId: SessionId): Flow<List<PendingRequest>> =
        observePending(serverId).map { rows -> rows.filter { it.sessionId == sessionId } }

    override suspend fun upsert(request: PendingRequest) = synchronized(lock) {
        pendingMap[keyOf(request)] = request
        pendingFlow.value = pendingMap.toMap()
        Unit
    }

    override suspend fun dismiss(
        serverId: ServerId,
        kind: PendingKind,
        requestId: RequestId,
        nowMs: Long,
    ) = synchronized(lock) {
        pendingMap[keyOf(serverId, kind, requestId)]?.let { row ->
            pendingMap[keyOf(serverId, kind, requestId)] = row.copy(dismissedAtMs = nowMs)
            pendingFlow.value = pendingMap.toMap()
        }
        Unit
    }

    override suspend fun tombstone(
        serverId: ServerId,
        kind: PendingKind,
        requestId: RequestId,
        nowMs: Long,
    ) = synchronized(lock) {
        pendingMap -= keyOf(serverId, kind, requestId)
        pendingFlow.value = pendingMap.toMap()
        Unit
    }

    override suspend fun sweepAbsent(
        serverId: ServerId,
        presentKeys: Set<DurableStore.PendingKey>,
        nowMs: Long,
    ): Int = synchronized(lock) {
        val doomed = pendingMap.keys
            .filter { it.serverId == serverId }
            .filter { DurableStore.PendingKey(it.kind, it.requestId) !in presentKeys }
        doomed.forEach { pendingMap -= it }
        pendingFlow.value = pendingMap.toMap()
        doomed.size
    }

    // ---- 测试侧读取面 ----

    fun frames(): List<TransportFrame> = synchronized(lock) { frameLog.toList() }

    fun upserts(): List<DurableStore.ProjectionUpsert> = synchronized(lock) { upsertLog.toList() }

    fun outbox(): List<DurableStore.OutboxEntry> = synchronized(lock) { outboxLog.toList() }

    fun pendingRows(): List<PendingRequest> = synchronized(lock) { pendingMap.values.toList() }

    fun islands(sessionId: SessionId): List<AppliedIsland> =
        synchronized(lock) { islandMap[sessionId]?.toList() ?: emptyList() }

    fun needsResync(sessionId: SessionId): NeedsResync? = synchronized(lock) { resyncMap[sessionId] }

    fun rosterSnapshot(): List<RosterEntry> = rosterFlow.value

    fun cursorCount(): Int = synchronized(lock) { cursorMap.size }

    fun retiredIdentities(): Set<ConnectionIdentity> = synchronized(lock) { retiredMap.keys.toSet() }

    fun scopeFailure(identity: ConnectionIdentity): Int? = synchronized(lock) {
        scopeFailureMap[identity.digest]
    }

    fun recordScopeFailure(identity: ConnectionIdentity, count: Int) = synchronized(lock) {
        scopeFailureMap[identity.digest] = count
        Unit
    }

    private fun keyOf(request: PendingRequest) =
        PendingRowKey(request.serverId, request.kind, request.requestId)

    private fun keyOf(serverId: ServerId, kind: PendingKind, requestId: RequestId) =
        PendingRowKey(serverId, kind, requestId)

    private fun keyOf(cursor: TransportCursor) = cursor.identity.digest to cursor.channel.value
}
