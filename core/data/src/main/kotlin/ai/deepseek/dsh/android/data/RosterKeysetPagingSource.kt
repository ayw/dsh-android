package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.model.RosterEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** keyset 游标：不可变的 `rosterRank` 加破平的 `sessionId`（D19）。 */
data class RosterKeysetCursor(
    val rosterRank: Long,
    val sessionId: String,
)

/** 一页的结果。[next] 是**下一页的游标**（空页时为 null），要不要继续翻只看 [hasMore]。 */
data class RosterPage(
    val items: List<RosterEntry>,
    val next: RosterKeysetCursor?,
    val hasMore: Boolean,
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * 名册的分页页源（验收 G12 / D31）。
 *
 * D31 决定客户端不引 `androidx.paging`：`:core:domain` 是纯 JVM 模块，页源的排序稳定性才是
 * 这条验收项真正要保的东西，因此这里是一个自持的 suspend 页源，而不是 `PagingSource` 子类。
 *
 * 两条不变量：
 *  - 游标只来自**本页最后一行**，且 `(rosterRank, sessionId)` 一起传，同 rank 的行不会被跳过；
 *  - 页与页之间不重排：`rosterRank` 写入后不可变，后台同步改写 `updatedAtMs` 不会让已看过的行消失或重复。
 */
class RosterKeysetPagingSource(
    private val pageSize: Int,
    private val topPage: suspend (limit: Int) -> List<RosterEntry>,
    private val pageAfter: suspend (rank: Long, sessionId: String, limit: Int) -> List<RosterEntry>,
) {

    init {
        require(pageSize > 0) { "页大小必须为正" }
    }

    /** [cursor] 为 null 即首页。 */
    suspend fun load(cursor: RosterKeysetCursor? = null): RosterPage {
        val items = if (cursor == null) {
            topPage(pageSize)
        } else {
            pageAfter(cursor.rosterRank, cursor.sessionId, pageSize)
        }
        val last = items.lastOrNull()
        return RosterPage(
            items = items,
            next = last?.let { RosterKeysetCursor(it.rosterRank, it.sessionId.value) },
            hasMore = items.size == pageSize,
        )
    }

    /** 逐页取到 `limit` 条为止；分页读侧（`SessionStore.rosterPage` 的完整 keyset 版）用这个。 */
    suspend fun loadUntil(limit: Int): List<RosterEntry> {
        require(limit >= 0) { "读取条数不能为负" }
        val out = ArrayList<RosterEntry>(minOf(limit, pageSize))
        var cursor: RosterKeysetCursor? = null
        while (out.size < limit) {
            val page = load(cursor)
            if (page.isEmpty) break
            page.items.forEach { out.add(it) }
            cursor = page.next ?: break
            if (!page.hasMore) break
        }
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }
}

/** 把 [RoomSessionStore] 的 Flow 查询适配成页源；装配点用它接线，测试用假数据直接构造页源。 */
fun roomRosterPagingSource(
    store: RoomSessionStore,
    pageSize: Int,
    top: Flow<List<RosterEntry>>,
): RosterKeysetPagingSource = RosterKeysetPagingSource(
    pageSize = pageSize,
    topPage = { top.first() },
    pageAfter = { rank, sessionId, limit -> store.rosterKeysetPage(rank, sessionId, limit).first() },
)
