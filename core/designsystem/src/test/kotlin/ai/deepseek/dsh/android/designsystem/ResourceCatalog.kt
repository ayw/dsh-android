package ai.deepseek.dsh.android.designsystem

import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * 测试用的公共资源定位与解析工具。
 *
 * 纯 JVM：不碰 Android 运行时、不用 Robolectric、不依赖 kotlin-reflect。
 * Gradle 单测的工作目录可能是模块目录，也可能是仓库根，因此向上找；
 * 找不到就抛带上下文的异常（G14 的机检一旦失效必须显性失败，不能静默跳过）。
 */
internal object ResourceCatalog {

    /** `<string>` / `<plurals>` / `<string-array>` 的统一表示。 */
    data class Entry(val kind: String, val name: String, val quantities: Map<String, String>)

    data class CallSite(val kind: String, val name: String, val args: Int, val line: Int)

    fun moduleRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "src/main/res/values/strings.xml").isFile) return dir
            val nested = File(dir, "core/designsystem")
            if (File(nested, "src/main/res/values/strings.xml").isFile) return nested
            dir = dir.parentFile
        }
        error("cannot find :core:designsystem resource dir while walking up from $start")
    }

    fun resDir(root: File, locale: String): File = File(root, "src/main/res/$locale")

    /** 解析一个 strings*.xml；XML 错误直接抛出，让测试失败而不是跳过。 */
    fun parse(file: File): List<Entry> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        val document = builder.parse(file)
        val out = ArrayList<Entry>()
        val children = document.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            val name = element.getAttribute("name")
            if (name.isEmpty()) continue
            when (element.tagName) {
                "string" -> out.add(Entry("string", name, mapOf("" to textOf(element))))
                "plurals", "string-array" -> {
                    val attr = if (element.tagName == "plurals") "quantity" else "entry"
                    val items = LinkedHashMap<String, String>()
                    val kids = element.childNodes
                    for (j in 0 until kids.length) {
                        val kid = kids.item(j)
                        if (kid.nodeType != Node.ELEMENT_NODE) continue
                        val key = (kid as Element).getAttribute(attr)
                        if (key.isEmpty()) continue
                        items[key] = textOf(kid)
                    }
                    out.add(Entry(element.tagName, name, items))
                }
            }
        }
        return out
    }

    /**
     * 只取元素自身的文本，不递归子节点：`<string>` 里没有子元素，
     * `<item>` 同理；这样 `&lt;` 之类的转义仍按 XML 规则解好。
     */
    private fun textOf(element: Element): String {
        val builder = StringBuilder()
        val kids = element.childNodes
        for (i in 0 until kids.length) {
            val node = kids.item(i)
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                builder.append(node.nodeValue)
            }
        }
        return builder.toString().trim()
    }

    fun mainKotlinSources(root: File): List<File> {
        val dir = File(root, "src/main")
        if (!dir.isDirectory) error("missing main source dir $dir")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().sortedBy { it.path }
    }

    /** `%1$s` / `%s` / `%2$d` 之类占位符的完整集合。 */
    fun placeholders(text: String): Set<String> = PLACEHOLDER.findAll(text).map { it.value }.toSet()

    /** 最大占位符序号：`%1$s` -> 1；`%s` 按 1 计；无占位符 -> 0。 */
    fun maxPlaceholderIndex(text: String): Int {
        val indices = PLACEHOLDER.findAll(text).map { match ->
            // group(1) 含尾部的 `$`（如 "2$"），先剥掉再转数字。
            match.groupValues[1].removeSuffix("$").toIntOrNull() ?: 1
        }.toList()
        return indices.maxOrNull() ?: 0
    }

    /**
     * 扫描源码里的 `stringResource(R.string.x, ...)` / `pluralStringResource(R.plurals.x, n, ...)`
     * 调用点，返回每个调用实际传入的格式参数个数（已排除资源 id 与 plurals 的 quantity）。
     *
     * 实现是逐字符状态机：先剥掉注释（保持行号不变），再按顶层逗号切分，
     * 因此跨行实参、嵌套 lambda、字符串里的逗号都不会算错。
     */
    fun callSites(source: String): List<CallSite> {
        val code = stripComments(source)
        val out = ArrayList<CallSite>()
        CALL.findAll(code).forEach { match ->
            val fn = match.groupValues[1]
            val open = code.indexOf('(', match.range.first)
            if (open < 0) return@forEach
            val parts = splitTopLevel(code, open)
            if (parts.isEmpty()) return@forEach
            var args = parts.size - 1
            if (parts.last().isBlank()) args -= 1
            if (fn == "pluralStringResource") args -= 1
            if (args < 0) args = 0
            val line = code.substring(0, match.range.first).count { it == '\n' } + 1
            out.add(CallSite(match.groupValues[2], match.groupValues[3], args, line))
        }
        return out
    }

    /** 把行注释与块注释替换成等宽空格（保持行号与列位置不变），供源码扫描使用。 */
    fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inString = false
        var inChar = false
        var inLine = false
        var inBlock = false
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else ' '
            when {
                inLine -> {
                    out.append(if (c == '\n') c else ' ')
                    if (c == '\n') inLine = false
                }
                inBlock -> {
                    if (c == '*' && next == '/') {
                        out.append("  ")
                        inBlock = false
                        i += 2
                        continue
                    }
                    out.append(if (c == '\n') c else ' ')
                }
                inString -> {
                    out.append(c)
                    if (c == '\\') {
                        out.append(next)
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                }
                inChar -> {
                    out.append(c)
                    if (c == '\\') {
                        out.append(next)
                        i += 2
                        continue
                    }
                    if (c == '\'') inChar = false
                }
                c == '/' && next == '/' -> inLine = true
                c == '/' && next == '*' -> {
                    inBlock = true
                    i += 2
                    continue
                }
                else -> {
                    if (c == '"') inString = true
                    if (c == '\'') inChar = true
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    /** 从 [open]（下标指向 `(`）开始按顶层逗号切分实参，返回每段原文。 */
    private fun splitTopLevel(source: String, open: Int): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = open
        var i = open
        while (i < source.length) {
            val c = source[i]
            when (c) {
                '"' -> i = skipString(source, i)
                '\'' -> i = skipChar(source, i)
                '/' -> if (i + 1 < source.length && source[i + 1] == '/') {
                    while (i < source.length && source[i] != '\n') i++
                } else if (i + 1 < source.length && source[i + 1] == '*') {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 1
                } else i++
                '(', '[', '{' -> {
                    depth++
                    if (depth == 1) start = i + 1
                    i++
                }
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) {
                        parts.add(source.substring(start, i))
                        return parts
                    }
                    i++
                }
                ',' -> {
                    if (depth == 1) {
                        parts.add(source.substring(start, i))
                        start = i + 1
                    }
                    i++
                }
                else -> i++
            }
        }
        return parts
    }

    private fun skipString(source: String, from: Int): Int {
        var i = from + 1
        while (i < source.length) {
            val c = source[i]
            if (c == '\\') i++ else if (c == '"') return i + 1
            i++
        }
        return source.length
    }

    private fun skipChar(source: String, from: Int): Int {
        var i = from + 1
        while (i < source.length) {
            val c = source[i]
            if (c == '\\') i++ else if (c == '\'') return i + 1
            i++
        }
        return source.length
    }

    /** G14 的源码侧镜像：`Text("字面量")` 一律不允许（预览文件除外）。 */
    fun hardcodedTextLines(source: String, fileName: String): List<Pair<Int, String>> {
        if (fileName.contains("Preview")) return emptyList()
        val out = ArrayList<Pair<Int, String>>()
        source.lineSequence().forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
            val match = TEXT_LITERAL.find(line)
            if (match != null && match.groupValues[1].startsWith("\"") && match.groupValues[1] != "\"\"") {
                out.add(index + 1 to trimmed)
            }
        }
        return out
    }

    private val PLACEHOLDER = Regex("%(\\d+\\$)?[ds]")
    private val CALL = Regex("\\b(stringResource|pluralStringResource)\\s*\\(\\s*R\\.(string|plurals)\\.([A-Za-z0-9_]+)")
    private val TEXT_LITERAL = Regex("\\bText\\s*\\(\\s*(?:text\\s*=\\s*)?(\".*?\"|\\$\\{)")
}
