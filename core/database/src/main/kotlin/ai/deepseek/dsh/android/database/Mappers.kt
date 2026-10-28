package ai.deepseek.dsh.android.database

import ai.deepseek.dsh.android.model.AppliedIsland
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.ConnectionIdentity
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.NeedsResync
import ai.deepseek.dsh.android.model.NotificationTag
import ai.deepseek.dsh.android.model.OutboxState
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

/**
 * 模型 ↔ 实体映射。放在本模块是因为 `:core:database` 只依赖 `:core:model`：适配器（`:core:data`）
 * 因此不必手写列顺序，也不会在这里塞进策略。
 *
 * 明确不做两件事：不实现 `:core:domain` 的 `*Store` 接口（依赖方向禁止）；不解析 `payloadJson`
 * （D06 原样存取，字节可比性由 G15 校验）。
 */

private val DIGEST_REGEX = Regex("^[0-9a-f]{64}$")

/**
 * 原始帧入库。`replay` 是传输层元数据（F10），**不进 durable 模型**，所以没有对应列；
 * `wallClockMs` 与 `receivedAtMs` 各存一份：前者参与投影与显示，后者只服务保留策略与诊断。
 */
fun TransportFrame.toEntity(serverId: ServerId, receivedAtMs: Long): SessionEventEntity = SessionEventEntity(
    serverId = serverId,
    sessionId = sessionId,
    seq = seq,
    type = type,
    ignorable = ignorable,
    payloadJson = payloadJson,
    origin = channel.value,
    wallClockMs = wallClockMs,
    receivedAtMs = receivedAtMs,
)

/** 从库里重放出来的帧不是网络重放：`replay` 固定 false（F10）。 */
fun SessionEventEntity.toFrame(): TransportFrame = TransportFrame(
    channel = ChannelKind(origin),
    sessionId = sessionId,
    seq = seq,
    type = type,
    ignorable = ignorable,
    payloadJson = payloadJson,
    wallClockMs = wallClockMs,
    replay = false,
)

fun RosterEntry.toEntity(serverId: ServerId): SessionEntity = SessionEntity(
    serverId = serverId,
    sessionId = sessionId,
    title = title,
    updatedAtMs = updatedAtMs,
    running = running,
    archived = archived,
    workspaceId = workspaceId,
    rosterRank = rosterRank,
    lastSeenRank = rosterRank,
    tombstonedAtMs = null,
)

fun SessionEntity.toRosterEntry(): RosterEntry = RosterEntry(
    sessionId = sessionId,
    title = title,
    updatedAtMs = updatedAtMs,
    running = running,
    archived = archived,
    workspaceId = workspaceId,
    rosterRank = rosterRank,
)

/**
 * 位置写进 head 行。安全标记与裁剪界单独传入：它们由 [SessionHeadDao.markUnsafe] /
 * [SessionHeadDao.notePrunedFloor] 维护，不能因为一次普通位置写入被顺手抹掉（[SessionHeadDao.upsertSessionHeads]
 * 的 SQL 里 `historyUnsafe` 也不在 SET 列表）。
 */
fun SessionPosition.toHeadEntity(
    serverId: ServerId,
    sessionId: SessionId,
    lastAppliedAtMs: Long,
    historyUnsafe: Boolean = false,
    historyIncomplete: Boolean = false,
    prunedBelowSeq: EventSeq? = null,
): SessionHeadEntity = SessionHeadEntity(
    serverId = serverId,
    sessionId = sessionId,
    contiguousSeq = contiguousSeq,
    appliedMaxSeq = appliedMaxSeq,
    historyUnsafe = historyUnsafe,
    historyIncomplete = historyIncomplete,
    prunedBelowSeq = prunedBelowSeq,
    lastAppliedAtMs = lastAppliedAtMs,
)

fun SessionHeadEntity.toPosition(): SessionPosition = SessionPosition(contiguousSeq = contiguousSeq, appliedMaxSeq = appliedMaxSeq)

fun AppliedIsland.toEntity(serverId: ServerId, sessionId: SessionId): AppliedIslandEntity = AppliedIslandEntity(
    serverId = serverId,
    sessionId = sessionId,
    startSeq = startSeq,
    endSeq = endSeq,
)

fun AppliedIslandEntity.toModel(): AppliedIsland = AppliedIsland(startSeq = startSeq, endSeq = endSeq)

fun List<AppliedIsland>.toEntities(serverId: ServerId, sessionId: SessionId): List<AppliedIslandEntity> =
    map { it.toEntity(serverId, sessionId) }

fun List<AppliedIslandEntity>.toModels(): List<AppliedIsland> = map { it.toModel() }

/**
 * pending 幂等写入的实体化。[asOfSeq] 是守卫列的值：调用方传“本次变更所依据的会话位置”，
 * 低于存量值的写入在 SQL 层就是 0 行（D13）。
 */
fun PendingRequest.toEntity(asOfSeq: Long): PendingRequestEntity = PendingRequestEntity(
    serverId = serverId,
    kind = kind,
    requestId = requestId,
    sessionId = sessionId,
    status = status,
    completeness = completeness,
    payloadJson = payloadJson,
    firstSeenAtMs = firstSeenAtMs,
    createdAtMs = createdAtMs,
    asOfSeq = asOfSeq,
    anchorSeq = anchorSeq,
    callId = callId,
    toolName = toolName,
    dismissedAtMs = dismissedAtMs,
    resolvedAtMs = resolvedAtMs,
)

fun PendingRequestEntity.toModel(): PendingRequest = PendingRequest(
    serverId = serverId,
    kind = kind,
    requestId = requestId,
    sessionId = sessionId,
    status = status,
    completeness = completeness,
    payloadJson = payloadJson,
    firstSeenAtMs = firstSeenAtMs,
    createdAtMs = createdAtMs,
    dismissedAtMs = dismissedAtMs,
    resolvedAtMs = resolvedAtMs,
    anchorSeq = anchorSeq,
    callId = callId,
    toolName = toolName,
)

fun TransportCursor.toEntity(updatedAtMs: Long): SyncStateEntity = SyncStateEntity(
    identityHash = identity.digest,
    kind = channel.value,
    seq = sinceSeq,
    epoch = epoch,
    updatedAtMs = updatedAtMs,
)

/** 摘要必须仍是合法的 64 位小写 hex；被写坏时返回 null 而不是抛异常（读路径不能崩）。 */
fun SyncStateEntity.toCursor(): TransportCursor? {
    if (!identityHash.matches(DIGEST_REGEX)) return null
    return TransportCursor(
        identity = ConnectionIdentity(identityHash),
        channel = ChannelKind(kind),
        sinceSeq = seq,
        epoch = epoch,
    )
}

fun NeedsResync.toEntity(serverId: ServerId, requestedAtMs: Long): ResyncRequestEntity = ResyncRequestEntity(
    serverId = serverId,
    sessionId = sessionId,
    reason = reason,
    expectedSeq = expectedSeq,
    attempts = attempts,
    degraded = degraded,
    requestedAtMs = requestedAtMs,
)

/** `reason` 列就是 [ResyncReason] 枚举（D27），因此读回不需要解析，也就不可能“猜错语义”。 */
fun ResyncRequestEntity.toModel(): NeedsResync = NeedsResync(
    sessionId = sessionId,
    reason = reason,
    expectedSeq = expectedSeq,
    attempts = attempts,
    degraded = degraded,
)

/**
 * 外发队列条目：`kind` / `tag` / `state` 全部是 `:core:model` 的类型（D27/D28），
 * tag 的格式在构造时就由 `NotificationTag.init` 校验，不需要在这里重复。
 */
fun outboxEntry(
    identityHash: String,
    kind: PendingKind,
    requestId: RequestId,
    notificationTag: NotificationTag,
    state: OutboxState,
    nowMs: Long,
): NotificationOutboxEntity = NotificationOutboxEntity(
    identityHash = identityHash,
    kind = kind,
    requestId = requestId,
    notificationTag = notificationTag,
    state = state,
    createdAtMs = nowMs,
    updatedAtMs = nowMs,
)

/** 库里 `sync_owner.role` 存 [SyncOwnerRole] 枚举；聚合行对象只用基础类型，因此这里做一次名字解析。 */
fun SyncOwnerStateRow.roleOrNull(): SyncOwnerRole? = SyncOwnerRole.entries.firstOrNull { it.name == role }

