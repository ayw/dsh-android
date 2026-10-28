package ai.deepseek.dsh.android.feature.inbox

import ai.deepseek.dsh.android.designsystem.components.DisclosureRow
import ai.deepseek.dsh.android.designsystem.components.EmptyState
import ai.deepseek.dsh.android.designsystem.components.ErrorState
import ai.deepseek.dsh.android.designsystem.components.InlineNotice
import ai.deepseek.dsh.android.designsystem.components.ListSkeleton
import ai.deepseek.dsh.android.designsystem.components.NoticeEmphasis
import ai.deepseek.dsh.android.designsystem.components.PendingCountBadge
import ai.deepseek.dsh.android.designsystem.components.SectionHeader
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.DshTheme
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.timeline.PendingAnchorCard
import ai.deepseek.dsh.android.designsystem.timeline.PendingAnchorContent
import ai.deepseek.dsh.android.designsystem.timeline.PendingField
import ai.deepseek.dsh.android.designsystem.timeline.PendingOption
import ai.deepseek.dsh.android.designsystem.timeline.SubmitPhase
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.model.EventSeq
import ai.deepseek.dsh.android.model.PendingContent
import ai.deepseek.dsh.android.model.PendingKind
import ai.deepseek.dsh.android.model.RequestId
import ai.deepseek.dsh.android.model.SessionId
import ai.deepseek.dsh.android.model.TimelineItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 有状态入口：主题、状态收集与导航 lambda 都在这里收口。 */
@Composable
fun InboxRoute(
    viewModel: InboxViewModel,
    onOpenSession: (SessionId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DshTheme {
        InboxScreen(
            state = state,
            onOpenSession = onOpenSession,
            onDismiss = viewModel::dismiss,
            onUndismiss = viewModel::undismiss,
            modifier = modifier,
        )
    }
}

/**
 * 只读全局收件箱：按会话分组、按首次出现时间排序。
 *
 * 卡片的提交控件固定禁用：本里程碑不提交，提交值的请求体要等 M5 的录制。
 */
@Composable
fun InboxScreen(
    state: InboxUiState,
    onOpenSession: (SessionId) -> Unit,
    onDismiss: (PendingKind, RequestId) -> Unit,
    onUndismiss: (PendingKind, RequestId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding,
        ) {
            item(key = "header") {
                InboxHeader(state)
            }

            val countsError = state.countsError
            if (countsError != null) {
                item(key = "counts-error") {
                    InlineNotice(
                        title = stringResource(R.string.inbox_counts_error_title),
                        emphasis = NoticeEmphasis.Warning,
                        modifier = Modifier.padding(
                            horizontal = DshDimens.Gutter,
                            vertical = DshSpacing.xs,
                        ),
                        detail = stringResource(errorReasonRes(countsError)),
                    )
                }
            }

            val rowsError = state.rowsError
            if (rowsError != null) {
                item(key = "rows-error") {
                    val noticeModifier = Modifier.padding(
                        horizontal = DshDimens.Gutter,
                        vertical = DshSpacing.xs,
                    )
                    if (state.visibleCount == 0 && state.dismissed.isEmpty()) {
                        ErrorState(
                            reasonRes = errorReasonRes(rowsError),
                            modifier = noticeModifier,
                            detail = stringResource(R.string.inbox_rows_error_detail),
                        )
                    } else {
                        InlineNotice(
                            title = stringResource(R.string.inbox_rows_error_title),
                            emphasis = NoticeEmphasis.Warning,
                            modifier = noticeModifier,
                            detail = stringResource(errorReasonRes(rowsError)),
                        )
                    }
                }
            }

            val actionError = state.actionError
            if (actionError != null) {
                item(key = "action-error") {
                    InlineNotice(
                        title = stringResource(R.string.inbox_action_error_title),
                        emphasis = NoticeEmphasis.Warning,
                        modifier = Modifier.padding(
                            horizontal = DshDimens.Gutter,
                            vertical = DshSpacing.xs,
                        ),
                        detail = stringResource(errorReasonRes(actionError)),
                    )
                }
            }

            if (state.loading) {
                item(key = "loading") {
                    ListSkeleton(
                        modifier = Modifier.padding(vertical = DshSpacing.sm),
                        rows = 3,
                    )
                }
            }

            state.sections.forEach { section ->
                item(key = "session:${section.sessionId.value}") {
                    SessionHeader(section = section, onOpenSession = onOpenSession)
                }
                items(
                    items = section.cards,
                    key = { card ->
                        "card:${card.sessionId.value}:${card.kind.name}:${card.requestId.value}"
                    },
                ) { card ->
                    InboxCardView(
                        card = card,
                        onDismiss = { onDismiss(card.kind, card.requestId) },
                        onUndismiss = null,
                    )
                }
            }

            if (!state.loading && state.visibleCount == 0 && state.rowsError == null) {
                item(key = "empty") {
                    val trusted = state.trustedEmpty
                    EmptyState(
                        titleRes = if (trusted) {
                            R.string.inbox_empty_title
                        } else {
                            R.string.inbox_unknown_title
                        },
                        subtitleRes = if (trusted) {
                            R.string.inbox_empty_subtitle
                        } else {
                            R.string.inbox_unknown_subtitle
                        },
                    )
                }
            }

            if (state.showDismissed) {
                item(key = "dismissed") {
                    DismissedSection(
                        dismissed = state.dismissed,
                        onUndismiss = onUndismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxHeader(state: InboxUiState) {
    val type = LocalDshType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.md),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.inbox_title),
                modifier = Modifier.weight(1f),
                style = type.listTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            state.counts?.let { counts ->
                PendingCountBadge(counts = counts)
            }
        }
        Text(
            text = stringResource(R.string.inbox_subtitle),
            style = type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.volumeMaySkipSweep) {
            Text(
                text = stringResource(R.string.inbox_volume_notice),
                style = type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionHeader(
    section: InboxSection,
    onOpenSession: (SessionId) -> Unit,
) {
    val type = LocalDshType.current
    val openLabel = stringResource(R.string.inbox_open_session)
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = section.title?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.inbox_session_untitled),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = openLabel) { onOpenSession(section.sessionId) },
        )
        val hint = when {
            !section.available -> stringResource(R.string.inbox_session_unavailable)
            section.archived -> stringResource(R.string.inbox_session_archived)
            else -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                modifier = Modifier.padding(
                    start = DshDimens.Gutter,
                    end = DshDimens.Gutter,
                    bottom = DshSpacing.xs,
                ),
                style = type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InboxCardView(
    card: InboxCard,
    onDismiss: (() -> Unit)?,
    onUndismiss: (() -> Unit)?,
) {
    val type = LocalDshType.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        PendingAnchorCard(
            item = card.toAnchorItem(),
            content = anchorContent(card.content),
            phase = SubmitPhase.Idle,
            modifier = Modifier.fillMaxWidth(),
            onDecision = null,
            onDismiss = onDismiss,
        )
        if (card.content is PendingContent.Undecodable) {
            InlineNotice(
                title = stringResource(R.string.inbox_undecodable_notice),
                emphasis = NoticeEmphasis.Warning,
                modifier = Modifier.padding(horizontal = DshDimens.Gutter),
                detail = stringResource(R.string.inbox_undecodable_detail),
            )
        }
        if (onUndismiss != null) {
            TextButton(
                onClick = onUndismiss,
                modifier = Modifier.padding(start = DshDimens.Gutter),
            ) {
                Text(text = stringResource(R.string.inbox_undismiss), style = type.label)
            }
        }
    }
}

@Composable
private fun DismissedSection(
    dismissed: List<InboxCard>,
    onUndismiss: (PendingKind, RequestId) -> Unit,
) {
    val type = LocalDshType.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    DisclosureRow(
        title = stringResource(R.string.inbox_dismissed_title),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        summary = stringResource(R.string.inbox_dismissed_summary),
        value = if (dismissed.isEmpty()) {
            null
        } else {
            stringResource(R.string.inbox_dismissed_count, dismissed.size)
        },
    ) {
        if (dismissed.isEmpty()) {
            Text(
                text = stringResource(R.string.inbox_dismissed_empty),
                style = type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            dismissed.forEach { card ->
                key(card.sessionId.value, card.kind.name, card.requestId.value) {
                    InboxCardView(
                        card = card,
                        onDismiss = null,
                        onUndismiss = { onUndismiss(card.kind, card.requestId) },
                    )
                }
            }
        }
    }
}

private fun InboxCard.toAnchorItem(): TimelineItem.PendingAnchor = TimelineItem.PendingAnchor(
    key = "inbox:${kind.name}:${requestId.value}",
    primarySeq = anchorSeq ?: EventSeq(0L),
    requestId = requestId,
    kind = kind,
    anchoredToCallId = anchoredToCallId,
)

@Composable
private fun anchorContent(content: PendingContent): PendingAnchorContent = when (content) {
    is PendingContent.Approval -> PendingAnchorContent(
        title = content.toolName?.let { toolName ->
            stringResource(R.string.inbox_approval_title, toolName)
        } ?: stringResource(R.string.inbox_approval_title_generic),
        detail = content.reason,
    )

    is PendingContent.Question -> PendingAnchorContent(
        title = stringResource(R.string.inbox_question_title),
        fields = content.questions.map { question ->
            val header = question.header?.takeIf { it.isNotBlank() }
            PendingField(
                id = question.questionId,
                label = if (header != null) {
                    stringResource(R.string.inbox_question_field_label, header, question.text)
                } else {
                    question.text
                },
                options = question.options.mapIndexed { index, option ->
                    PendingOption(id = index.toString(), label = option.label)
                },
                multiSelect = question.multiSelect,
                allowFreeText = false,
                required = false,
            )
        },
    )

    is PendingContent.Undecodable -> PendingAnchorContent(
        title = stringResource(R.string.inbox_undecodable_notice),
    )
}

private fun errorReasonRes(error: DomainError?): Int = when (error) {
    DomainError.AuthExpired -> R.string.inbox_error_auth
    is DomainError.Forbidden -> R.string.inbox_error_forbidden
    DomainError.Unreachable -> R.string.inbox_error_unreachable
    DomainError.Timeout -> R.string.inbox_error_timeout
    else -> R.string.inbox_error_generic
}
