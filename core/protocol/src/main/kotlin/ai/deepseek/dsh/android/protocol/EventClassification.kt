package ai.deepseek.dsh.android.protocol

import ai.deepseek.dsh.android.model.DurableEvent

/**
 * How a wire `type` the client meets is treated. This is the D08/F16 rule turned into one testable
 * table; the projector never sees a raw string it has to re-classify.
 *
 *  - [PROJECTED]      a registered type that decodes to a real [DurableEvent] case.
 *  - [WITHOUT_LOG]    a known catalog type whose loss cannot affect reconstruction and that is pure
 *                     noise to a mobile client: dropped with no counter and no log line.
 *  - [WITH_COUNTER]   a type we do not project but must still count as "unsupported" — an unrecognized
 *                     type that arrived `ignorable == true`, or a catalog type the client may one day
 *                     want to surface. Advances the frontier, bumps the counter, produces no entry.
 *  - [SURFACE]        an unrecognized type that arrived WITHOUT `ignorable == true`. Per F16 its absence
 *                     means "required": the log cannot be safely reconstructed, so the session is marked
 *                     `historyUnsafe` and a hard boundary is shown. This must surface in diagnostics.
 *
 * `ignorable` on [DurableEvent.Unknown] is the wire marker; [tierOf] is the client's classification and
 * is what the diagnostics counter and the `historyUnsafe` flag are driven from.
 */
enum class IgnoreTier { PROJECTED, WITHOUT_LOG, WITH_COUNTER, SURFACE }

/**
 * The known-case + unknown-ignorable table. Membership here is the *only* place a wire event-type
 * literal is enumerated for projection decisions.
 */
object EventClassification {

    /** The wire `type` strings that decode to a [DurableEvent]. This is the known-case table size. */
    val PROJECTED_TYPES: Set<String> = setOf(
        "user/message",
        "assistant/message",
        "assistant/attempt",
        "tool/call",
        "tool/result",
        "turn/start",
        "turn/end",
        "step/start",
        "step/end",
        "approval/asked",
        "approval/requested",
        "approval/decided",
        "approval/resolved",
        "question/requested",
        "question/resolved",
        "compaction/start",
        "compaction/end",
        "compaction/summary",
        "session/end-seed",
        "system/message",
        "developer/message",
        "model/selection",
        "goal/change",
        "working-directory/change",
        "session/title",
    )

    /**
     * Known catalog types that are log-only / control-plane noise for a read-mostly mobile client.
     * They advance the cursor and are silently skipped (WITHOUT_LOG) — a newer harness adding one of
     * these must not raise an "unsupported event" false alarm.
     */
    val SILENT_TYPES: Set<String> = setOf(
        "request/header",
        "request/context",
        "llm/retry",
        "llm/retry-started",
        "permission/preset",
        "plan/mode",
        "sandbox/mode",
        "approval/policy",
        "agent-preset/selected",
        "session/title-llm-request",
        "web/deepseek-search-llm-request",
        "image/offload",
        "feedback/record",
        "feedback/message-put",
        "feedback/message-delete",
        // Gateway-synthesized channel frames (gateway.js legacyPush): subscription bookkeeping,
        // composer/job state and streaming drafts. They are not durable history rows, so treating
        // them as unknown would flag a healthy session as `historyUnsafe`.
        "session/subscribed",
        "session/projection",
        "session/queue",
        "session/jobs",
        "session/reasoning",
        "session/stream-error",
        "stream/error",
    )

    /**
     * Prefix-silenced families. Rows under the host namespace are roster/host control-plane signals,
     * not session history; they are consumed (if at all) by the sync actor before projection.
     */
    private val SILENT_PREFIXES: List<String> = listOf("host/")

    /**
     * The rest of the recorded catalog (the `subagent-`, `team-`, `tool-workflow-`, `hook-`, `job-`
     * families, `todo/write`, `workspace/changes`, `compaction/prune`, `schedule/change`,
     * `deliverables/presented`, the `command-` family, `session-log-deepseek-` ...), plus plugin
     * open-namespaced types the client has not registered. These are counted as unsupported but are
     * safe to skip; they are `WITH_COUNTER`.
     *
     * No set is needed for them: any type outside [PROJECTED_TYPES] and [SILENT_TYPES] that is not
     * explicitly recognized is treated per the unknown rule in [tierOf].
     */
    fun tierOf(type: String, ignorable: Boolean): IgnoreTier = when {
        type in PROJECTED_TYPES -> IgnoreTier.PROJECTED
        type in SILENT_TYPES -> IgnoreTier.WITHOUT_LOG
        SILENT_PREFIXES.any { type.startsWith(it) } -> IgnoreTier.WITHOUT_LOG
        // Known-but-unprojected catalog entries and genuinely unknown types both land here.
        ignorable -> IgnoreTier.WITH_COUNTER
        else -> IgnoreTier.SURFACE
    }
}
