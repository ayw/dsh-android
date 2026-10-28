package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.AuthState
import ai.deepseek.dsh.android.model.DiagnosticsSnapshot
import ai.deepseek.dsh.android.model.PendingCompleteness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G10：诊断快照的字段集是验收契约的一部分，删字段/改名必须让这里先红。
 * 用 Java 反射而不是 kotlin-reflect：`declaredFields` 对 public data class 属性稳定，
 * 且不需要额外依赖。
 */
class DiagnosticsSnapshotTest {

    private val requiredFields = setOf(
        "transportCursorPerChannel",
        "contiguousSeqLag",
        "needsResyncCount",
        "historyIncompleteSessions",
        "historyUnsafeSessions",
        "pendingCompleteness",
        "baselineSweepSkipped",
        "authState",
        "lastPollWaitSupported",
        "retentionTrimmedCount",
        "badJsonCount",
        "wsDowngradeCount",
        "identityRetiredPendingPurge",
    )

    @Test
    fun `every required diagnostics field is present on the snapshot`() {
        val declared = DiagnosticsSnapshot::class.java.declaredFields.map { it.name }.toSet()
        val missing = requiredFields - declared
        assertTrue(missing.isEmpty(), "missing diagnostics fields: $missing")
    }

    @Test
    fun `snapshot also carries runtime-only facts the ui must be able to show`() {
        val declared = DiagnosticsSnapshot::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("rosterAgeMs" in declared, "rosterAgeMs is needed to explain roster staleness")
        assertTrue("capabilities" in declared, "capabilities drive the degraded capability facts")
    }

    @Test
    fun `clean snapshot is not degraded`() {
        assertFalse(clean().degraded)
    }

    @Test
    fun `every degraded trigger flips the predicate`() {
        val triggers = listOf(
            "needsResyncCount" to clean().copy(needsResyncCount = 1),
            "historyIncompleteSessions" to clean().copy(historyIncompleteSessions = 1),
            "historyUnsafeSessions" to clean().copy(historyUnsafeSessions = 1),
            "baselineSweepSkipped" to clean().copy(baselineSweepSkipped = true),
            "lastPollWaitSupported" to clean().copy(lastPollWaitSupported = false),
        )
        for ((name, snapshot) in triggers) {
            assertTrue(snapshot.degraded, "$name must mark the snapshot degraded")
        }
    }

    @Test
    fun `auth facts are reported verbatim and never folded into the degraded predicate`() {
        // 认证态是事实报告；degraded 只描述投影健康度。两者混在一起会让离线时误报“历史可能不完整”。
        for (state in AuthState.entries) {
            val snapshot = clean().copy(authState = state)
            assertEquals(state, snapshot.authState)
            assertFalse(snapshot.degraded, "$state alone must not flip degraded")
        }
    }

    @Test
    fun `pending completeness is carried verbatim so the ui can show unknown`() {
        assertEquals(
            PendingCompleteness.Unknown,
            clean().copy(pendingCompleteness = PendingCompleteness.Unknown).pendingCompleteness,
        )
    }

    private fun clean() = DiagnosticsSnapshot(
        transportCursorPerChannel = emptyMap(),
        contiguousSeqLag = emptyMap(),
        needsResyncCount = 0,
        historyIncompleteSessions = 0,
        historyUnsafeSessions = 0,
        pendingCompleteness = PendingCompleteness.Complete,
        baselineSweepSkipped = false,
        authState = AuthState.Connected,
        lastPollWaitSupported = true,
        retentionTrimmedCount = 0,
        badJsonCount = 0,
        wsDowngradeCount = 0,
        identityRetiredPendingPurge = 0,
        rosterAgeMs = null,
        capabilities = null,
    )
}
