package ai.deepseek.dsh.android.designsystem.timeline

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.components.ErrorState
import ai.deepseek.dsh.android.designsystem.components.StatusChip
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.DshTone
import ai.deepseek.dsh.android.designsystem.theme.LocalDshShapes
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.statusFaint
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.TimelineItem

/** 提交相位。v0 只有 `accepted:true|false` 两分支（F5），超时或无回执落 [Unknown]。 */
enum class SubmitPhase {
    Idle,
    Submitting,
    Accepted,
    Stale,
    Unknown,
}

/** 表单里的一个可选项；[label] 必须由特性模块从 `:core:designsystem` 的字符串资源取（G14）。 */
data class PendingOption(
    val id: String,
    val label: String,
)

/**
 * 提问的一个字段。`ask_user_question` 的 `questionsJson` 由特性模块解码后传入
 * （本模块不解析 JSON，也不判断题目数量）。
 */
data class PendingField(
    val id: String,
    val label: String,
    val options: List<PendingOption> = emptyList(),
    val multiSelect: Boolean = false,
    val allowFreeText: Boolean = true,
    val required: Boolean = true,
    val placeholder: String? = null,
)

/** 卡片可视内容。[title] 是解码后的 payload 文本；解码失败时传 null，卡片仍渲染身份与状态。 */
data class PendingAnchorContent(
    val title: String,
    val detail: String? = null,
    val argumentsPreview: String? = null,
    val fields: List<PendingField> = emptyList(),
    val decisionOptions: List<PendingOption> = emptyList(),
)

/** 回传给特性模块的动作；身份用模型的 [RequestId]（D13：approval 取 `data.id`，question 取 `callId`）。 */
sealed interface PendingDecision {
    data class Approval(val requestId: RequestId, val allowed: Boolean) : PendingDecision
    data class Answer(val requestId: RequestId, val answers: Map<String, List<String>>) : PendingDecision
}

/**
 * 待处理锚点卡片（G07 / 计划 §8.3）。审批与提问共用外壳，按 [PendingKind] 分支。
 *
 * 诚实性写死在组件里，特性模块无法绕过：
 * - 底部固定说明“批准/拒绝要等下一次同步才生效，这里不是实时推送”（计划 §10 已知限制）；
 * - [SubmitPhase.Unknown] 显示“结果未知”，不显示假 0，也不自动重发（D13）；
 * - [SubmitPhase.Stale] 不出现重试按钮（`accepted:false` 不重试，F5）；
 * - [needsSecondConfirm] 用于全局收件箱的二次确认（计划 §8.3）；
 * - [sessionAvailable] 为 false 时说明会话当前不可用但仍可提交。
 *
 * @param submitting 禁用全部提交控件并显示进度
 * @param errorRes 只能传字符串资源 id，异常正文一律不进 UI（G09.2 / G14）
 */
@Composable
fun PendingAnchorCard(
    item: TimelineItem.PendingAnchor,
    content: PendingAnchorContent?,
    phase: SubmitPhase,
    modifier: Modifier = Modifier,
    submitting: Boolean = false,
    @StringRes errorRes: Int? = null,
    needsSecondConfirm: Boolean = false,
    sessionAvailable: Boolean = true,
    onDecision: ((PendingDecision) -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val type = LocalDshType.current
    val shapes = LocalDshShapes.current
    val isApproval = item.kind == PendingKind.Approval
    val canSubmit = onDecision != null &&
        !submitting &&
        phase != SubmitPhase.Accepted &&
        phase != SubmitPhase.Stale

    var selected by remember(item.requestId.value, content) { mutableStateOf(mapOf<String, List<String>>()) }
    var freeText by remember(item.requestId.value, content) { mutableStateOf(mapOf<String, String>()) }
    var confirmed by remember(item.requestId.value) { mutableStateOf(!needsSecondConfirm) }

    Surface(
        modifier = modifier
            .widthIn(max = DshDimens.ContentMaxWidth)
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        shape = shapes.card,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(DshSpacing.md),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (isApproval) R.string.pending_kind_approval else R.string.pending_kind_question,
                    ),
                    modifier = Modifier.weight(1f, fill = false),
                    style = type.label,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StatusChip(label = pendingStatusLabel(phase), tone = pendingStatusTone(phase))
            }

            Text(
                text = content?.title ?: stringResource(R.string.pending_undecodable_title),
                style = type.body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val detail = content?.detail
            if (detail != null) {
                Text(text = detail, style = type.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val preview = content?.argumentsPreview
            if (preview != null) {
                Text(
                    text = stringResource(R.string.conversation_tool_arguments),
                    style = type.meta,
                    color = statusFaint(),
                )
                Text(
                    text = preview,
                    style = type.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.anchoredToCallId != null) {
                Text(
                    text = stringResource(R.string.pending_anchored_to_tool),
                    style = type.meta,
                    color = statusFaint(),
                )
            }
            if (!sessionAvailable) {
                Text(
                    text = stringResource(R.string.pending_session_unavailable),
                    style = type.meta,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            val fields = content?.fields.orEmpty()
            if (isApproval) {
                ApprovalButtons(
                    options = content?.decisionOptions.orEmpty(),
                    enabled = canSubmit && confirmed,
                    requestId = item.requestId,
                    onDecision = onDecision,
                )
                if (needsSecondConfirm) {
                    Row(
                        modifier = Modifier
                            .defaultMinSize(minHeight = DshDimens.TouchTarget)
                            .clickable { confirmed = !confirmed },
                        horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                        Text(
                            text = stringResource(R.string.pending_second_confirm),
                            style = type.meta,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else if (fields.isEmpty()) {
                Text(
                    text = stringResource(R.string.pending_question_fields_unavailable),
                    style = type.meta,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                fields.forEach { field ->
                    QuestionFieldRow(
                        field = field,
                        enabled = canSubmit,
                        selected = selected[field.id].orEmpty(),
                        freeTextValue = freeText[field.id].orEmpty(),
                        onSelect = { values -> selected = selected + (field.id to values) },
                        onFreeText = { text -> freeText = freeText + (field.id to text) },
                    )
                }
                val submitLabel = stringResource(
                    if (phase == SubmitPhase.Submitting) R.string.pending_submitting else R.string.pending_submit,
                )
                TextButton(
                    onClick = {
                        onDecision?.invoke(
                            PendingDecision.Answer(
                                requestId = item.requestId,
                                answers = buildAnswers(fields, selected, freeText),
                            ),
                        )
                    },
                    enabled = canSubmit,
                    modifier = Modifier.defaultMinSize(minHeight = DshDimens.TouchTarget),
                ) {
                    Text(text = submitLabel, style = type.label)
                }
            }

            if (phase == SubmitPhase.Submitting || submitting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (phase == SubmitPhase.Stale) {
                // accepted:false -> STALE：刻意不提供重试（D13 / F5）。
                Text(
                    text = stringResource(R.string.pending_stale_no_retry),
                    style = type.meta,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (errorRes != null) {
                ErrorState(reasonRes = errorRes)
            }

            Text(
                text = stringResource(R.string.pending_wait_next_sync),
                style = type.meta,
                color = statusFaint(),
            )

            if (onDismiss != null) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = DshDimens.TouchTarget),
                ) {
                    Text(text = stringResource(R.string.pending_dismiss), style = type.label)
                }
            }
        }
    }
}

private fun buildAnswers(
    fields: List<PendingField>,
    selected: Map<String, List<String>>,
    freeText: Map<String, String>,
): Map<String, List<String>> {
    val out = LinkedHashMap<String, List<String>>()
    fields.forEach { field ->
        val values = selected[field.id].orEmpty() + listOfNotNull(freeText[field.id]?.takeIf { it.isNotEmpty() })
        if (values.isNotEmpty()) out[field.id] = values
    }
    return out
}

@Composable
private fun ApprovalButtons(
    options: List<PendingOption>,
    enabled: Boolean,
    requestId: RequestId,
    onDecision: ((PendingDecision) -> Unit)?,
) {
    val type = LocalDshType.current
    if (options.isEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm)) {
            TextButton(
                onClick = { onDecision?.invoke(PendingDecision.Approval(requestId, allowed = true)) },
                enabled = enabled,
                modifier = Modifier.defaultMinSize(minHeight = DshDimens.TouchTarget),
            ) {
                Text(text = stringResource(R.string.pending_allow_once), style = type.label)
            }
            OutlinedButton(
                onClick = { onDecision?.invoke(PendingDecision.Approval(requestId, allowed = false)) },
                enabled = enabled,
                modifier = Modifier.defaultMinSize(minHeight = DshDimens.TouchTarget),
            ) {
                Text(text = stringResource(R.string.pending_reject), style = type.label)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
            options.forEach { option ->
                TextButton(
                    onClick = {
                        onDecision?.invoke(
                            PendingDecision.Approval(
                                requestId = requestId,
                                allowed = option.id == APPROVAL_ALLOW_ONCE,
                            ),
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .defaultMinSize(minHeight = DshDimens.TouchTarget)
                        .fillMaxWidth(),
                ) {
                    Text(text = option.label, style = type.label)
                }
            }
        }
    }
}

/** 审批结论的 wire 值之一（F8：`allowed-once | rejected | cancelled | unavailable`）。 */
private const val APPROVAL_ALLOW_ONCE = "allowed-once"

@Composable
private fun QuestionFieldRow(
    field: PendingField,
    enabled: Boolean,
    selected: List<String>,
    freeTextValue: String,
    onSelect: (List<String>) -> Unit,
    onFreeText: (String) -> Unit,
) {
    val type = LocalDshType.current
    val placeholderText = field.placeholder
    val placeholderSlot: (@Composable () -> Unit)? = if (placeholderText == null) {
        null
    } else {
        @Composable { Text(text = placeholderText, style = type.meta) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
            Text(
                text = field.label,
                style = type.label,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (field.required) {
                Text(
                    text = stringResource(R.string.pending_required),
                    style = type.meta,
                    color = statusFaint(),
                )
            }
        }
        field.options.forEach { option ->
            val chosen = selected.contains(option.id)
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = DshDimens.TouchTarget)
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        val next = when {
                            !field.multiSelect -> listOf(option.id)
                            chosen -> selected - option.id
                            else -> selected + option.id
                        }
                        onSelect(next)
                    },
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = chosen, onCheckedChange = null, enabled = enabled)
                Text(
                    text = option.label,
                    style = type.body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (field.allowFreeText) {
            OutlinedTextField(
                value = freeTextValue,
                onValueChange = onFreeText,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text(text = stringResource(R.string.pending_free_text_label), style = type.meta) },
                placeholder = placeholderSlot,
            )
        }
    }
}

@Composable
internal fun pendingStatusLabel(phase: SubmitPhase): String = stringResource(
    when (phase) {
        SubmitPhase.Idle -> R.string.pending_waiting
        SubmitPhase.Submitting -> R.string.pending_submitting
        SubmitPhase.Accepted -> R.string.pending_resolved
        SubmitPhase.Stale -> R.string.pending_stale
        SubmitPhase.Unknown -> R.string.pending_unknown_result
    },
)

private fun pendingStatusTone(phase: SubmitPhase): DshTone = when (phase) {
    SubmitPhase.Idle -> DshTone.Warning
    SubmitPhase.Submitting -> DshTone.Info
    SubmitPhase.Accepted -> DshTone.Positive
    SubmitPhase.Stale -> DshTone.Neutral
    SubmitPhase.Unknown -> DshTone.Neutral
}
