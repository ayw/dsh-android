package ai.deepseek.dsh.android.database

import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.NotificationTag
import ai.deepseek.dsh.android.model.OutboxState
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.PendingStatus
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ResyncReason
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.SyncOwnerRole
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `:core:database` 的可持久化类型全部落在这里。
 *
 * 列的类型选择是刻意的：
 *  - 身份与序号使用 `:core:model` 的 value class（经 [DshTypeConverters] 绑定），让 DAO 之间不会
 *    把 serverId / sessionId / seq 互相传错；
 *  - 聚合查询返回的行对象（*Row）只用基础类型，因为它们是 SQL 计算结果而不是领域对象；
 *  - 时间一律 `Long` epoch 毫秒，由调用方传入（`:core:domain` 的 Clock），库里不写 `CURRENT_TIMESTAMP`，
 *    这样测试可以注入虚拟时间，也避免系统时钟跳变直接影响保留策略。
 *
 * 任何表都不存放明文凭据：[ServerCredentialEntity] 只有 IV 与密文两列（G09.4 的反射检查依赖这一点）。
 */

/**
 * 一台已配对的网关（D01/D14）。`identityHash` 是当前生效的连接身份摘要
 * （`IdentityDigest.of(base, token)`），token 轮换会产生新摘要并把旧摘要写进 [IdentityRetiredEntity]。
 */
@Entity(tableName = "server")
data class ServerEntity(
    @PrimaryKey val serverId: ServerId,
    val label: String,
    /** 配对时保存的 base URL；digest 依赖它，必须逐字节保存（不去斜杠、不小写 host、不补默认端口）。 */
    val baseUrl: String,
    val identityHash: String,
    val addedAtMs: Long,
    val lastSeenAtMs: Long,
)

/**
 * 凭据密文（D14）：AAD 绑定 identity digest，blob 自带 magic+version 头。
 * 明文 token 从不进 Room，因此这里也从来没有对应列。
 */
@Entity(
    tableName = "server_credential",
    primaryKeys = ["identityHash"],
    indices = [Index("serverId")],
)
data class ServerCredentialEntity(
    val identityHash: String,
    val serverId: ServerId,
    /** AES-GCM 随机 IV（12 字节）。 */
    val iv: ByteArray,
    /** magic + version + GCM 密文+tag 的整体 blob。 */
    val ciphertext: ByteArray,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is ServerCredentialEntity &&
            identityHash == other.identityHash &&
            serverId == other.serverId &&
            iv.contentEquals(other.iv) &&
            ciphertext.contentEquals(other.ciphertext) &&
            createdAtMs == other.createdAtMs &&
            updatedAtMs == other.updatedAtMs)

    override fun hashCode(): Int {
        var result = identityHash.hashCode()
        result = 31 * result + serverId.hashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + updatedAtMs.hashCode()
        return result
    }
}

/**
 * 会话名册投影（D19）：`rosterRank` 一旦写入就不可变，keyset 分页只依赖它；
 * `updatedAtMs` 会被后台同步改写，所以它只用于显示与排序展示，不作为分页游标的唯一依据。
 * `lastSeenRank` 记录最后一次被 roster 全量拉取确认的 rank，小于当前 rank 即为 absent（tombstone 候选）。
 */
@Entity(
    tableName = "session",
    primaryKeys = ["serverId", "sessionId"],
    indices = [
        Index(value = ["serverId", "rosterRank", "sessionId"]),
        Index(value = ["serverId", "updatedAtMs", "sessionId"]),
        Index(value = ["serverId", "lastSeenRank"]),
    ],
)
data class SessionEntity(
    val serverId: ServerId,
    val sessionId: SessionId,
    /** 标题属于会话内容：日志与遥测不得外带，只在这里存一份。 */
    val title: String,
    val updatedAtMs: Long,
    val running: Boolean,
    val archived: Boolean,
    val workspaceId: String?,
    val rosterRank: Long,
    val lastSeenRank: Long,
    /** null 表示未被标死；非 null 是标死时刻，过期后由 [SessionDao.purgeTombstonedSessions] 真正删除。 */
    val tombstonedAtMs: Long? = null,
)

/**
 * 会话位置行（D07：所有表以 (serverId, sessionId) 为键）。
 * `contiguousSeq` 与 `appliedMaxSeq` 两列严禁合并：快照尾部可以应用 900..950 而 contiguousSeq 仍是 100
 * （G06）。`prunedBelowSeq` 记录保留裁剪的界，UI 据此显示“历史可能不完整”。
 */
@Entity(
    tableName = "session_head",
    primaryKeys = ["serverId", "sessionId"],
    indices = [Index(value = ["serverId", "sessionId", "appliedMaxSeq"])],
)
data class SessionHeadEntity(
    val serverId: ServerId,
    val sessionId: SessionId,
    val contiguousSeq: EventSeq? = null,
    val appliedMaxSeq: EventSeq? = null,
    /** D08：未知且没有 ignorable 的事件 → 历史不可安全重建。 */
    val historyUnsafe: Boolean = false,
    /** 历史有洞但还能重建（与 historyUnsafe 分开：前者是不完整，后者是不可重建）。 */
    val historyIncomplete: Boolean = false,
    val prunedBelowSeq: EventSeq? = null,
    val lastAppliedAtMs: Long = 0,
)

/**
 * 原始帧（durable raw log）。`payloadJson` 原样存取、绝不重新编码（D06）。
 * 表用 rowid + `UNIQUE(serverId, sessionId, seq)`：重复帧需要整行替换（@Upsert），
 * 而 `UNIQUE` 就是它的冲突目标；显式列出的三个冲突列同时也是历史分页的索引。
 */
@Entity(
    tableName = "session_event",
    indices = [
        Index(value = ["serverId", "sessionId", "seq"], unique = true),
        Index(value = ["serverId", "sessionId", "type"]),
        Index(value = ["serverId", "receivedAtMs"]),
    ],
)
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val serverId: ServerId,
    val sessionId: SessionId,
    val seq: EventSeq,
    val type: String,
    /** D08 需要它才能在离线重放时复现三档投影；payload 里没有这个字段。 */
    val ignorable: Boolean,
    val payloadJson: String,
    /** 帧来源：mux / host / 历史修复。取值见 [Origins]。 */
    val origin: String,
    /** 事件自带的时间戳（投影与 UI 显示用它）。 */
    val wallClockMs: Long,
    /** 本机收到帧的时刻（保留策略与诊断用它，不参与投影）。 */
    val receivedAtMs: Long,
)

object Origins {
    const val MUX = "mux"
    const val HOST = "host"
    const val HISTORY = "history"
}

/** 已应用区间（D07：补上 serverId）。合并算法只在 `:core:domain` 的 `Islands` 里有一份（D26），本模块不做合并。 */
@Entity(
    tableName = "applied_island",
    primaryKeys = ["serverId", "sessionId", "startSeq"],
    indices = [Index(value = ["serverId", "sessionId", "endSeq"])],
)
data class AppliedIslandEntity(
    val serverId: ServerId,
    val sessionId: SessionId,
    val startSeq: EventSeq,
    val endSeq: EventSeq,
)

/**
 * pending 请求（D13）。主键 `(serverId, kind, requestId)`；
 * `asOfSeq` 是幂等守卫列：只有 `excluded.asOfSeq >= 现有 asOfSeq` 才落库，陈旧写在 SQL 层就是 0 行。
 * `tombstonedAtMs` / `sweptAtMs` 与 `resolvedAtMs`、`dismissedAtMs` 互不相同：
 * tombstone 是“基线里没有它”（G07 要求重放 request 仍保持 tombstoned），dismiss 只影响徽标。
 */
@Entity(
    tableName = "pending_request",
    primaryKeys = ["serverId", "kind", "requestId"],
    indices = [
        Index(value = ["serverId", "status"]),
        Index(value = ["serverId", "sessionId"]),
        Index(value = ["serverId", "tombstonedAtMs"]),
    ],
)
data class PendingRequestEntity(
    val serverId: ServerId,
    val kind: PendingKind,
    /** approval 取 approval/asked 的 data.id，question 取 ask_user_question 的 callId（D13）。 */
    val requestId: RequestId,
    val sessionId: SessionId,
    val status: PendingStatus,
    val completeness: PendingCompleteness,
    /** 只存规范化后的 payload 原文：审批原因/提问文本是会话内容，不进日志。 */
    val payloadJson: String,
    val firstSeenAtMs: Long,
    val createdAtMs: Long,
    val asOfSeq: Long,
    val anchorSeq: EventSeq? = null,
    val callId: CallId? = null,
    val toolName: String? = null,
    val dismissedAtMs: Long? = null,
    val resolvedAtMs: Long? = null,
    val tombstonedAtMs: Long? = null,
    /** 最近一次 baseline sweep 确认“仍在”的时刻（世代标记）；completeness 的时间判断用它。 */
    val sweptAtMs: Long? = null,
)

/**
 * 通知外发队列（D28：这张表只有一个含义 —— 通知外发期望，不承载任何写请求回执）。
 * PK 让写入天然幂等：重复入队不会重复通知。
 * 列类型按 D27/D28 全部用枚举与值类，不再用 stringly-typed 字符串：
 * `state` 存 `:core:model` 的 [OutboxState]（`New, Sent, Cancelled, Skipped`），
 * `kind` 只能是 [PendingKind]（Approval|Question），`notificationTag` 只能是冻结格式的 [NotificationTag]。
 */
@Entity(
    tableName = "notification_outbox",
    primaryKeys = ["identityHash", "kind", "requestId"],
    indices = [Index(value = ["identityHash", "state"]), Index("notificationTag")],
)
data class NotificationOutboxEntity(
    val identityHash: String,
    val kind: PendingKind,
    val requestId: RequestId,
    /** 系统通知的替换与撤回标识；退役身份清理会整行删掉它（见 [IdentityDao]）。 */
    val notificationTag: NotificationTag,
    val state: OutboxState,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is NotificationOutboxEntity &&
            identityHash == other.identityHash &&
            kind == other.kind &&
            requestId == other.requestId &&
            notificationTag == other.notificationTag &&
            state == other.state &&
            createdAtMs == other.createdAtMs &&
            updatedAtMs == other.updatedAtMs)

    override fun hashCode(): Int {
        var result = identityHash.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + requestId.hashCode()
        result = 31 * result + notificationTag.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + updatedAtMs.hashCode()
        return result
    }
}

/** 传输游标（只用于 poll 增量，D07/F3）：按 (identityHash, kind) 存 seq + epoch。 */
@Entity(
    tableName = "sync_state",
    primaryKeys = ["identityHash", "kind"],
    indices = [Index("identityHash")],
)
data class SyncStateEntity(
    val identityHash: String,
    /** 通道名：mux / host（[ai.deepseek.dsh.android.model.ChannelKind.value]）。 */
    val kind: String,
    val seq: Long,
    val epoch: String?,
    val updatedAtMs: Long,
)

/**
 * 退役身份账本（D14）。`serverId` 与 identityHash 同时留下，迟到帧才能归因到旧身份；
 * 保留窗口由 `:core:domain` 的 `identityPurgeAfterMs` 决定，本模块只存 `retiredAtMs`。
 */
@Entity(
    tableName = "identity_retired",
    primaryKeys = ["identityHash"],
    indices = [Index(value = ["retiredAtMs"])],
)
data class IdentityRetiredEntity(
    val identityHash: String,
    val serverId: ServerId,
    val retiredAtMs: Long,
)

/** 403/scope 拒绝的按身份计数（G18：AuthExpired 只由观察到的 401/403 进入）。 */
@Entity(
    tableName = "auth_scope_failures",
    primaryKeys = ["identityHash", "scope"],
    indices = [Index("identityHash")],
)
data class AuthScopeFailureEntity(
    val identityHash: String,
    /** scope 名字，不含 URL、路径或凭据。 */
    val scope: String,
    val failCount: Long,
    val lastCode: Int,
    val lastFailureAtMs: Long,
)

/** needsResync 请求（D08/G06）：attempts 与 degraded 由引擎计算后整体覆盖写入。 */
@Entity(
    tableName = "resync_request",
    primaryKeys = ["serverId", "sessionId"],
    indices = [Index(value = ["serverId", "degraded"])],
)
data class ResyncRequestEntity(
    val serverId: ServerId,
    val sessionId: SessionId,
    /** D27：列直接存 [ResyncReason] 枚举（`SeqGap` / `HistoryRepair` / `ManualImmediate`），不是网关字符串。 */
    val reason: ResyncReason,
    val expectedSeq: EventSeq,
    val attempts: Int,
    val degraded: Boolean,
    val requestedAtMs: Long,
)

/**
 * 前后台交接的唯一真相（D10）：单行表，`ownerKey` 恒为 [SyncOwnerEntity.SINGLETON]。
 * Worker 抢不到就开始的 CAS 由 `SyncOwnerDao.casOwner` 的一条语句完成。
 * `role` 列存 [SyncOwnerRole] 枚举（D27）；D10 原文把这两列写在 `sync_state` 里，
 * 这里按任务书拆成独立单行表：owner 是设备级事实，不该随 identity 轮换被清（见 [IdentityDao]）。
 */
@Entity(tableName = "sync_owner")
data class SyncOwnerEntity(
    @PrimaryKey val ownerKey: String = SINGLETON,
    val role: SyncOwnerRole,
    /** 持有者标识（进程内 actor id / worker id）；role 为 None 时是 [NoneRole.HOLDER]（空串，不是 null）。 */
    val holderId: String,
    val foregroundRequestedAtMs: Long? = null,
    val updatedAtMs: Long,
) {
    companion object {
        const val SINGLETON = "sync-owner"
    }
}

object NoneRole {
    /** role=None 时的 holderId，空串而不是 null，保证 CAS 的 `=` 判断是二值的。 */
    const val HOLDER = ""
}

/** 诊断计数器（D23/G10）：只存计数，语义名在 [DiagnosticsCounters]。 */
@Entity(tableName = "diagnostics_counters")
data class DiagnosticsCounterEntity(
    @PrimaryKey val name: String,
    val value: Long,
    val updatedAtMs: Long,
)
