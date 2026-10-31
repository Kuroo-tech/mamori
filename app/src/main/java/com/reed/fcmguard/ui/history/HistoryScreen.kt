package com.reed.fcmguard.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reed.fcmguard.R
import com.reed.fcmguard.execution.OperationRecord
import com.reed.fcmguard.ui.components.InfoNote
import com.reed.fcmguard.ui.components.KeyValueRow
import com.reed.fcmguard.ui.components.SectionCard
import com.reed.fcmguard.ui.components.StatusPill
import com.reed.fcmguard.ui.components.formatTime
import com.reed.fcmguard.ui.home.operationStateLabel
import com.reed.fcmguard.ui.home.operationStateTone

/**
 * Operation history.
 *
 * Every operation the engine journaled appears here with its real terminal (or
 * interrupted) state. Failed and partially-completed records are expandable so the
 * detail captured by the engine stays inspectable.
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var expandedId by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    if (state.loading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            SectionCard(
                title = stringResource(R.string.operation_history),
                subtitle = stringResource(
                    R.string.history_summary,
                    state.records.size
                )
            ) {
                if (state.records.isEmpty()) {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(state.records, key = { it.id }) { record ->
            HistoryEntry(
                record = record,
                expanded = expandedId == record.id,
                onToggle = { expandedId = if (expandedId == record.id) null else record.id }
            )
        }

        if (state.records.isNotEmpty()) {
            item(key = "note") {
                InfoNote(
                    text = stringResource(R.string.history_note),
                    icon = Icons.Filled.Info
                )
            }
        }
    }
}

@Composable
private fun HistoryEntry(record: OperationRecord, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = operationStateLabel(record.state),
                    tone = operationStateTone(record.state)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatTime(record.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = record.key,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    KeyValueRow(
                        label = stringResource(R.string.history_operation_id),
                        value = record.id
                    )
                    KeyValueRow(
                        label = stringResource(R.string.history_started),
                        value = formatTime(record.startedAt)
                    )
                    record.snapshotId?.let {
                        KeyValueRow(
                            label = stringResource(R.string.history_snapshot),
                            value = it
                        )
                    }
                    record.detail?.let {
                        KeyValueRow(
                            label = stringResource(R.string.history_detail),
                            value = it
                        )
                    }
                }
            }
        }
    }
}
