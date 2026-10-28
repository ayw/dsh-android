package ai.deepseek.dsh.android.designsystem

import ai.deepseek.dsh.android.designsystem.ResourceCatalog.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * G14 / D15 的模块内镜像检查（纯 JVM XML 解析，不依赖 Android 运行时）。
 *
 * 只读 `:core:designsystem` 自己的资源与源码：
 * - 默认 `values/` 是简体中文，`values-en/` 是英文，两边键集必须**完全相等**；
 * - 不允许空串、不允许重复键；
 * - 每个键的占位符集合必须逐 quantity 相等（避免 `%1$s` 与 `%1$d` 只改一边）；
 * - 主源码里引用的每个 `R.string.*` / `R.plurals.*` 必须两边都存在，且实参数与声明一致；
 * - 主源码里不允许 `Text("字面量")`（预览文件按 G14 的约定豁免）。
 */
class StringParityTest {

    private val root: File = ResourceCatalog.moduleRoot()

    private fun catalog(locale: String): List<Entry> {
        val dir = ResourceCatalog.resDir(root, locale)
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }.orEmpty()
        assertTrue("no xml under $dir", files.isNotEmpty())
        return files.sortedBy { it.name }.flatMap { ResourceCatalog.parse(it) }
    }

    private fun List<Entry>.named(kind: String): Map<String, Entry> =
        filter { it.kind == kind }.associateBy { it.name }

    @Test
    fun bothLocaleCatalogsExist() {
        assertTrue(File(root, "src/main/res/values/strings.xml").isFile)
        assertTrue(File(root, "src/main/res/values-en/strings.xml").isFile)
    }

    @Test
    fun keySetsAreExactlyEqual() {
        val zh = catalog("values")
        val en = catalog("values-en")
        for (kind in listOf("string", "plurals", "string-array")) {
            val zhKeys = zh.named(kind).keys
            val enKeys = en.named(kind).keys
            val onlyZh = (zhKeys - enKeys).sorted()
            val onlyEn = (enKeys - zhKeys).sorted()
            assertTrue("$kind: missing from values-en/ -> $onlyZh", onlyZh.isEmpty())
            assertTrue("$kind: only in values-en/ -> $onlyEn", onlyEn.isEmpty())
        }
        assertEquals("total entry count differs", zh.size, en.size)
    }

    @Test
    fun noEmptyValues() {
        listOf("values" to catalog("values"), "values-en" to catalog("values-en")).forEach { (locale, entries) ->
            entries.forEach { entry ->
                assertTrue("$locale: empty value for ${entry.kind}/${entry.name}", entry.name.isNotBlank())
                entry.quantities.forEach { (quantity, value) ->
                    assertTrue(
                        "$locale: empty value for ${entry.kind}/${entry.name}[$quantity]",
                        value.isNotEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun noDuplicateKeysWithinALocale() {
        listOf("values" to catalog("values"), "values-en" to catalog("values-en")).forEach { (locale, entries) ->
            val seen = HashSet<String>()
            entries.forEach { entry ->
                val id = "${entry.kind}/${entry.name}"
                assertTrue("$locale: duplicate key $id", seen.add(id))
            }
        }
    }

    @Test
    fun placeholderSetsMatchPerKeyAndQuantity() {
        val zh = catalog("values").associateBy { "${it.kind}/${it.name}" }
        val en = catalog("values-en").associateBy { "${it.kind}/${it.name}" }
        zh.forEach { (key, zhEntry) ->
            val enEntry = en[key] ?: error("$key is missing from values-en/")
            assertEquals(
                "$key: quantity set differs",
                zhEntry.quantities.keys.sorted(),
                enEntry.quantities.keys.sorted(),
            )
            zhEntry.quantities.forEach { (quantity, text) ->
                assertEquals(
                    "$key[$quantity]: placeholder set differs between locales",
                    ResourceCatalog.placeholders(text),
                    ResourceCatalog.placeholders(enEntry.quantities.getValue(quantity)),
                )
            }
        }
    }

    @Test
    fun pluralsDeclareOneAndOtherInBothLocales() {
        listOf("values" to catalog("values"), "values-en" to catalog("values-en")).forEach { (locale, entries) ->
            entries.filter { it.kind == "plurals" }.forEach { entry ->
                val quantities = entry.quantities.keys
                assertTrue("$locale: plurals/${entry.name} needs 'other'", quantities.contains("other"))
                assertTrue("$locale: plurals/${entry.name} needs 'one'", quantities.contains("one"))
            }
        }
    }

    @Test
    fun defaultLocaleIsChineseAndEnglishOverrideIsLatin() {
        val zh = File(root, "src/main/res/values/strings.xml").readText()
        val en = File(root, "src/main/res/values-en/strings.xml").readText()
        assertTrue("values/ must be Simplified Chinese (D15)", zh.any { it.code in CJK })
        assertFalse("values-en/ must not contain CJK", en.any { it.code in CJK })
    }

    @Test
    fun everyResourceReferencedByMainSourcesExistsInBothLocales() {
        val zh = catalog("values")
        val en = catalog("values-en")
        val refs = ReferenceScan.references(ResourceCatalog.mainKotlinSources(root))
        assertTrue("main sources reference no resources at all", refs.isNotEmpty())
        refs.forEach { (kind, names) ->
            val zhNames = zh.named(kind).keys
            val enNames = en.named(kind).keys
            names.forEach { name ->
                assertTrue("$kind/$name referenced but missing in values/", zhNames.contains(name))
                assertTrue("$kind/$name referenced but missing in values-en/", enNames.contains(name))
            }
        }
    }

    @Test
    fun callSitesPassExactlyTheDeclaredPlaceholderCount() {
        val declared = catalog("values").associateBy { "${it.kind}/${it.name}" }
        val failures = ArrayList<String>()
        ResourceCatalog.mainKotlinSources(root).forEach { file ->
            ResourceCatalog.callSites(file.readText()).forEach { site ->
                val entry = declared["${site.kind}/${site.name}"]
                if (entry == null) {
                    failures.add("${file.name}:${site.line}: unknown ${site.kind}/${site.name}")
                } else {
                    val expected = entry.quantities.values.maxOf { ResourceCatalog.maxPlaceholderIndex(it) }
                    if (expected != site.args) {
                        failures.add(
                            "${file.name}:${site.line}: ${site.kind}/${site.name} declares $expected " +
                                "placeholder(s) but the call passes ${site.args}",
                        )
                    }
                }
            }
        }
        assertEquals(failures.joinToString("\n"), emptyList<String>(), failures)
    }

    @Test
    fun noHardcodedUserVisibleTextInMainSources() {
        val failures = ArrayList<String>()
        ResourceCatalog.mainKotlinSources(root).forEach { file ->
            ResourceCatalog.hardcodedTextLines(file.readText(), file.name).forEach { (line, text) ->
                failures.add("${file.name}:$line: $text")
            }
        }
        assertEquals(failures.joinToString("\n"), emptyList<String>(), failures)
    }

    private companion object {
        /** 通用汉字区：用来确认默认目录确实是中文（D15）。 */
        val CJK = 0x4E00..0x9FA5
    }
}

/** 收集主源码里引用到的资源名，按 `string` / `plurals` 分组。 */
internal object ReferenceScan {
    private val REF = Regex("\\bR\\.(string|plurals)\\.([A-Za-z0-9_]+)")

    fun references(files: List<File>): Map<String, Set<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        files.forEach { file ->
            REF.findAll(file.readText()).forEach { match ->
                out.getOrPut(match.groupValues[1]) { LinkedHashSet() }.add(match.groupValues[2])
            }
        }
        return out
    }
}
