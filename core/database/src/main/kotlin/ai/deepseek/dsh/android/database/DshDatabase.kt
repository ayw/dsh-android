package ai.deepseek.dsh.android.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * 离线优先的本地真值库（D07）：UI 只读这里的 DAO Flow，Room 是 single source of truth。
 *
 * 版本决策：**version = 1，没有 MIGRATION_1_2 占位** —— 本轮没有需要跨版本搬运的数据，加占位迁移会让
 * G16 的“schema 基线逐字节一致”凭空多出差异。真正加表/加列时才把版本抬到 2，并同时提供命名迁移。
 *
 * 刻意不调用 `fallbackToDestructiveMigration()`（G16）：版本与 schema 不匹配时必须失败，
 * 不能静默清空用户的历史。这里也没有 `FallbackableMigration`：迁移只能显式命名。
 *
 * 日志模式保持 Room 默认（不用 WAL）：D10 的 `sync_owner` 是跨进程交接表，Room 不建议在多进程写入
 * 场景启用 WAL。
 *
 * 写事务约定（G06 的 `tombstone → baseline → events → cursor` 顺序）：
 * `:core:data` 的适配器把 [SessionEventDao.upsertEvents] / [SessionHeadDao.upsertSessionHeads] /
 * [IslandDao.replaceIslands] / [PendingDao.applyPendingChanges] / [OutboxDao.insertOutbox] /
 * [SyncStateDao.advanceCursor] / [ResyncDao.putResync] 这几个原语放进**一次**
 * `db.withTransaction { … }`。Room 2.5+ 的 `@Transaction` 方法会加入已存在的外层事务，
 * 因此这些原语内部的多语句包装不会另开第二个事务。
 */
@Database(
    entities = [
        ServerEntity::class,
        ServerCredentialEntity::class,
        SessionEntity::class,
        SessionHeadEntity::class,
        SessionEventEntity::class,
        AppliedIslandEntity::class,
        PendingRequestEntity::class,
        NotificationOutboxEntity::class,
        SyncStateEntity::class,
        IdentityRetiredEntity::class,
        AuthScopeFailureEntity::class,
        ResyncRequestEntity::class,
        SyncOwnerEntity::class,
        DiagnosticsCounterEntity::class,
    ],
    version = DshDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(DshTypeConverters::class)
abstract class DshDatabase : RoomDatabase() {

    abstract fun serverDao(): ServerDao

    abstract fun credentialDao(): CredentialDao

    abstract fun sessionDao(): SessionDao

    abstract fun sessionHeadDao(): SessionHeadDao

    abstract fun sessionEventDao(): SessionEventDao

    abstract fun islandDao(): IslandDao

    abstract fun pendingDao(): PendingDao

    abstract fun outboxDao(): OutboxDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun syncOwnerDao(): SyncOwnerDao

    abstract fun resyncDao(): ResyncDao

    abstract fun identityDao(): IdentityDao

    abstract fun authDao(): AuthDao

    abstract fun diagnosticsDao(): DiagnosticsDao

    companion object {
        const val NAME = "dsh.db"
        const val VERSION = 1

        /**
         * 组合根的装配点（`:app` 的 AppScope 调用）。这里没有任何 migration 兜底：
         * 版本不匹配即抛异常（G16）。新增版本时必须显式命名迁移并在这里挂上 `addMigrations(...)`。
         *
         * @param name 库文件名；测试可传临时文件路径。
         */
        fun builder(context: Context, name: String = NAME): RoomDatabase.Builder<DshDatabase> =
            Room.databaseBuilder(context.applicationContext, DshDatabase::class.java, name)

        /** 内存库只用于本模块的 Room 冒烟测试（不参与 G16 的 schema diff）。 */
        fun inMemoryForTest(context: Context): DshDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, DshDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
