package com.reed.fcmguard.ui.language

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reed.fcmguard.R
import com.reed.fcmguard.core.locale.LocaleCapability
import com.reed.fcmguard.core.locale.LocaleCatalog
import com.reed.fcmguard.core.locale.LocaleChoice
import com.reed.fcmguard.core.locale.LocaleCode
import com.reed.fcmguard.core.locale.SupportedAppLocales
import com.reed.fcmguard.core.locale.SystemLocaleSnapshot
import com.reed.fcmguard.data.AppLocaleState
import com.reed.fcmguard.data.InstalledAppLocale
import com.reed.fcmguard.data.SystemLocaleSource
import com.reed.fcmguard.data.SystemLocaleState
import com.reed.fcmguard.ui.components.InfoNote
import com.reed.fcmguard.ui.components.KeyValueRow
import com.reed.fcmguard.ui.components.SectionCard
import com.reed.fcmguard.ui.components.StatusPill
import com.reed.fcmguard.ui.components.StatusTone
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Language Manager.
 *
 * What this screen can actually do, and by what mechanism:
 *
 * - **Read** the system locale list: always; it is the framework configuration.
 * - **Write** the system locale list: through the platform's own system-locale store
 *   (`Settings.System` key `system_locales`) with the user-granted *Modify system settings*
 *   access. Android exposes no public `setSystemLocales`, so this store is the mechanism the
 *   framework itself reads. Every write is verified against
 *   `LocaleManager.getSystemLocales()`; if the running configuration does not change, the
 *   result is reported as "saved, applies after restart" rather than success.
 * - **Restore** the original system locales: from the snapshot captured before the first
 *   change. It is never overwritten, and a failed restore keeps it for a retry.
 * - **Write Mamori's own** app locale: from API 33 through `LocaleManager`, verified by
 *   read-back.
 * - **Read another app's** locale: attempted on API 35 and reported per app when the platform
 *   refuses. **Writing** another app's locale has no public API, so the row only deep-links
 *   to that app's settings instead of pretending to change it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageManagerScreen(
    onOpenSystemLanguageSettings: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenAppSettings: (String) -> Unit,
    onLocaleChanged: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LanguageManagerViewModel = viewModel(
        factory = LanguageManagerViewModel.factory(LocalContext.current)
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pickerMode by remember { mutableStateOf(PickerMode.NONE) }
    var confirmSystemChange by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        launch {
            viewModel.events.collect { event ->
                when (event) {
                    LanguageEvent.OpenSystemLanguageSettings -> onOpenSystemLanguageSettings()
                    is LanguageEvent.OpenAppSettings -> onOpenAppSettings(event.packageName)
                    LanguageEvent.LocaleChanged -> onLocaleChanged()
                }
            }
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            onMessage(messageText(context, message))
            viewModel.dismissMessage()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            SectionCard(
                title = stringResource(R.string.language_manager_title),
                subtitle = stringResource(R.string.language_manager_summary)
            ) {
                if (state.applying) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        item(key = "system") {
            SystemLanguageCard(
                state = state,
                onOpenSystemLanguageSettings = onOpenSystemLanguageSettings,
                onOpenWriteSettings = onOpenWriteSettings,
                onChange = { pickerMode = PickerMode.SYSTEM },
                onRestore = { confirmRestore = true }
            )
        }

        item(key = "own") {
            OwnLanguageCard(
                state = state,
                onChange = { pickerMode = PickerMode.OWN },
                onClear = viewModel::clearOwnLocale
            )
        }

        item(key = "apps-header") {
            SectionCard(
                title = stringResource(R.string.installed_apps_title),
                subtitle = stringResource(R.string.installed_apps_summary)
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.installed_apps_search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = MaterialTheme.shapes.large
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        FilterRow(state.filter, viewModel::setFilter)
                    }
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.loading
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.installed_apps_refresh)
                        )
                    }
                }

                if (state.loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                AppReadAccessNote(state)
                if (state.discoveryRestricted) {
                    InfoNote(
                        text = stringResource(R.string.installed_apps_restricted),
                        icon = Icons.Filled.Info,
                        tone = StatusTone.WARN
                    )
                }
                if (state.visibleApps.isEmpty() && !state.loading) {
                    Text(
                        text = stringResource(
                            if (state.apps.isEmpty()) {
                                R.string.installed_apps_empty
                            } else {
                                R.string.installed_apps_no_matches
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(state.visibleApps, key = { it.packageName }) { app ->
            AppLocaleCard(
                app = app,
                loadIcon = viewModel::loadIcon,
                onChangeOwnLanguage = { pickerMode = PickerMode.OWN },
                onOpenAppSettings = { viewModel.openAppSettings(app.packageName) }
            )
        }

        item(key = "apps-note") {
            InfoNote(
                text = stringResource(R.string.installed_apps_self_hint),
                icon = Icons.Filled.Info
            )
        }
    }

    when (pickerMode) {
        PickerMode.NONE -> Unit
        PickerMode.OWN -> LanguagePickerDialog(
            title = stringResource(R.string.app_language_title),
            note = stringResource(R.string.language_picker_note),
            current = state.ownOverride,
            choices = remember { appChoices() },
            allowSystemDefault = true,
            applying = state.applying,
            onPick = {
                pickerMode = PickerMode.NONE
                viewModel.applyOwnLocale(it)
            },
            onUseSystemDefault = {
                pickerMode = PickerMode.NONE
                viewModel.clearOwnLocale()
            },
            onDismiss = { pickerMode = PickerMode.NONE }
        )
        PickerMode.SYSTEM -> LanguagePickerDialog(
            title = stringResource(R.string.system_language_picker_title),
            note = stringResource(R.string.system_language_picker_note),
            current = state.system?.primary,
            choices = remember { LocaleCatalog.systemChoices() },
            allowSystemDefault = false,
            applying = state.applying,
            onPick = { tag ->
                pickerMode = PickerMode.NONE
                confirmSystemChange = tag
            },
            onUseSystemDefault = { pickerMode = PickerMode.NONE },
            onDismiss = { pickerMode = PickerMode.NONE }
        )
    }

    val pending = confirmSystemChange
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmSystemChange = null },
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.system_language_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.system_language_confirm_message,
                        "${LocaleCode.displayName(pending)} ($pending)"
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSystemChange = null
                        viewModel.changeSystemLocale(pending)
                    }
                ) {
                    Text(stringResource(R.string.system_language_confirm_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSystemChange = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (confirmRestore) {
        val snapshot = state.snapshot
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.system_language_restore_confirm_title)) },
            text = {
                Text(
                    snapshot?.let {
                        stringResource(
                            R.string.system_language_restore_confirm_message,
                            it.tags.joinToString(", ") { tag -> LocaleCode.displayName(tag) }
                        )
                    } ?: stringResource(R.string.system_language_restore_unavailable)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = false
                        viewModel.restoreSystemLocale()
                    },
                    enabled = snapshot != null
                ) {
                    Text(stringResource(R.string.system_language_restore_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

private enum class PickerMode { NONE, OWN, SYSTEM }

@Composable
private fun FilterRow(
    selected: AppLocaleFilter,
    onSelect: (AppLocaleFilter) -> Unit
) {
    val entries = AppLocaleFilter.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                shape = SegmentedButtonDefaults.itemShape(index, entries.size)
            ) {
                Text(
                    text = stringResource(filterLabel(filter)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun filterLabel(filter: AppLocaleFilter): Int = when (filter) {
    AppLocaleFilter.ALL -> R.string.apps_filter_all
    AppLocaleFilter.OVERRIDDEN -> R.string.apps_filter_overridden
    AppLocaleFilter.SYSTEM_DEFAULT -> R.string.apps_filter_system_default
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemLanguageCard(
    state: LanguageUiState,
    onOpenSystemLanguageSettings: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onChange: () -> Unit,
    onRestore: () -> Unit
) {
    SectionCard(
        title = stringResource(R.string.system_language_title),
        subtitle = stringResource(R.string.system_language_summary)
    ) {
        val system = state.system
        if (system == null || (system.primary == null && system.all.isEmpty())) {
            Text(
                text = stringResource(R.string.system_language_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SystemLocaleDetails(system)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text(
            text = stringResource(R.string.system_language_change_title),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = stringResource(R.string.system_language_mechanism),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!state.canWriteSystemLocale) {
            InfoNote(
                text = stringResource(R.string.system_language_no_access),
                icon = Icons.Filled.Info,
                tone = StatusTone.WARN
            )
            OutlinedButton(onClick = onOpenWriteSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.system_language_grant))
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onChange,
                enabled = state.canWriteSystemLocale && !state.applying
            ) {
                Text(stringResource(R.string.system_language_change_action))
            }
            OutlinedButton(onClick = onOpenSystemLanguageSettings) {
                Text(stringResource(R.string.system_language_open_settings))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text(
            text = stringResource(R.string.system_language_restore_title),
            style = MaterialTheme.typography.titleSmall
        )
        SnapshotDetails(state.snapshot)
        OutlinedButton(
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.snapshot != null && state.canWriteSystemLocale && !state.applying
        ) {
            Text(stringResource(R.string.system_language_restore_action))
        }
    }
}

@Composable
private fun SnapshotDetails(snapshot: SystemLocaleSnapshot?) {
    if (snapshot == null) {
        Text(
            text = stringResource(R.string.system_language_snapshot_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    KeyValueRow(
        label = stringResource(R.string.system_language_snapshot_saved),
        value = snapshot.tags.joinToString(", ") { tag -> LocaleCode.displayName(tag) }
    )
    KeyValueRow(
        label = stringResource(R.string.system_language_snapshot_codes),
        value = snapshot.tags.joinToString(", ")
    )
    KeyValueRow(
        label = stringResource(R.string.system_language_snapshot_created),
        value = formatTimestamp(snapshot.createdAt)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemLocaleDetails(system: SystemLocaleState) {
    val primary = system.primary ?: system.all.firstOrNull() ?: return
    KeyValueRow(
        label = stringResource(R.string.system_language_primary),
        value = LocaleCode.displayName(primary)
    )
    KeyValueRow(
        label = stringResource(R.string.system_language_identifier),
        value = primary
    )
    val native = LocaleCode.nativeName(primary)
    if (native != LocaleCode.displayName(primary)) {
        KeyValueRow(
            label = stringResource(R.string.system_language_native),
            value = native
        )
    }
    if (system.source == SystemLocaleSource.PLATFORM_DEFAULT) {
        Text(
            text = stringResource(R.string.system_language_source_config),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (system.all.size > 1) {
        Text(
            text = stringResource(R.string.system_language_fallbacks),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (tag in system.all.drop(1)) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnLanguageCard(
    state: LanguageUiState,
    onChange: () -> Unit,
    onClear: () -> Unit
) {
    val canWrite = state.canWriteOwnLocale
    SectionCard(
        title = stringResource(R.string.app_language_title),
        subtitle = stringResource(R.string.app_language_summary)
    ) {
        val current = state.ownOverride
        KeyValueRow(
            label = stringResource(R.string.app_language_current),
            value = if (current == null) {
                stringResource(R.string.system_default)
            } else {
                "${LocaleCode.displayName(current)} · $current"
            }
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onChange,
                enabled = canWrite && !state.applying
            ) {
                Text(stringResource(R.string.app_language_change))
            }
            OutlinedButton(
                onClick = onClear,
                enabled = canWrite && !state.applying && current != null
            ) {
                Text(stringResource(R.string.app_language_use_system))
            }
        }
        if (!canWrite) {
            InfoNote(
                text = stringResource(R.string.app_language_unsupported),
                icon = Icons.Filled.Info,
                tone = StatusTone.WARN
            )
        }
    }
}

@Composable
private fun AppReadAccessNote(state: LanguageUiState) {
    val capabilities = state.capabilities ?: return
    val text = when {
        !capabilities.can(LocaleCapability.READ_OTHER_APP_LOCALES) ->
            R.string.installed_apps_read_unsupported
        state.otherAppLocalesReadable -> R.string.installed_apps_read_available
        else -> R.string.installed_apps_read_denied
    }
    InfoNote(
        text = stringResource(text),
        icon = Icons.Filled.Info,
        tone = if (state.otherAppLocalesReadable) StatusTone.OK else StatusTone.WARN
    )
}

@Composable
private fun AppLocaleCard(
    app: InstalledAppLocale,
    loadIcon: suspend (String) -> Drawable?,
    onChangeOwnLanguage: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
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
            AppIcon(packageName = app.packageName, label = app.label, load = loadIcon)
            Spacer(Modifier.width(12.dp))
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
                Spacer(Modifier.height(4.dp))
                AppStatePill(app.state)
            }
            Spacer(Modifier.width(8.dp))
            if (app.isSelf) {
                TextButton(onClick = onChangeOwnLanguage) {
                    Text(stringResource(R.string.change_language))
                }
            } else {
                TextButton(onClick = onOpenAppSettings) {
                    Text(stringResource(R.string.installed_apps_open_settings))
                }
            }
        }
    }
}

@Composable
private fun AppStatePill(state: AppLocaleState) {
    when (state) {
        is AppLocaleState.Override -> StatusPill(
            text = stringResource(
                R.string.app_locale_override,
                LocaleCode.displayName(state.tag)
            ),
            tone = StatusTone.NEUTRAL
        )
        AppLocaleState.SystemDefault -> StatusPill(
            text = stringResource(R.string.system_default),
            tone = StatusTone.NEUTRAL
        )
        AppLocaleState.NotReadable -> StatusPill(
            text = stringResource(R.string.app_locale_not_readable),
            tone = StatusTone.WARN
        )
        AppLocaleState.Unsupported -> StatusPill(
            text = stringResource(R.string.app_locale_unavailable),
            tone = StatusTone.NEUTRAL
        )
    }
}

@Composable
private fun AppIcon(
    packageName: String,
    label: String,
    load: suspend (String) -> Drawable?
) {
    val density = LocalDensity.current
    val sizePx = with(density) { 40.dp.toPx().toInt().coerceAtLeast(1) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName, sizePx) {
        value = withContext(Dispatchers.IO) {
            load(packageName)?.toImageBitmapOrNull(sizePx)
        }
    }

    val shape = RoundedCornerShape(10.dp)
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(shape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    title: String,
    note: String,
    current: String?,
    choices: List<LocaleChoice>,
    allowSystemDefault: Boolean,
    applying: Boolean,
    onPick: (String) -> Unit,
    onUseSystemDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val options = remember(query, choices) { LocaleCatalog.filter(choices, query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.language_picker_search)) },
                    shape = MaterialTheme.shapes.large
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    if (allowSystemDefault) {
                        item(key = SupportedAppLocales.SYSTEM) {
                            LanguageOptionRow(
                                title = stringResource(R.string.system_default),
                                subtitle = null,
                                selected = current == null,
                                enabled = !applying,
                                onClick = onUseSystemDefault
                            )
                        }
                    }
                    items(options, key = { it.tag }) { choice ->
                        LanguageOptionRow(
                            title = choice.displayName,
                            subtitle = if (choice.nativeName != choice.displayName) {
                                "${choice.nativeName} · ${choice.tag}"
                            } else {
                                choice.tag
                            },
                            selected = current == choice.tag,
                            enabled = !applying,
                            onClick = { onPick(choice.tag) }
                        )
                    }
                    if (options.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.language_picker_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun LanguageOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/** The locales Mamori itself declares; anything else is rejected by LocaleManager. */
private fun appChoices(): List<LocaleChoice> = SupportedAppLocales.codes.map { code ->
    LocaleChoice(
        tag = code,
        displayName = LocaleCode.displayName(code),
        nativeName = LocaleCode.nativeName(code)
    )
}

private fun formatTimestamp(createdAt: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(createdAt))

private fun Drawable.toImageBitmapOrNull(sizePx: Int): ImageBitmap? = try {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    bitmap.asImageBitmap()
} catch (_: Throwable) {
    null
}

private fun messageText(context: Context, message: LanguageMessage): String = when (message) {
    is LanguageMessage.Applied -> context.getString(
        R.string.app_language_applied,
        LocaleCode.displayName(message.tag)
    )
    LanguageMessage.Cleared -> context.getString(R.string.app_language_cleared)
    is LanguageMessage.SystemApplied -> context.getString(
        R.string.system_language_applied,
        LocaleCode.displayName(message.tag),
        message.tag
    )
    is LanguageMessage.SystemRestored -> context.getString(
        R.string.system_language_restored,
        LocaleCode.displayName(message.tag)
    )
    is LanguageMessage.PendingRestart -> context.getString(
        R.string.system_language_pending_restart,
        LocaleCode.displayName(message.tag),
        message.tag
    )
    is LanguageMessage.Failed -> when (val reason = message.reason) {
        is LocaleFailure.Unsupported ->
            context.getString(R.string.app_language_failed, reason.detail)
        is LocaleFailure.Rejected ->
            context.getString(R.string.app_language_failed, reason.detail)
        is LocaleFailure.VerificationFailed -> context.getString(
            R.string.app_language_unverified,
            reason.expected ?: context.getString(R.string.system_default),
            reason.actual ?: context.getString(R.string.system_default)
        )
    }
}
