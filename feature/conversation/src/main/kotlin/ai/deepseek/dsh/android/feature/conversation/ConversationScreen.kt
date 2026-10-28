package ai.deepseek.dsh.android.feature.conversation

import ai.deepseek.dsh.android.core.designsystem.R as DshR
import ai.deepseek.dsh.android.designsystem.components.EmptyState
import ai.deepseek.dsh.android.designsystem.components.ErrorState
import ai.deepseek.dsh.android.designsystem.components.InlineNotice
import ai.deepseek.dsh.android.designsystem.components.ListSkeleton
import ai.deepseek.dsh.android.designsystem.components.NoticeEmphasis
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.DshTheme
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.dshContentWidth
import ai.deepseek.dsh.android.designsystem.theme.statusFaint
import ai.deepseek.dsh.android.designsystem.timeline.HistoryIncompleteNotice
import ai.deepseek.dsh.android.designsystem.timeline.PendingAnchorContent
import ai.deepseek.dsh.android.designsystem.timeline.TimelineItemRow
import ai.deepseek.dsh.android.designsystem.timeline.VisibleHistoryNotice
import ai.deepseek.dsh.android.designsystem.timeline.formatSeq
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.TimelineGapState
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.TimelineItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 会话时间线（M4，只读）。状态收集、主题与导航回调都在这里收口。
 */
@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DshTheme {
        ConversationContent(
            state = state,
            onBack = onBack,
            onLoadOlder = viewModel::loadOlder,
            onToggleGroup = viewModel::toggleGroup,
            onDismiss = viewModel::dismiss,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationContent(
    state: ConversationUiState,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onDismiss: (PendingKind, RequestId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = LocalDshType.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(DshR.string.conversation_title),
                        style = type.listTitle,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(text = stringResource(R.string.conversation_back), style = type.label)
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding,
        ) {
            item(key = "readonly") {
                Text(
                    text = stringResource(DshR.string.conversation_readonly_hint),
                    modifier = Modifier
                        .dshContentWidth()
                        .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.xs),
                    style = type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.notices.isNotEmpty()) {
                item(key = "notices") {
                    ConversationNotices(state = state)
                }
            }

            val readError = state.readError
            if (readError != null && state.items.isEmpty()) {
                item(key = "read-error") {
                    ErrorState(
                        reasonRes = errorReasonRes(readError),
                        modifier = Modifier.padding(
                            horizontal = DshDimens.Gutter,
                            vertical = DshSpacing.xs,
                        ),
                        detail = stringResource(R.string.conversation_read_error_detail),
                    )
                }
            } else if (readError != null) {
                item(key = "read-stale") {
                    InlineNotice(
                        title = stringResource(R.string.conversation_read_error_title),
                        emphasis = NoticeEmphasis.Warning,
                        modifier = Modifier.padding(
                            horizontal = DshDimens.Gutter,
                            vertical = DshSpacing.xs,
                        ),
                        detail = stringResource(errorReasonRes(readError)),
                    )
                }
            }

            val actionError = state.actionError
            if (actionError != null) {
                item(key = "action-error") {
                    InlineNotice(
                        title = stringResource(R.string.conversation_action_error_title),
                        emphasis = NoticeEmphasis.Warning,
                        modifier = Modifier.padding(
                            horizontal = DshDimens.Gutter,
                            vertical = DshSpacing.xs,
                        ),
                        detail = stringResource(errorReasonRes(actionError)),
                    )
                }
            }

            if (!state.initialLoading) {
                item(key = "older") {
                    OlderHeader(state = state, onLoadOlder = onLoadOlder)
                }
            }

            if (state.initialLoading) {
                item(key = "loading") {
                    ListSkeleton(
                        modifier = Modifier.padding(vertical = DshSpacing.sm),
                        rows = 4,
                    )
                }
            }

            if (state.isEmptyTimeline && !state.initialLoading && readError == null) {
                item(key = "empty") {
                    val canLoad = state.hasMoreHistory
                    EmptyState(
                        titleRes = DshR.string.conversation_no_messages_title,
                        subtitleRes = DshR.string.conversation_no_messages_subtitle,
                        actionLabelRes = if (canLoad) DshR.string.conversation_load_earlier else null,
                        onAction = if (canLoad) onLoadOlder else null,
                    )
                }
            }

            items(items = state.items, key = { item -> item.key }) { item ->
                TimelineItemRow(
                    item = item,
                    modifier = Modifier.fillMaxWidth(),
                    expandedGroups = state.expandedGroups,
                    onToggleGroup = onToggleGroup,
                    pendingContent = if (item is TimelineItem.PendingAnchor) {
                        localizedPendingContent(state.pendingContents[item.key], item.kind)
                    } else {
                        null
                    },
                    // D33：M4 不提交远端决议；卡片因此不出现可用的批准/拒绝。
                    onPendingDecision = null,
                    onPendingDismiss = if (item is TimelineItem.PendingAnchor) {
                        { onDismiss(item.kind, item.requestId) }
                    } else {
                        null
                    },
                    pendingSessionAvailable = true,
                )
            }
        }
    }
}

/**
 * 翻页头：只有 `hasMoreHistory` 为 false 才声明“到顶”。
 * 失败后 `hasMoreHistory` 保持 true，按钮仍可重试（不自动重试）。
 */
@Composable
private fun OlderHeader(
    state: ConversationUiState,
    onLoadOlder: () -> Unit,
) {
    val type = LocalDshType.current
    Column(
        modifier = Modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        val historyError = state.historyError
        if (historyError != null) {
            InlineNotice(
                title = stringResource(R.string.conversation_older_error_title),
                emphasis = NoticeEmphasis.Warning,
                detail = stringResource(errorReasonRes(historyError)),
            )
        }
        when {
            state.historyLoading -> Text(
                text = stringResource(DshR.string.conversation_loading_earlier),
                style = type.meta,
                color = statusFaint(),
            )

            state.hasMoreHistory -> TextButton(
                onClick = onLoadOlder,
                modifier = Modifier.defaultMinSize(minHeight = DshDimens.TouchTarget),
            ) {
                Text(text = stringResource(DshR.string.conversation_load_earlier), style = type.label)
            }

            state.items.isNotEmpty() -> Text(
                text = stringResource(DshR.string.conversation_history_start),
                style = type.meta,
                color = statusFaint(),
            )
        }
    }
}

/**
 * 缺口说明条。种类由 ViewModel 决定（[ConversationNoticeKind]），这里只选组件与资源，
 * 不在屏幕里重新判断历史是否完整。
 */
@Composable
private fun ConversationNotices(state: ConversationUiState) {
    val type = LocalDshType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DshSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        state.notices.forEach { kind ->
            when (kind) {
                ConversationNoticeKind.SeqMissing -> {
                    val missing = state.gapState as? TimelineGapState.Missing
                    if (missing == null) {
                        // 种类与状态不一致时不伪造区间，只说明历史可能不完整。
                        InlineNotice(
                            title = stringResource(DshR.string.states_history_may_be_incomplete),
                            emphasis = NoticeEmphasis.Neutral,
                            modifier = noticeModifier(),
                        )
                    } else {
                        InlineNotice(
                            title = stringResource(
                                DshR.string.conversation_gap_range,
                                formatSeq(missing.expectedSeq.value),
                                formatSeq(missing.arrivedSeq.value),
                            ),
                            emphasis = NoticeEmphasis.Neutral,
                            modifier = noticeModifier(),
                            detail = stringResource(DshR.string.states_history_may_be_incomplete),
                        )
                    }
                }

                ConversationNoticeKind.HistoryIncomplete ->
                    HistoryIncompleteNotice(modifier = noticeModifier())

                ConversationNoticeKind.HistoryUnsafe -> {
                    val unknownType = state.historyUnsafeType
                    InlineNotice(
                        title = stringResource(DshR.string.conversation_history_unsafe_title),
                        emphasis = NoticeEmphasis.Warning,
                        modifier = noticeModifier(),
                        detail = if (unknownType == null) {
                            stringResource(DshR.string.states_unknown)
                        } else {
                            stringResource(DshR.string.conversation_history_unsafe_detail, unknownType)
                        },
                    )
                }

                ConversationNoticeKind.VisibleHistoryNotContext ->
                    VisibleHistoryNotice(modifier = noticeModifier())
            }
        }
    }
}

@Composable
private fun noticeModifier(): Modifier = Modifier.padding(horizontal = DshDimens.Gutter)

/**
 * VM 不持有 Context，无法本地化兜底标题；空标题在这里补资源文案。
 * 有 toolName / 题目文本时原样使用解码结果，屏幕不改写正文。
 */
@Composable
private fun localizedPendingContent(
    content: PendingAnchorContent?,
    kind: PendingKind,
): PendingAnchorContent? {
    if (content == null || content.title.isNotBlank()) return content
    val fallback = if (kind == PendingKind.Approval) {
        R.string.conversation_pending_approval_title
    } else {
        R.string.conversation_pending_question_title
    }
    return content.copy(title = stringResource(fallback))
}

/** 一律只显示资源文案，异常正文/服务端 body 不进 UI（G09.2）。 */
private fun errorReasonRes(error: DomainError): Int = when (error) {
    DomainError.AuthExpired -> DshR.string.errors_auth_expired
    is DomainError.Forbidden -> DshR.string.errors_forbidden_detail
    DomainError.Unreachable -> DshR.string.errors_network_unreachable
    DomainError.Timeout -> DshR.string.errors_timeout
    DomainError.HistoryIncomplete -> DshR.string.errors_repair_stopped
    DomainError.Conflict -> DshR.string.errors_generic
    is DomainError.Rejected -> DshR.string.errors_generic
    is DomainError.UnavailableCapability -> DshR.string.errors_generic
}
