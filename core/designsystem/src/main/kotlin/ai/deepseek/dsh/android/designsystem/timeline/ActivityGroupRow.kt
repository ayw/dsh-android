package ai.deepseek.dsh.android.designsystem.timeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.deepseek.dsh.android.core.designsystem.R
import ai.deepseek.dsh.android.designsystem.components.DshGlyph
import ai.deepseek.dsh.android.designsystem.components.DshGlyphIcon
import ai.deepseek.dsh.android.designsystem.components.StatusChip
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.DshTone
import ai.deepseek.dsh.android.designsystem.theme.LocalDshShapes
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.statusFaint
import ai.deepseek.dsh.android.designsystem.theme.statusOutline
import ai.deepseek.dsh.android.designsystem.theme.toneColors
import ai.deepseek.dsh.android.model.ActivityEntry
import ai.deepseek.dsh.android.model.ToolEntry
import ai.deepseek.dsh.android.model.ToolStatus
import ai.deepseek.dsh.android.model.TimelineItem

/**
 * 活动组行：工具 + reasoning 的折叠组。
 *
 * **是否成组、组内状态优先级由投影器决定**（计划 §7.3），UI 只渲染已经成形的
 * [TimelineItem.ActivityGroup]，不在这里数条目、不合并、不判断 >=2 项。
 *
 * 展开状态由调用方持有（`rememberSaveable` + 组 key），滚动与返回后不丢（计划 §11）。
 */
@Composable
fun ActivityGroupRow(
    item: TimelineItem.ActivityGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = LocalDshType.current
    val shapes = LocalDshShapes.current
    val statusLabel = groupStatusLabel(item.status)
    val stepsLabel = pluralStringResource(
        R.plurals.conversation_group_steps,
        item.entries.size,
        item.entries.size,
    )
    val toggleLabel = stringResource(if (expanded) R.string.states_collapse else R.string.states_expand)

    Surface(
        modifier = modifier
            .widthIn(max = DshDimens.ContentMaxWidth)
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
        shape = shapes.card,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(DshDimens.Stroke, statusOutline()),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = DshDimens.TouchTarget)
                    .clickable(onClickLabel = toggleLabel, onClick = onToggle)
                    .padding(horizontal = DshSpacing.md, vertical = DshSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DshGlyphIcon(
                    glyph = if (expanded) DshGlyph.ChevronDown else DshGlyph.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 16.dp,
                )
                Text(
                    text = stepsLabel,
                    modifier = Modifier.weight(1f),
                    style = type.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.turnOpen) {
                    Text(
                        text = stringResource(R.string.conversation_group_open_turn),
                        style = type.meta,
                        color = statusFaint(),
                    )
                }
                StatusChip(label = statusLabel, tone = groupStatusTone(item.status))
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(
                        start = DshSpacing.md,
                        end = DshSpacing.md,
                        bottom = DshSpacing.md,
                    ),
                    verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
                ) {
                    item.entries.forEach { entry ->
                        ActivityEntryRow(entry)
                    }
                }
            }
        }
    }
}

/** 组内单条目分发：四种来源各有表现，未登记类型不静默丢弃（D08）。 */
@Composable
private fun ActivityEntryRow(entry: ActivityEntry) {
    when (entry) {
        is ActivityEntry.Tool -> ToolEntryRow(tool = entry.tool)
        is ActivityEntry.Reasoning -> ReasoningOverlay(text = entry.text)
        is ActivityEntry.Attempt -> AttemptRow(entry = entry)
        is ActivityEntry.UnsupportedEvent -> UnsupportedEntryRow(eventType = entry.type)
    }
}

/**
 * 单条工具调用：状态胶囊 + 可展开的参数/结果原文。
 * [ToolStatus.Incomplete] 走中性色，**不画成失败**（[toolStatusTone]）；
 * `arguments`/`result` 原样显示，解析失败也不影响条目（计划 §7.2）。
 */
@Composable
fun ToolEntryRow(
    tool: ToolEntry,
    modifier: Modifier = Modifier,
) {
    val type = LocalDshType.current
    var detailOpen by remember(tool.callId.value) { mutableStateOf(false) }
    val detailLabel = stringResource(if (detailOpen) R.string.states_hide_details else R.string.states_view_details)
    val argumentsJson = tool.argumentsJson
    val resultJson = tool.resultJson
    val hasDetail = argumentsJson != null || resultJson != null
    val conflict = tool.conflictingResultSeq
    val noneLabel = stringResource(R.string.states_none)

    Column(
        modifier = modifier
            .defaultMinSize(minHeight = DshDimens.TouchTarget)
            .then(
                if (hasDetail) {
                    Modifier.clickable(onClickLabel = detailLabel, onClick = { detailOpen = !detailOpen })
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tool.toolName,
                modifier = Modifier.weight(1f, fill = false),
                style = type.mono,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusChip(
                label = toolStatusLabel(tool.status),
                tone = toolStatusTone(tool.status),
                glyph = toolStatusGlyph(tool.status),
            )
        }
        Text(
            text = stringResource(
                R.string.conversation_tool_seq_pair,
                tool.callSeq?.let { formatSeq(it.value) } ?: noneLabel,
                tool.resultSeq?.let { formatSeq(it.value) } ?: noneLabel,
            ),
            style = type.meta,
            color = statusFaint(),
        )
        if (conflict != null) {
            // 同一 call 收到不同 seq 的第二条 result：保留较早终态，这里只记账（计划 §7.2）。
            Text(
                text = stringResource(R.string.conversation_tool_result_conflict, formatSeq(conflict.value)),
                style = type.meta,
                color = toneColors(DshTone.Neutral).onContainer,
            )
        }
        if (detailOpen) {
            if (argumentsJson != null) {
                DataBlock(label = stringResource(R.string.conversation_tool_arguments), value = argumentsJson)
            }
            if (resultJson != null) {
                DataBlock(label = stringResource(R.string.conversation_tool_result), value = resultJson)
            }
        }
    }
}

/**
 * reasoning 叠加层：明确标注“推理叠加层”，并说明它不是正文、提交后清除
 * （F12：没有独立 reasoning 帧；计划 §7.1：不持久化半截文本）。
 */
@Composable
fun ReasoningOverlay(
    text: String,
    modifier: Modifier = Modifier,
) {
    val type = LocalDshType.current
    Column(
        modifier = modifier
            .widthIn(max = DshDimens.ContentMaxWidth)
            .padding(start = DshSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.conversation_reasoning_title),
            style = type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = text, style = type.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(R.string.conversation_reasoning_disclaimer),
            style = type.meta,
            color = statusFaint(),
        )
    }
}

/** `assistant/attempt` 折叠出的诊断行，不是普通气泡（计划 §7.1）。 */
@Composable
private fun AttemptRow(entry: ActivityEntry.Attempt) {
    val type = LocalDshType.current
    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm)) {
            Text(
                text = stringResource(R.string.conversation_attempt_title),
                style = type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.interrupted) {
                Text(
                    text = stringResource(R.string.conversation_interrupted),
                    style = type.meta,
                    color = statusFaint(),
                )
            }
        }
        if (entry.summary.isNotEmpty()) {
            Text(text = entry.summary, style = type.meta, color = statusFaint())
        }
        val errorKind = entry.errorKind
        if (errorKind != null) {
            Text(
                text = stringResource(R.string.conversation_attempt_error_kind, errorKind),
                style = type.meta,
                color = statusFaint(),
            )
        }
    }
}

@Composable
private fun UnsupportedEntryRow(eventType: String) {
    Text(
        text = stringResource(R.string.conversation_unsupported_entry, eventType),
        style = LocalDshType.current.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 参数/结果原文块：等宽、限高、内部滚动。 */
@Composable
private fun DataBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val type = LocalDshType.current
    Column(
        modifier = modifier
            .widthIn(max = DshDimens.ContentMaxWidth)
            .padding(top = DshSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        Text(text = label, style = type.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = LocalDshShapes.current.card,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(DshDimens.Stroke, statusOutline()),
        ) {
            Text(
                text = value,
                modifier = Modifier
                    .heightIn(max = DshDimens.CollapsedBlockHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(DshSpacing.sm),
                style = type.mono,
            )
        }
    }
}

/** 字形只是装饰（状态名由文本承载），因此不需要 contentDescription。 */
private fun toolStatusGlyph(status: ToolStatus): DshGlyph = when (status) {
    ToolStatus.Called -> DshGlyph.Ring
    ToolStatus.Succeeded -> DshGlyph.Check
    ToolStatus.Failed -> DshGlyph.Cross
    ToolStatus.Incomplete -> DshGlyph.Dash
    ToolStatus.OrphanResult -> DshGlyph.Dot
}
