package ai.deepseek.dsh.android.database

import ai.deepseek.dsh.android.model.CallId
import ai.deepseek.dsh.android.model.ChannelKind
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.NotificationTag
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import androidx.room.TypeConverter

/**
 * value class 列与查询参数的编解码。注册在 [DshDatabase] 上，因此实体里可以直接使用
 * `:core:model` 的 id 类型，DAO 之间不会把 serverId / sessionId / seq 传错成另一个 id。
 *
 * 落库形式固定：id 类走 TEXT，序号走 INTEGER。这是索引与 keyset 比较的基础
 * （`sessionId < :s` 依赖 SQLite 的 BINARY 排序，和 Kotlin 的 String 比较一致）。
 *
 * 形状选择是刻意的：普通 class + 实例方法（无参构造）是 Room 最传统的转换器写法，
 * 不依赖 `@JvmStatic` 或扩展函数转换器这类较新的编译器特性。
 *
 * 枚举列（`PendingKind` / `PendingStatus` / `PendingCompleteness` / `OutboxState` / `ResyncReason` /
 * `SyncOwnerRole`）**不在这里**：它们由 Room 2.7+ 的 `BuiltInTypeConverters` 自动按名字存 TEXT
 * （room-common 2.8.5 已包含该实现），再写一份显式转换器反而会产生“同一类型两个转换器”的歧义。
 */
class DshTypeConverters {
    @TypeConverter
    fun fromServerId(value: ServerId): String = value.value

    @TypeConverter
    fun toServerId(raw: String): ServerId = ServerId(raw)

    @TypeConverter
    fun fromSessionId(value: SessionId): String = value.value

    @TypeConverter
    fun toSessionId(raw: String): SessionId = SessionId(raw)

    @TypeConverter
    fun fromEventSeq(value: EventSeq): Long = value.value

    @TypeConverter
    fun toEventSeq(raw: Long): EventSeq = EventSeq(raw)

    @TypeConverter
    fun fromRequestId(value: RequestId): String = value.value

    @TypeConverter
    fun toRequestId(raw: String): RequestId = RequestId(raw)

    @TypeConverter
    fun fromCallId(value: CallId): String = value.value

    @TypeConverter
    fun toCallId(raw: String): CallId = CallId(raw)

    /**
     * 通知 tag 的冻结格式（`digest + ":a:|q:" + id`）由 `NotificationTag.init` 校验：
     * 库里只可能由 `NotificationTag.of` 写入合法值，读到非法值时构造即抛 ——
     * 写坏的数据要被看见，不在这里降级成 null。
     */
    @TypeConverter
    /**
     * 通知 tag 的冻结格式（`digest + ":a:|q:" + id`）由 `NotificationTag.init` 校验：
     * 库里只可能由 `NotificationTag.of` 写入合法值，读到非法值时构造即抛 ——
     * 写坏的数据要被看见，不在这里降级成 null。
     */
    fun fromNotificationTag(value: NotificationTag): String = value.value

    @TypeConverter
    fun toNotificationTag(raw: String): NotificationTag = NotificationTag(raw)

    @TypeConverter
    fun fromChannelKind(value: ChannelKind): String = value.value

    @TypeConverter
    fun toChannelKind(raw: String): ChannelKind = ChannelKind(raw)
}
