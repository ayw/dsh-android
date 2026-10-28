package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.SyncOwnerRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val gateTuning = SyncTuning.DEFAULT

/** 八个整数能力全开；测试用 override 逐个关掉。 */
private val enabledCapabilities: Map<String, Int> = mapOf(
    "wsTicket" to 1,
    "eventPolling" to 1,
    "eventEpoch" to 1,
    "operations" to 1,
    "workspaceFiles" to 1,
    "resumableUploads" to 1,
    "fileInbox" to 1,
    "imagePromptTransport" to 1,
)

private fun gateCapabilitiesOf(raw: Map<String, Int>): BackendCapabilities {
    fun level(name: String): Int = raw[name] ?: 0
    return BackendCapabilities(
        wsTicket = level("wsTicket"),
        eventPolling = level("eventPolling"),
        eventEpoch = level("eventEpoch"),
        operations = level("operations"),
        workspaceFiles = level("workspaceFiles"),
        resumableUploads = level("resumableUploads"),
        fileInbox = level("fileInbox"),
        imagePromptTransport = level("imagePromptTransport"),
        raw = raw,
    )
}

private fun gateCapabilities(vararg overrides: Pair<String, Int>): BackendCapabilities {
    val raw = LinkedHashMap<String, Int>()
    enabledCapabilities.forEach { (key, value) -> raw[key] = value }
    overrides.forEach { (key, value) -> raw[key] = value }
    return gateCapabilitiesOf(raw)
}

private data class GateCase(
    val capability: String,
    val decision: (CapabilityGates) -> CapabilityDecision,
)

/**
 * `/health` 的能力矩阵（G08）：缺失键 = 0 = 隐藏功能；每个整数 `>= 1` 才放行；
 * 角色与 tuning 只解释“为什么被挡”，不改变能力本身。
 */
class CapabilityGateTest {

    private val allGates = listOf(
        GateCase("wsTicket") { it.websocket },
        GateCase("eventPolling") { it.backgroundSync },
        GateCase("eventEpoch") { it.epochTracking },
        GateCase("operations") { it.receipts },
        GateCase("workspaceFiles") { it.files },
        GateCase("resumableUploads") { it.resumableUploads },
        GateCase("fileInbox") { it.fileInbox },
        GateCase("imagePromptTransport") { it.imagePrompt },
    )

    @Test
    fun capabilityUnknownKeysBehaveAsZeroAndHideTheFeature() {
        // 只有 eventPolling 在场：其余键缺失即 0，隐藏而不崩溃。
        val sparse = gateCapabilitiesOf(mapOf("eventPolling" to 1, "futureFeature" to 9))
        val gates = CapabilityGate.gates(sparse, gateTuning)

        assertTrue(gates.backgroundSyncAllowed)
        assertEquals(listOf("backgroundSync"), gates.allowedNames)
        listOf(
            gates.websocket,
            gates.epochTracking,
            gates.receipts,
            gates.files,
            gates.resumableUploads,
            gates.fileInbox,
            gates.imagePrompt,
        ).forEach { assertEquals(CapabilityBlock.CapabilityAbsent, it.blockedBy) }

        // 完全空表：八项全部隐藏，且没有任何异常。
        val empty = CapabilityGate.gates(gateCapabilitiesOf(emptyMap()), gateTuning)
        assertTrue(empty.allowedNames.isEmpty())
        assertEquals(0, CapabilityGate.level(gateCapabilitiesOf(emptyMap()), "eventPolling"))
    }

    @Test
    fun capabilityEachIntegerGateFlipsAtOne() {
        assertEquals(8, allGates.size)
        assertEquals(CapabilityGate.capabilityNames.toSet(), allGates.map { it.capability }.toSet())

        allGates.forEach { case ->
            val atZero = case.decision(CapabilityGate.gates(gateCapabilities(case.capability to 0), gateTuning))
            assertEquals(false, atZero.allowed, case.capability)
            assertEquals(CapabilityBlock.CapabilityAbsent, atZero.blockedBy, case.capability)

            assertTrue(
                case.decision(CapabilityGate.gates(gateCapabilities(case.capability to 1), gateTuning)).allowed,
                "${case.capability}=1 must allow the gate",
            )
            assertTrue(
                case.decision(CapabilityGate.gates(gateCapabilities(case.capability to 2), gateTuning)).allowed,
                "${case.capability}=2 must allow the gate",
            )
        }
    }

    @Test
    fun capabilityOperationsZeroDisablesResolveRetries() {
        val without = CapabilityGate.gates(gateCapabilities("operations" to 0), gateTuning)
        val with = CapabilityGate.gates(gateCapabilities("operations" to 1), gateTuning)

        assertFalse(without.resolveRetriesAllowed)
        assertFalse(without.receipts.allowed)
        assertEquals(CapabilityBlock.CapabilityAbsent, without.receipts.blockedBy)
        assertFalse(without.permitsEdge(PendingEdgeSource.LocalSubmit))
        assertTrue(without.permitsEdge(PendingEdgeSource.ServerSettled))

        assertTrue(with.resolveRetriesAllowed)
        assertTrue(with.receipts.allowed)
        assertTrue(with.permitsEdge(PendingEdgeSource.LocalSubmit))
    }

    @Test
    fun capabilityMissingEventPollingDisablesBackgroundSync() {
        // 键根本不出现（不是值为 0）：parsed 字段也按 0 落，后台同步必须关。
        val raw = LinkedHashMap(enabledCapabilities).apply { remove("eventPolling") }
        val sparse = gateCapabilitiesOf(raw)
        val gates = CapabilityGate.gates(sparse, gateTuning)

        assertFalse(gates.backgroundSyncAllowed)
        assertEquals(CapabilityBlock.CapabilityAbsent, gates.backgroundSync.blockedBy)
        assertEquals(0, CapabilityGate.level(sparse, "eventPolling"))
        assertFalse(gates.allowedNames.contains("backgroundSync"))
        // 传输类能力不受连累。
        assertTrue(gates.websocketAllowed)
        assertTrue(gates.receiptsAllowed)
        assertTrue(gates.filesAllowed)
    }

    @Test
    fun capabilityOwnerRoleNoneBlocksBackgroundSyncOnly() {
        val caps = gateCapabilities()
        val foreground = CapabilityGate.gates(caps, gateTuning, SyncOwnerRole.Foreground)
        val worker = CapabilityGate.gates(caps, gateTuning, SyncOwnerRole.Worker)
        val none = CapabilityGate.gates(caps, gateTuning, SyncOwnerRole.None)

        assertTrue(foreground.backgroundSyncAllowed)
        assertTrue(worker.backgroundSyncAllowed)
        assertFalse(none.backgroundSyncAllowed)
        assertEquals(CapabilityBlock.RoleNotAllowed, none.backgroundSync.blockedBy)

        // 其余七项是传输属性，与 owner 角色无关。
        assertEquals(foreground.websocket, none.websocket)
        assertEquals(foreground.epochTracking, none.epochTracking)
        assertEquals(foreground.receipts, none.receipts)
        assertEquals(foreground.files, none.files)
        assertEquals(foreground.resumableUploads, none.resumableUploads)
        assertEquals(foreground.fileInbox, none.fileInbox)
        assertEquals(foreground.imagePrompt, none.imagePrompt)
        assertEquals(
            foreground.allowedNames.filterNot { it == "backgroundSync" },
            none.allowedNames,
        )

        // 角色不掩盖“后端没实现”：缺 eventPolling 时仍是 CapabilityAbsent。
        val absent = CapabilityGate.gates(gateCapabilities("eventPolling" to 0), gateTuning, SyncOwnerRole.None)
        assertEquals(CapabilityBlock.CapabilityAbsent, absent.backgroundSync.blockedBy)
    }

    @Test
    fun capabilityTuningDisabledExplainsZeroOrNegativeTuning() {
        listOf(0L, -1L).forEach { value ->
            val background = CapabilityGate.gates(gateCapabilities(), SyncTuning(pollWaitMsWorker = value))
            assertEquals(CapabilityBlock.TuningDisabled, background.backgroundSync.blockedBy, "pollWaitMsWorker=$value")
            assertFalse(background.backgroundSyncAllowed)
            assertTrue(background.websocketAllowed)

            val upload = CapabilityGate.gates(gateCapabilities(), SyncTuning(uploadChunkBytes = value.toInt()))
            assertEquals(CapabilityBlock.TuningDisabled, upload.resumableUploads.blockedBy, "uploadChunkBytes=$value")
            assertTrue(upload.filesAllowed)

            val image = CapabilityGate.gates(gateCapabilities(), SyncTuning(imagePromptMaxBytes = value))
            assertEquals(CapabilityBlock.TuningDisabled, image.imagePrompt.blockedBy, "imagePromptMaxBytes=$value")
        }

        // 后端缺失优先于 tuning 关闭：原因必须是 CapabilityAbsent。
        val absentAndDisabled = CapabilityGate.gates(
            gateCapabilities("eventPolling" to 0),
            SyncTuning(pollWaitMsWorker = 0),
        )
        assertEquals(CapabilityBlock.CapabilityAbsent, absentAndDisabled.backgroundSync.blockedBy)
    }

    @Test
    fun capabilityUnknownCapabilitiesListsOnlyUnrecognisedKeys() {
        val caps = gateCapabilities("zetaFuture" to 1, "alphaFuture" to 0)

        assertEquals(listOf("alphaFuture", "zetaFuture"), CapabilityGate.unknownCapabilities(caps))
        assertTrue(CapabilityGate.unknownCapabilities(gateCapabilities()).isEmpty())
        assertEquals(0, CapabilityGate.level(gateCapabilities(), "alphaFuture"))
        assertEquals(1, CapabilityGate.level(caps, "zetaFuture"))
        // `unknown` 问的是“服务器有没有发布这个键”，不是“客户端认不认识”（后者是 unknownCapabilities）。
        assertFalse(caps.unknown("wsTicket"))
        assertFalse(caps.unknown("alphaFuture"), "alphaFuture 发布过，只是客户端不认识")
        assertTrue(caps.unknown("absentKey"), "缺失键 = 未发布")
        // 未知键不打开也不关闭任何功能：八项已知能力仍按各自的值判定。
        assertEquals(
            listOf("websocket", "backgroundSync", "epochTracking", "receipts", "files", "resumableUploads", "fileInbox", "imagePrompt"),
            CapabilityGate.gates(caps, gateTuning).allowedNames,
        )
    }
}
