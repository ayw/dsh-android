package ai.deepseek.dsh.android.database

/**
 * keyset 分页的**游标值**推导，纯 Kotlin、无 Room 依赖，因此可以在普通 JVM 测试里跑。
 *
 * 注意职责边界：分页的执行真相永远是 [SessionDao] 里的 SQL（`ORDER BY … DESC, sessionId DESC` +
 * `(key < :c OR (key = :c AND sessionId < :s))`）。这里只做两件 SQL 无法替适配器做的事：
 *  1. 从一页返回的实体里算出**下一页的游标**（PagingSource 的 `nextKey`）；
 *  2. 在内存里复算同一套比较，用来断言游标推导与 SQL 的形状一致（`KeysetOrderTest` 会用反射把
 *     这里的比较式和 DAO 注解里的字面量对照）。
 *
 * 两种游标都要，因为两侧契约不同：
 *  - [rosterKeyset]：`(rosterRank, sessionId)` —— D19/G12 规定的主游标，rank 写入后不可变；
 *  - [updatedAtKeyset]：`(updatedAtMs, sessionId)` —— `:core:domain` 的 `SessionKeyset` 形状，
 *    feature 侧从上一页尾部拿到的就是它。
 *
 * 字符串比较口径：SQLite 默认 BINARY 排序按 UTF-8 字节，Kotlin 的 `String.compareTo` 按 UTF-16 码元。
 * 两者在 ASCII 与 BMP 内一致，只有增补平面（emoji 等）才可能分叉；sessionId 是服务端生成的 id，
 * 因此这里的内存复算与实际分页结果一致。真按 sessionId 排序的权威仍是 SQL。
 */
object KeysetOrder {

    /** 一个 keyset 位置：排序键 + 破平的 sessionId。 */
    data class Cursor(val sortKey: Long, val sessionId: String)

    /** `ORDER BY sortKey DESC, sessionId DESC` 的顺序（返回负/零/正，与 SQL 的行序一致）。 */
    fun compareDescending(a: Cursor, b: Cursor): Int {
        val byKey = b.sortKey.compareTo(a.sortKey)
        return if (byKey != 0) byKey else b.sessionId.compareTo(a.sessionId)
    }

    /**
     * `(sortKey < :c.sortKey OR (sortKey = :c.sortKey AND sessionId < :c.sessionId))`：
     * 候选行是否落在游标**之后**（即下一页）。等于游标本身要排除，否则会重复最后一行。
     */
    fun isAfter(candidate: Cursor, cursor: Cursor): Boolean =
        candidate.sortKey < cursor.sortKey ||
            (candidate.sortKey == cursor.sortKey && candidate.sessionId < cursor.sessionId)

    /** 按 SQL 的行序排序并取一页（`limit` 即 `SyncTuning.rosterPageSize` / `historyPageSize`，本模块不给默认值）。 */
    fun page(all: List<Cursor>, cursor: Cursor?, limit: Int): List<Cursor> {
        require(limit > 0) { "page size must be positive" }
        val ordered = all.sortedWith { a, b -> compareDescending(a, b) }
        if (cursor == null) return ordered.take(limit)
        return ordered.filter { isAfter(it, cursor) }.take(limit)
    }

    /** 一页里的最后一行就是续页游标；空页没有下一页。 */
    fun nextCursor(page: List<Cursor>): Cursor? = page.lastOrNull()

    /** 从 rank 游标走完整张表：断言无重复、无遗漏时用（每页的 `nextCursor` 串起来）。 */
    fun walk(all: List<Cursor>, pageSize: Int): List<List<Cursor>> {
        val pages = mutableListOf<List<Cursor>>()
        var cursor: Cursor? = null
        while (true) {
            val next = page(all, cursor, pageSize)
            if (next.isEmpty()) return pages
            pages += next
            cursor = nextCursor(next) ?: return pages
        }
    }
}

/** 名册行的主 keyset 游标（D19：不可变 `rosterRank` + sessionId 破平）。 */
fun SessionEntity.rosterKeyset(): KeysetOrder.Cursor = KeysetOrder.Cursor(rosterRank, sessionId.value)

/** 按活跃度翻页的游标（`:core:domain` 的 `SessionKeyset` 形状）。 */
fun SessionEntity.updatedAtKeyset(): KeysetOrder.Cursor = KeysetOrder.Cursor(updatedAtMs, sessionId.value)

/** 历史分页游标（D19：`(sessionId, seq) desc`，`beforeSeq` exclusive）。 */
fun SessionEntity.historyKeyset(seq: Long): KeysetOrder.Cursor = KeysetOrder.Cursor(seq, sessionId.value)
