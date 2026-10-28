package ai.deepseek.dsh.android.debug

/**
 * debug 源集独有。release 构建的 APK 里不应出现 [CANARY]，G11 用字符串扫描验证构建型态隔离。
 */
object Canary {
    const val CANARY = "DSH-CANARY-DEBUG-ONLY-4f2b9c"
}
