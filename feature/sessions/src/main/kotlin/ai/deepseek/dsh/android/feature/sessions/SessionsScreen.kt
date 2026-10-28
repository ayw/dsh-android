package ai.deepseek.dsh.android.feature.sessions

import ai.deepseek.dsh.android.designsystem.components.Badge
import ai.deepseek.dsh.android.designsystem.components.EmptyState
import ai.deepseek.dsh.android.designsystem.components.ErrorState
import ai.deepseek.dsh.android.designsystem.components.InlineNotice
import ai.deepseek.dsh.android.designsystem.components.ListSkeleton
import ai.deepseek.dsh.android.designsystem.components.NoticeEmphasis
import ai.deepseek.dsh.android.designsystem.components.PendingCountBadge
import ai.deepseek.dsh.android.designsystem.components.StatusChip
import ai.deepseek.dsh.android.designsystem.theme.DshDimens
import ai.deepseek.dsh.android.designsystem.theme.DshSpacing
import ai.deepseek.dsh.android.designsystem.theme.DshTheme
import ai.deepseek.dsh.android.designsystem.theme.DshTone
import ai.deepseek.dsh.android.designsystem.theme.LocalDshType
import ai.deepseek.dsh.android.designsystem.theme.dshContentWidth
import ai.deepseek.dsh.android.domain.repository.DomainError
import ai.deepseek.dsh.android.domain.repository.RosterSyncState
import ai.deepseek.dsh.android.model.PendingCompleteness
import ai.deepseek.dsh.android.model.PendingCounts
import ai.deepseek.dsh.android.model.SessionId
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 会话列表（M4 只读）。行只展示名册事实：标题、更新时间、运行/归档/历史不可重建。
 * 待处理计数只从 [PendingCounts] 来；未知时由 `PendingCountBadge` 显示“未知”，绝不显示 0。
 */
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onOpenSession: (SessionId) -> Unit,
    onOpenInbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DshTheme {
        Scaffold(modifier = modifier) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
            ) {
                item(key = "header") {
                    SessionsHeader(
                        state = state,
                        onOpenInbox = onOpenInbox,
                        onRefresh = viewModel::refresh,
                    )
                }

                val countsError = state.countsError
                if (countsError != null) {
                    item(key = "counts-error") {
                        InlineNotice(
                            title = stringResource(R.string.sessions_counts_error_title),
                            emphasis = NoticeEmphasis.Warning,
                            modifier = Modifier.padding(
                                horizontal = DshDimens.Gutter,
                                vertical = DshSpacing.xs,
                            ),
                            detail = stringResource(errorReasonRes(countsError)),
                        )
                    }
                }

                if (state.rosterMayBeStale && state.phase != SessionsListPhase.Error) {
                    item(key = "roster-state") {
                        val failed = state.rosterSyncState == RosterSyncState.Failed
                        InlineNotice(
                            title = stringResource(
                                if (failed) {
                                    R.string.sessions_roster_failed_title
                                } else {
                                    R.string.sessions_roster_stale_title
                                },
                            ),
                            emphasis = if (failed) NoticeEmphasis.Warning else NoticeEmphasis.Neutral,
                            modifier = Modifier.padding(
                                horizontal = DshDimens.Gutter,
                                vertical = DshSpacing.xs,
                            ),
                            detail = stringResource(
                                if (failed) {
                                    R.string.sessions_roster_failed_detail
                                } else {
                                    R.string.sessions_roster_stale_detail
                                },
                            ),
                        )
                    }
                }

                val actionError = state.actionError
                if (actionError != null) {
                    item(key = "action-error") {
                        InlineNotice(
                            title = stringResource(R.string.sessions_action_error_title),
                            emphasis = NoticeEmphasis.Warning,
                            modifier = Modifier.padding(
                                horizontal = DshDimens.Gutter,
                                vertical = DshSpacing.xs,
                            ),
                            detail = stringResource(errorReasonRes(actionError)),
                        )
                    }
                }

                val loadError = state.loadError
                if (loadError != null && state.rows.isNotEmpty()) {
                    item(key = "load-error") {
                        InlineNotice(
                            title = stringResource(R.string.sessions_load_error_title),
                            emphasis = NoticeEmphasis.Warning,
                            modifier = Modifier.padding(
                                horizontal = DshDimens.Gutter,
                                vertical = DshSpacing.xs,
                            ),
                            detail = stringResource(R.string.sessions_load_error_detail),
                        )
                    }
                }

                if (state.phase == SessionsListPhase.Loading) {
                    item(key = "loading") {
                        ListSkeleton(
                            modifier = Modifier.padding(vertical = DshSpacing.sm),
                            rows = 4,
                        )
                    }
                }

                items(
                    items = state.rows,
                    key = { row -> "session:${row.sessionId.value}" },
                ) { row ->
                    SessionRowView(row = row, onOpenSession = onOpenSession)
                }

                if (state.phase == SessionsListPhase.Empty) {
                    item(key = "empty") {
                        EmptyState(
                            titleRes = if (state.trustedEmpty) {
                                R.string.sessions_empty_title
                            } else {
                                R.string.sessions_unknown_empty_title
                            },
                            subtitleRes = if (state.trustedEmpty) {
                                R.string.sessions_empty_subtitle
                            } else {
                                R.string.sessions_unknown_empty_subtitle
                            },
                            actionLabelRes = R.string.sessions_refresh,
                            onAction = viewModel::refresh,
                        )
                    }
                }

                if (state.phase == SessionsListPhase.Error) {
                    item(key = "error") {
                        ErrorState(
                            reasonRes = errorReasonRes(state.loadError),
                            modifier = Modifier.padding(vertical = DshSpacing.sm),
                            onRetry = viewModel::refresh,
                        )
                    }
                }

                if (state.rows.isNotEmpty()) {
                    item(key = "footer") {
                        LoadMoreFooter(state = state, onLoadMore = viewModel::loadMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionsHeader(
    state: SessionsUiState,
    onOpenInbox: () -> Unit,
    onRefresh: () -> Unit,
) {
    val type = LocalDshType.current
    // 计数还没到达时也渲染“未知”胶囊：没有 PendingCounts 不等于 0（D13）。
    val counts = state.counts ?: PendingCounts(
        badge = null,
        dismissed = 0,
        completeness = PendingCompleteness.Unknown,
    )
    Column(
        modifier = Modifier
            .dshContentWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.md),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sessions_title),
                modifier = Modifier.weight(1f),
                style = type.listTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PendingCountBadge(counts = counts, onClick = onOpenInbox)
        }
        Text(
            text = stringResource(R.string.sessions_subtitle),
            style = type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm)) {
            TextButton(onClick = onRefresh, enabled = !state.refreshing) {
                Text(
                    text = stringResource(
                        if (state.refreshing) {
                            R.string.sessions_refreshing
                        } else {
                            R.string.sessions_refresh
                        },
                    ),
                    style = type.label,
                )
            }
            TextButton(onClick = onOpenInbox) {
                Text(text = stringResource(R.string.sessions_open_inbox), style = type.label)
            }
        }
    }
}

@Composable
private fun SessionRowView(
    row: SessionRow,
    onOpenSession: (SessionId) -> Unit,
) {
    val type = LocalDshType.current
    val openLabel = stringResource(R.string.sessions_open_conversation)
    Column(
        modifier = Modifier
            .dshContentWidth()
            .clickable(onClickLabel = openLabel) { onOpenSession(row.sessionId) }
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        // 标题换行而非省略：窄屏/平板都靠折行保留可区分的后缀，绝不用省略号截断（响应式评审标准）。
        Text(
            text = row.title?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.sessions_untitled),
            style = type.listTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = relativeUpdatedText(row.updatedAtMs),
                modifier = Modifier.weight(1f, fill = false),
                style = type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 仅当本会话确有可见 pending 才画徽标；0 不画（不是伪造 0，D13 只约束聚合计数）。
            if (row.pendingCount > 0) {
                Badge(
                    text = stringResource(R.string.sessions_row_pending, row.pendingCount),
                    tone = DshTone.Warning,
                )
            }
        }
        if (row.running || row.archived || row.historyUnsafe) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.running) {
                    StatusChip(
                        label = stringResource(R.string.sessions_status_running),
                        tone = DshTone.Info,
                    )
                }
                if (row.archived) {
                    StatusChip(
                        label = stringResource(R.string.sessions_status_archived),
                        tone = DshTone.Neutral,
                    )
                }
                if (row.historyUnsafe) {
                    StatusChip(
                        label = stringResource(R.string.sessions_status_history_unsafe),
                        tone = DshTone.Warning,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadMoreFooter(
    state: SessionsUiState,
    onLoadMore: () -> Unit,
) {
    val type = LocalDshType.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshDimens.Gutter, vertical = DshSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.loadingMore -> Text(
                text = stringResource(R.string.sessions_loading_more),
                style = type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.canLoadMore -> TextButton(
                onClick = onLoadMore,
                enabled = !state.refreshing,
            ) {
                Text(text = stringResource(R.string.sessions_load_more), style = type.label)
            }

            else -> Text(
                text = stringResource(R.string.sessions_end_of_list),
                style = type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun errorReasonRes(error: DomainError?): Int = when (error) {
    DomainError.AuthExpired -> R.string.sessions_error_auth
    is DomainError.Forbidden -> R.string.sessions_error_forbidden
    DomainError.Unreachable -> R.string.sessions_error_unreachable
    DomainError.Timeout -> R.string.sessions_error_timeout
    DomainError.HistoryIncomplete -> R.string.sessions_error_history_incomplete
    DomainError.Conflict -> R.string.sessions_error_conflict
    is DomainError.Rejected -> R.string.sessions_error_rejected
    is DomainError.UnavailableCapability -> R.string.sessions_error_unavailable
    null -> R.string.sessions_error_generic
}
