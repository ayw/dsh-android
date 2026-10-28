package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.model.RosterEntry
import ai.deepseek.dsh.android.model.SessionId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G12（任务书测试 d）：名册分页的 keyset 正确性。
 *
 * D31 决定客户端不引 `androidx.paging`，所以这条验收项真正要保的是**分页语义本身**：
 * 游标由 `(rosterRank, sessionId)` 两个字段构成，同 rank 的行不会跨过页边界消失或重复；
 * 翻页也不依赖 `updatedAtMs` —— 后台同步会改写它，用偏移量或易变排序键就会跳行。
 */
class RosterKeysetPagingSourceTest {

    /** 真库的 keyset 查询等价物：排序键 = (rosterRank, sessionId)，游标严格“在它之后”。 */
    private class FakeRoster(entries: List<RosterEntry>) {
        val afterCalls = mutableListOf<Pair<Long, String>>()
        private val all = entries.sortedWith(compareBy({ it.rosterRank }, { it.sessionId.value }))

        fun top(limit: Int): List<RosterEntry> = all.take(limit)

        fun after(rank: Long, sessionId: String, limit: Int): List<RosterEntry> {
            afterCalls += rank to sessionId
            return all
                .filter { it.rosterRank > rank || (it.rosterRank == rank && it.sessionId.value > sessionId) }
                .take(limit)
        }
    }

    private fun entry(rank: Long, id: String) = RosterEntry(
        sessionId = SessionId(id),
        title = "s-$id",
        updatedAtMs = 1L,
        running = false,
        archived = false,
        workspaceId = null,
        rosterRank = rank,
    )

    private fun source(size: Int, roster: FakeRoster) =
        RosterKeysetPagingSource(pageSize = size, topPage = roster::top, pageAfter = roster::after)

    /** 4 行两两同 rank：跨页边界正好落在平局中间，这是最容易跳行/重行的形状。 */
    private val tiedRoster = listOf(entry(10, "a"), entry(10, "b"), entry(20, "c"), entry(20, "d"))

    @Test
    fun firstPageComesFromTopAndCarriesLastRowAsCursor() = runBlocking {
        val roster = FakeRoster(tiedRoster)
        val page = source(2, roster).load()

        assertEquals(listOf("a", "b"), page.items.map { it.sessionId.value })
        assertEquals(RosterKeysetCursor(10, "b"), page.next)
        assertTrue(page.hasMore)
        // 首页不经过游标通道：否则首屏会读到上一代的 rank。
        assertTrue(roster.afterCalls.isEmpty())
    }

    @Test
    fun cursorCarriesBothKeysetFieldsSoTiesAreNotSkipped() = runBlocking {
        val roster = FakeRoster(tiedRoster)
        val paging = source(2, roster)
        val first = paging.load()
        val second = paging.load(first.next)

        // 只用 rank 翻页的话，`b` 之后的查询会重新命中同 rank 的 `a`/`b` 或直接越过它们。
        assertEquals(listOf(10L to "b"), roster.afterCalls)
        assertEquals(listOf("c", "d"), second.items.map { it.sessionId.value })
    }

    @Test
    fun walkingPagesYieldsEveryRowOnceInOrder() = runBlocking {
        val roster = FakeRoster(tiedRoster)
        val paging = source(2, roster)
        val collected = mutableListOf<String>()
        var cursor: RosterKeysetCursor? = null
        // 步数上限本身就是断言：分页若不能收敛（空页仍报 hasMore），这里会先失败而不是挂死。
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            val page = paging.load(cursor)
            collected += page.items.map { it.sessionId.value }
            if (!page.hasMore) break
            cursor = page.next
        }

        assertEquals(listOf("a", "b", "c", "d"), collected)
        assertEquals(collected.size, collected.distinct().size)
    }

    @Test
    fun lastPageReportsNoMoreAndEmptyStoreHasNoCursor() = runBlocking {
        val roster = FakeRoster(tiedRoster)
        val page = source(10, roster).load()
        assertEquals(4, page.items.size)
        assertFalse("拿不满一页就一定到底", page.hasMore)
        assertEquals(RosterKeysetCursor(20, "d"), page.next)

        val empty = source(10, FakeRoster(emptyList())).load()
        assertTrue(empty.isEmpty)
        assertEquals(null, empty.next)
        assertFalse(empty.hasMore)
    }

    @Test
    fun loadUntilSpansPagesWithoutGapOrOverlap() = runBlocking {
        val entries = (1L..7L).map { entry(it / 2, "s$it") } + entry(1, "s0")
        val roster = FakeRoster(entries)
        val total = entries.size

        val head = source(3, roster).loadUntil(6)
        assertEquals(6, head.size)
        assertEquals(head.size, head.distinctBy { it.sessionId.value }.size)
        // 同一批数据整页取回，必须正好是前 6 行的同一集合。
        assertEquals(roster.top(6).map { it.sessionId.value }, head.map { it.sessionId.value })

        val everything = source(3, FakeRoster(entries)).loadUntil(total)
        assertEquals(total, everything.size)
        assertEquals(total, everything.map { it.sessionId.value }.distinct().size)
    }

    @Test
    fun loadUntilStopsAtTheEndAndRejectsImpossibleArguments() {
        val roster = FakeRoster(tiedRoster)
        assertEquals(0, runBlocking { source(3, roster).loadUntil(0).size })
        // 要得比数据多：拿到全部就停，不无限循环空页。
        assertEquals(4, runBlocking { source(3, roster).loadUntil(100).size })
        assertThrows(IllegalArgumentException::class.java) { source(-1, roster) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { source(3, roster).loadUntil(-1) } }
    }

    @Test
    fun cursorIsTakenFromTheRowTheStoreActuallyReturned() = runBlocking {
        // 后端返回顺序与请求顺序不一致（真库里 rank 会随同步批次变化）：游标仍以**最后一行**为准，
        // 下一页才能严格接续，绝不会用请求里猜的 rank。
        val roster = object {
            fun top(limit: Int) = listOf(entry(30, "z"), entry(10, "y")).take(limit)
            fun after(rank: Long, sessionId: String, limit: Int) = emptyList<RosterEntry>()
        }
        val page = RosterKeysetPagingSource(2, roster::top, roster::after).load()
        assertEquals(RosterKeysetCursor(10, "y"), page.next)
    }

    private companion object {
        /** 翻到底只需要 3 页（4 行 / 每页 2）；留足余量，超出即视为不收敛。 */
        const val MAX_PAGES = 8
    }
}
