package com.reed.fcmguard.ui.fcm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reed.fcmguard.AutostartStatusReader
import com.reed.fcmguard.R
import com.reed.fcmguard.ui.components.InfoNote
import com.reed.fcmguard.ui.components.SectionCard
import com.reed.fcmguard.ui.components.StatusPill
import com.reed.fcmguard.ui.components.StatusTone

/**
 * FCM app assistant.
 *
 * The screen lists only apps the PackageManager actually reported. When HyperOS refuses
 * the vendor AppOps probe every row stays UNKNOWN — the screen says so instead of
 * presenting an unreadable state as "disabled".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FcmScreen(
    onOpenAutostart: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FcmViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            SectionCard(
                title = stringResource(R.string.fcm_apps_title),
                subtitle = stringResource(R.string.fcm_apps_summary)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = viewModel::scan,
                        enabled = !state.scanning
                    ) {
                        if (state.scanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (state.scanning) R.string.scanning_apps else R.string.scan_fcm_apps
                            )
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onOpenAutostart) {
                        Text(stringResource(R.string.open_autostart_manager))
                    }
                }

                when {
                    !state.scanned ->
                        Text(
                            text = stringResource(R.string.fcm_apps_not_scanned),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                    state.apps.isEmpty() ->
                        Text(
                            text = stringResource(R.string.no_fcm_apps),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                    else -> Text(
                        text = if (state.autostartUnreadable) {
                            stringResource(
                                R.string.fcm_autostart_status_unavailable,
                                state.apps.size
                            )
                        } else {
                            stringResource(
                                R.string.fcm_apps_status_summary,
                                state.apps.size,
                                state.enabledCount,
                                state.attentionCount,
                                state.unknownCount
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (state.scanned && state.apps.isNotEmpty()) {
            item(key = "search") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    placeholder = { Text(stringResource(R.string.search_apps)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = MaterialTheme.shapes.large
                )
            }

            item(key = "filters") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.filter == FcmFilter.ALL,
                        onClick = { viewModel.setFilter(FcmFilter.ALL) },
                        label = { Text(stringResource(R.string.filter_all)) }
                    )
                    FilterChip(
                        selected = state.filter == FcmFilter.ENABLED,
                        onClick = { viewModel.setFilter(FcmFilter.ENABLED) },
                        label = { Text(stringResource(R.string.autostart_enabled)) }
                    )
                    FilterChip(
                        selected = state.filter == FcmFilter.ATTENTION,
                        onClick = { viewModel.setFilter(FcmFilter.ATTENTION) },
                        label = { Text(stringResource(R.string.filter_attention)) }
                    )
                    FilterChip(
                        selected = state.filter == FcmFilter.UNKNOWN,
                        onClick = { viewModel.setFilter(FcmFilter.UNKNOWN) },
                        label = { Text(stringResource(R.string.autostart_unknown)) }
                    )
                }
            }

            if (state.visible.isEmpty()) {
                item(key = "empty-filter") {
                    Text(
                        text = stringResource(R.string.fcm_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            }

            items(state.visible, key = { it.packageName }) { app ->
                FcmAppItem(app)
            }

            item(key = "note") {
                InfoNote(
                    text = stringResource(R.string.fcm_detection_note),
                    icon = Icons.Filled.Info
                )
            }
        }

        if (state.autostartUnreadable) {
            item(key = "unreadable-note") {
                InfoNote(
                    text = stringResource(R.string.fcm_detection_note),
                    icon = Icons.Filled.Info,
                    tone = StatusTone.WARN
                )
            }
        }
    }
}

@Composable
private fun FcmAppItem(app: FcmAppRow) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            StatusPill(
                text = stringResource(autostartLabel(app.autostart)),
                tone = autostartTone(app.autostart)
            )
        }
    }
}

private fun autostartLabel(status: AutostartStatusReader.Status): Int = when (status) {
    AutostartStatusReader.Status.ENABLED -> R.string.autostart_enabled
    AutostartStatusReader.Status.PARTIAL -> R.string.autostart_partial
    AutostartStatusReader.Status.DISABLED -> R.string.autostart_disabled
    AutostartStatusReader.Status.UNKNOWN -> R.string.autostart_unknown
}

private fun autostartTone(status: AutostartStatusReader.Status): StatusTone = when (status) {
    AutostartStatusReader.Status.ENABLED -> StatusTone.OK
    AutostartStatusReader.Status.PARTIAL -> StatusTone.WARN
    AutostartStatusReader.Status.DISABLED -> StatusTone.ERROR
    AutostartStatusReader.Status.UNKNOWN -> StatusTone.NEUTRAL
}
