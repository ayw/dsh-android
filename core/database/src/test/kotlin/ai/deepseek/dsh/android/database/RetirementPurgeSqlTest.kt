package ai.deepseek.dsh.android.database

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D14 / G16 的 SQL 层证据，不需要 Robolectric 或 Android runtime。
 *
 * Room 的 `@Query` 是 CLASS retention（`kotlin.annotation.Retention(BINARY)` +
 * `java.lang.annotation.Retention(CLASS)`），运行时反射读不到 SQL 文本，因此主检查按
 * `PendingContentCodecTest` 的方式从 `user.dir` 向上找到 DAO 源文件，对 KSP 实际编译的那段
 * SQL 文本断言；反射只作为“方法确实存在”的次要检查。
 *
 * 覆盖：
 *  a) `purgeableDigests` 与 `deletePurgedLedgerRows` 的 WHERE 谓词逐字相同（保留窗口 + A→B→A 守卫）；
 *  b) 账本 DELETE 确实由 `purgeRetiredIdentities` 在清完传输态之后调用；
 *  c) `clearTransportStateFor` 清四张传输表，且整个 DAO 不删任何会话/投影表；
 *  d) `identity_retired` 的三列与主键。
 */
class RetirementPurgeSqlTest {

    private val daoSource = repositoryFile("core/database/src/main/kotlin/ai/deepseek/dsh/android/database/IdentityDao.kt")
    private val entitiesSource = repositoryFile("core/database/src/main/kotlin/ai/deepseek/dsh/android/database/DshEntities.kt")

    private val queries: Map<String, String> = queriesOf(daoSource)

    private val purgeableSql = queries.getValue("purgeableDigests")
    private val ledgerDeleteSql = queries.getValue("deletePurgedLedgerRows")

    // (a) ------------------------------------------------------------------

    @Test
    fun retirementPurgeableDigestsAndLedgerDeleteShareOneWherePredicate() {
        val predicate = whereClause(purgeableSql)
        assertEquals(predicate, whereClause(ledgerDeleteSql))

        // 两个条件缺一不可：7 天保留窗口 + A→B→A 的 live 守卫。
        assertContains(predicate, "retiredAtMs + :graceMs <= :nowMs")
        assertContains(predicate, "NOT EXISTS (SELECT 1 FROM server AS s WHERE s.identityHash = identity_retired.identityHash)")

        assertContains(normalized(purgeableSql), "SELECT identityHash FROM identity_retired WHERE ")
        assertContains(normalized(ledgerDeleteSql), "DELETE FROM identity_retired WHERE ")
    }

    // (b) ------------------------------------------------------------------

    @Test
    fun retirementPurgeTransactionDeletesTheLedgerThroughTheSharedPredicate() {
        val body = functionBody(daoSource, "purgeRetiredIdentities")

        assertContains(body, "purgeableDigests(nowMs, graceMs)")
        assertContains(body, "clearTransportStateFor(it)")
        assertContains(body, "deletePurgedLedgerRows(nowMs, graceMs)")
        // 顺序即语义：先枚举，再清传输态，最后删账本行（崩溃可续跑、不产生孤儿行）。
        assertTrue(body.indexOf("purgeableDigests(") < body.indexOf("clearTransportStateFor("))
        assertTrue(body.indexOf("clearTransportStateFor(") < body.indexOf("deletePurgedLedgerRows("))

        // 次要检查：占位符形式的方法真的挂在 DAO 上（@Query 文本本身在 (a) 里比对）。
        val declared = IdentityDao::class.java.declaredMethods.map { it.name }.toSet()
        listOf(
            "purgeableDigests",
            "deletePurgedLedgerRows",
            "deleteCursorsFor",
            "deleteOutboxFor",
            "deleteScopeFailuresFor",
            "deleteCredentialFor",
        ).forEach { name -> assertContains(declared, name) }
    }

    // (c) ------------------------------------------------------------------

    @Test
    fun retirementClearTransportStateTouchesOnlyTransportTables() {
        val body = functionBody(daoSource, "clearTransportStateFor")
        listOf("deleteCursorsFor", "deleteOutboxFor", "deleteScopeFailuresFor", "deleteCredentialFor")
            .forEach { call -> assertContains(body, "$call(identityHash)") }

        assertEquals("sync_state", deleteTarget(queries.getValue("deleteCursorsFor")))
        assertEquals("notification_outbox", deleteTarget(queries.getValue("deleteOutboxFor")))
        assertEquals("auth_scope_failures", deleteTarget(queries.getValue("deleteScopeFailuresFor")))
        assertEquals("server_credential", deleteTarget(queries.getValue("deleteCredentialFor")))

        // 整个 DAO 的 DELETE 只能落在这些表上；会话/投影表一张都不许出现。
        val deleteTargets = queries.values
            .filter { normalized(it).startsWith("DELETE ") }
            .map(::deleteTarget)
            .toSet()
        assertEquals(
            setOf("sync_state", "notification_outbox", "auth_scope_failures", "server_credential", "identity_retired"),
            deleteTargets,
        )

        val sessionTables = listOf("session", "session_head", "session_event", "applied_island", "pending_request")
        assertTrue(deleteTargets.intersect(sessionTables.toSet()).isEmpty())
        sessionTables.forEach { table -> assertFalse(normalized(body).contains(table), "clearTransportStateFor must not touch $table") }
    }

    // (d) ------------------------------------------------------------------

    @Test
    fun retirementIdentityRetiredEntityKeepsItsIdentityColumnsAndPrimaryKey() {
        val entity = normalized(entityAnnotation(entitiesSource, "identity_retired"))
        assertContains(entity, "tableName = \"identity_retired\"")
        assertContains(entity, "primaryKeys = [\"identityHash\"]")

        val columns = normalized(constructorParameters(entitiesSource, "IdentityRetiredEntity"))
        assertContains(columns, "val identityHash: String")
        assertContains(columns, "val serverId: ServerId")
        assertContains(columns, "val retiredAtMs: Long")
        // 账本行只留归因所需的三列：多出来的列会变成没人读的状态。
        assertEquals(3, Regex("""\bval\s""").findAll(columns).count())
    }

    // ---- source helpers --------------------------------------------------

    private fun repositoryFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("$relative not found walking up from ${System.getProperty("user.dir")}")
    }

    /** `@Query(...)` 文本 → 方法名。同时支持单行字符串与 `"""` 三引号块；括号后允许换行与缩进。 */
    private fun queriesOf(source: String): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        var annotation = source.indexOf("@Query(")
        while (annotation >= 0) {
            var literalStart = annotation + "@Query(".length
            while (literalStart < source.length && source[literalStart].isWhitespace()) literalStart++
            val sql: String
            val afterLiteral: Int
            if (source.startsWith("\"\"\"", literalStart)) {
                val start = literalStart + 3
                val end = source.indexOf("\"\"\"", start)
                check(end > start) { "unterminated triple-quoted @Query near offset $literalStart" }
                sql = source.substring(start, end)
                afterLiteral = end + 3
            } else {
                check(source.startsWith("\"", literalStart)) { "unexpected @Query literal near offset $literalStart" }
                val end = source.indexOf('"', literalStart + 1)
                check(end > literalStart) { "unterminated @Query near offset $literalStart" }
                sql = source.substring(literalStart + 1, end)
                afterLiteral = end + 1
            }
            val funIndex = source.indexOf("fun ", afterLiteral)
            check(funIndex > 0) { "@Query without a following function" }
            val nameStart = funIndex + "fun ".length
            val nameEnd = source.indexOf('(', nameStart)
            check(nameEnd > nameStart) { "unparsable function after @Query" }
            found[source.substring(nameStart, nameEnd).trim()] = sql
            annotation = source.indexOf("@Query(", afterLiteral)
        }
        return found
    }

    private fun functionBody(source: String, name: String): String {
        val signature = source.indexOf("fun $name(")
        check(signature >= 0) { "fun $name not found" }
        val paramsOpen = source.indexOf('(', signature)
        val paramsClose = paramsOpen + balanced(source, paramsOpen).length - 1
        val open = source.indexOf('{', paramsClose)
        check(open >= 0) { "fun $name has no body" }
        return braced(source, open)
    }

    private fun entityAnnotation(source: String, table: String): String {
        val tableIndex = source.indexOf("tableName = \"$table\"")
        check(tableIndex >= 0) { "entity $table not found" }
        val annotation = source.lastIndexOf("@Entity(", tableIndex)
        check(annotation >= 0) { "entity $table has no @Entity annotation" }
        return balanced(source, source.indexOf('(', annotation))
    }

    private fun constructorParameters(source: String, className: String): String {
        val declaration = source.indexOf("data class $className(")
        check(declaration >= 0) { "data class $className not found" }
        val open = source.indexOf('(', declaration)
        return balanced(source, open).removeSurrounding("(", ")")
    }

    /** 从 `open` 处的开括号起返回配平的子串（含两端），忽略没有字符串/注释嵌套的注解与形参表。 */
    private fun balanced(source: String, open: Int): String {
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        error("unbalanced parentheses starting at offset $open")
    }

    /** 同上，但对 `{}` 配平；函数体里 `foo(a, b)` 的圆括号不能提前结束提取。 */
    private fun braced(source: String, open: Int): String {
        var depth = 0
        var index = open
        while (index < source.length) {
            when {
                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3)
                    check(end > index) { "unterminated raw string at offset $index" }
                    index = end + 3
                    continue
                }
                source[index] == '"' -> {
                    var end = index + 1
                    while (end < source.length && source[end] != '"') {
                        if (source[end] == '\\') end++
                        end++
                    }
                    index = end + 1
                    continue
                }
                source.startsWith("//", index) -> {
                    val end = source.indexOf('\n', index)
                    index = if (end < 0) source.length else end + 1
                    continue
                }
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2)
                    check(end > index) { "unterminated block comment at offset $index" }
                    index = end + 2
                    continue
                }
                source[index] == '{' -> depth++
                source[index] == '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        error("unbalanced braces starting at offset $open")
    }

    private fun normalized(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    private fun whereClause(sql: String): String {
        val where = sql.indexOf("WHERE", ignoreCase = true)
        check(where >= 0) { "no WHERE in: $sql" }
        return normalized(sql.substring(where))
    }

    private fun deleteTarget(sql: String): String {
        val match = Regex("""DELETE\s+FROM\s+([A-Za-z_][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE).find(sql)
        return match?.groupValues?.get(1) ?: error("not a DELETE statement: $sql")
    }
}
