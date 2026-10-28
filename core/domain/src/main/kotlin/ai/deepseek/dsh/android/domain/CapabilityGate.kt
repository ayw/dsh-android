package ai.deepseek.dsh.android.domain

import ai.deepseek.dsh.android.model.BackendCapabilities
import ai.deepseek.dsh.android.model.SyncOwnerRole

/** 单个能力开关：`allowed` 之外还给出被挡下的原因，UI 才能区分“后端没实现”与“客户端没开”。 */
data class CapabilityDecision(val allowed: Boolean, val blockedBy: CapabilityBlock?)

enum class CapabilityBlock { CapabilityAbsent, RoleNotAllowed, TuningDisabled }

/**
 * 后台同步的硬要求只有 `eventPolling>=1`；WS 取票、epoch、回执、文件与图片各自独立协商，
 * 不做单一版本号分支。能力键缺失按 0 处理，因此未知能力一律隐藏功能而不是崩溃。
 */
data class CapabilityGates(
    val websocket: CapabilityDecision,
    val backgroundSync: CapabilityDecision,
    val epochTracking: CapabilityDecision,
    val receipts: CapabilityDecision,
    val files: CapabilityDecision,
    val resumableUploads: CapabilityDecision,
    val fileInbox: CapabilityDecision,
    val imagePrompt: CapabilityDecision,
) {
    val allowedNames: List<String>
        get() = listOf(
            websocket to "websocket",
            backgroundSync to "backgroundSync",
            epochTracking to "epochTracking",
            receipts to "receipts",
            files to "files",
            resumableUploads to "resumableUploads",
            fileInbox to "fileInbox",
            imagePrompt to "imagePrompt",
        ).filter { it.first.allowed }.map { it.second }

    val allowedSet: Set<String> get() = allowedNames.toSet()

    val backgroundSyncAllowed: Boolean get() = backgroundSync.allowed
    val websocketAllowed: Boolean get() = websocket.allowed
    val epochTrackingAllowed: Boolean get() = epochTracking.allowed
    val receiptsAllowed: Boolean get() = receipts.allowed
    val filesAllowed: Boolean get() = files.allowed
    val resumableUploadsAllowed: Boolean get() = resumableUploads.allowed
    val fileInboxAllowed: Boolean get() = fileInbox.allowed
    val imagePromptAllowed: Boolean get() = imagePrompt.allowed

    /** `operations=0` 时 accepted 之后只能显示 unknown 且没有自动重试。 */
    val resolveRetriesAllowed: Boolean get() = receipts.allowed

    fun permitsEdge(source: PendingEdgeSource): Boolean = when (source) {
        PendingEdgeSource.LocalSubmit -> receipts.allowed
        else -> true
    }
}

object CapabilityGate {

    /** `/health` 能力表的键名，与网关发布字段逐字一致。 */
    val capabilityNames: List<String> = listOf(
        "wsTicket",
        "eventPolling",
        "eventEpoch",
        "operations",
        "workspaceFiles",
        "resumableUploads",
        "fileInbox",
        "imagePromptTransport",
    )

    fun gates(capabilities: BackendCapabilities, tuning: SyncTuning): CapabilityGates {
        val wsTicket = level(capabilities, "wsTicket", capabilities.wsTicket)
        val eventPolling = level(capabilities, "eventPolling", capabilities.eventPolling)
        val eventEpoch = level(capabilities, "eventEpoch", capabilities.eventEpoch)
        val operations = level(capabilities, "operations", capabilities.operations)
        val workspaceFiles = level(capabilities, "workspaceFiles", capabilities.workspaceFiles)
        val resumableUploads = level(capabilities, "resumableUploads", capabilities.resumableUploads)
        val fileInbox = level(capabilities, "fileInbox", capabilities.fileInbox)
        val imagePromptTransport = level(capabilities, "imagePromptTransport", capabilities.imagePromptTransport)
        return CapabilityGates(
            websocket = decided(wsTicket >= minimalLevel),
            backgroundSync = decided(
                eventPolling >= minimalLevel,
                tuningBlocked = tuning.pollWaitMsWorker <= 0L,
            ),
            epochTracking = decided(eventEpoch >= minimalLevel),
            receipts = decided(operations >= minimalLevel),
            files = decided(workspaceFiles >= minimalLevel),
            resumableUploads = decided(
                resumableUploads >= minimalLevel && workspaceFiles >= minimalLevel,
                tuningBlocked = tuning.uploadChunkBytes <= 0,
            ),
            fileInbox = decided(fileInbox >= minimalLevel && workspaceFiles >= minimalLevel),
            imagePrompt = decided(
                imagePromptTransport >= minimalLevel,
                tuningBlocked = tuning.imagePromptMaxBytes <= 0L,
            ),
        )
    }

    /**
     * 角色维度只影响“谁来跑后台同步”：没有所有者角色时周期同步无意义，其余能力是传输属性，
     * 与前台/Worker 无关（§4.5 的八项整数各自独立协商）。
     */
    fun gates(
        capabilities: BackendCapabilities,
        tuning: SyncTuning,
        role: SyncOwnerRole,
    ): CapabilityGates {
        val base = gates(capabilities, tuning)
        return CapabilityGates(
            websocket = base.websocket,
            backgroundSync = roleAllowed(base.backgroundSync, role),
            epochTracking = base.epochTracking,
            receipts = base.receipts,
            files = base.files,
            resumableUploads = base.resumableUploads,
            fileInbox = base.fileInbox,
            imagePrompt = base.imagePrompt,
        )
    }

    /** 能力键缺失按 0：未知能力不猜测语义，功能一律隐藏。 */
    fun level(capabilities: BackendCapabilities, name: String): Int = capabilities.raw[name] ?: 0

    fun unknownCapabilities(capabilities: BackendCapabilities): List<String> =
        capabilities.raw.keys.filterNot { it in capabilityNames }.sorted()

    /** 能力整数的最低可用值：`>= 1` 才算后端实现了该能力。 */
    const val minimalLevel: Int = 1

    private fun level(capabilities: BackendCapabilities, name: String, parsed: Int): Int =
        capabilities.raw[name] ?: parsed

    private fun decided(
        capabilityAllowed: Boolean,
        tuningBlocked: Boolean = false,
    ): CapabilityDecision = when {
        !capabilityAllowed -> CapabilityDecision(false, CapabilityBlock.CapabilityAbsent)
        tuningBlocked -> CapabilityDecision(false, CapabilityBlock.TuningDisabled)
        else -> CapabilityDecision(true, null)
    }

    private fun roleAllowed(decision: CapabilityDecision, role: SyncOwnerRole): CapabilityDecision =
        if (decision.allowed && role == SyncOwnerRole.None) {
            CapabilityDecision(false, CapabilityBlock.RoleNotAllowed)
        } else {
            decision
        }
}
