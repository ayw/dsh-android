package ai.deepseek.dsh.android.designsystem

import ai.deepseek.dsh.android.model.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 分发点穷尽性检查（G05 的 UI 侧兜底）。
 *
 * `TimelineItemRow` 的 `when` 刻意不写 `else`：新增变体会直接编译失败。
 * 但**删除**一个变体或把它并进别的分支，编译器不会报错——所以这里用文本扫描做真实检查：
 * 每个 `TimelineItem` 嵌套变体都必须以 `is TimelineItem.<Variant> ->` 的形式出现在分发点里。
 *
 * 变体清单来自 `TimelineItem::class.java.declaredClasses`（模型是唯一事实来源），
 * 因此给密封接口加一个变体也会立刻让这条测试失败，而不是静默漏渲染。
 */
class TimelineDispatcherExhaustivenessTest {

    private val dispatcherSource: String by lazy {
        val relative = "src/main/kotlin/ai/deepseek/dsh/android/designsystem/timeline/TimelineItemRow.kt"
        val file = File(ResourceCatalog.moduleRoot(), relative)
        assertTrue("dispatcher source not found: $file", file.isFile)
        file.readText()
    }

    private fun modelVariants(): List<String> =
        TimelineItem::class.java.declaredClasses.map { it.simpleName }.sorted()

    @Test
    fun modelExposesTheDocumentedVariantList() {
        assertEquals(EXPECTED_VARIANTS.sorted(), modelVariants())
    }

    @Test
    fun everyVariantIsDispatchedExplicitly() {
        val missing = modelVariants().filter { variant ->
            !Regex("is\\s+TimelineItem\\.$variant\\s*->").containsMatchIn(dispatcherSource)
        }
        assertEquals("variants without a dedicated branch: $missing", emptyList<String>(), missing)
    }

    @Test
    fun everyDocumentedVariantIsStillRepresented() {
        val absent = EXPECTED_VARIANTS.filter { variant ->
            !Regex("is\\s+TimelineItem\\.$variant\\s*->").containsMatchIn(dispatcherSource)
        }
        assertEquals("regressed variants: $absent", emptyList<String>(), absent)
    }

    @Test
    fun dispatcherHasNoCatchAllBranch() {
        val withoutComments = dispatcherSource.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "the dispatcher must stay a single exhaustive when over TimelineItem",
            withoutComments.contains("when (item)"),
        )
        assertTrue(
            "the dispatcher must never grow an else branch: it would swallow new TimelineItem variants",
            Regex("\\belse\\s*->").find(withoutComments) == null,
        )
    }

    @Test
    fun oneBranchPerVariantAndNoExtraUnknownVariant() {
        val branches = Regex("is\\s+TimelineItem\\.([A-Za-z0-9_]+)\\s*->")
            .findAll(dispatcherSource)
            .map { it.groupValues[1] }
            .toList()
        assertEquals("each variant needs exactly one branch", branches.size, branches.distinct().size)
        assertEquals(
            "dispatcher branches drift from the model",
            modelVariants().sorted(),
            branches.sorted(),
        )
    }

    private companion object {
        /** docs/ACCEPTANCE.md G05 列出的十种时间线条目。 */
        val EXPECTED_VARIANTS = listOf(
            "Assistant",
            "ActivityGroup",
            "CompactionMarker",
            "GapMarker",
            "HistoryUnsafeMarker",
            "PendingAnchor",
            "SystemNotice",
            "TurnEndBanner",
            "UnsupportedCount",
            "User",
        )
    }
}
