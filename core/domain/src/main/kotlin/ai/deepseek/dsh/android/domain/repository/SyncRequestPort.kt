package ai.deepseek.dsh.android.domain.repository

import ai.deepseek.dsh.android.model.ServerId
import kotlinx.coroutines.flow.Flow

/** 触发一次同步的原因，供 actor/Worker 决策与诊断归因（不含传输游标）。 */
enum class SyncReason {
    /** 用户在列表上手动“立即同步”。 */
    ManualImmediate,

    /** 前台 `ON_START` 恢复。 */
    ForegroundResume,

    /** 写操作超时后请求 baseline 重确认（D13：UNKNOWN 由 baseline 拉回 PENDING）。 */
    BaselineRefresh,

    /** host 侧名册变化待重同步（D11 hostResyncPending）。 */
    HostResync,
}

/**
 * UI/feature 请求同步的**端口**（G17：feature 只见此抽象）。`:core:data` 用 WorkManager + SyncActor 实现：
 * - [requestImmediate] 排一次 one-shot（REPLACE 语义只替换未开始的任务，见 D10）；
 * - [setForeground] 切换前后台所有权（Room 的 owner 列是跨进程唯一真相）；
 * - [isBackgroundSyncEnabled] 反映用户级开关（D24 FeatureFlags），关闭即取消周期任务并撤回通知。
 */
interface SyncRequestPort {
    suspend fun requestImmediate(serverId: ServerId, reason: SyncReason): Result<Unit>

    suspend fun setForeground(active: Boolean): Result<Unit>

    fun isBackgroundSyncEnabled(): Flow<Boolean>
}
