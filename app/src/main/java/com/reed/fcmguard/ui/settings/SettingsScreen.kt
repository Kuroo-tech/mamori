package com.reed.fcmguard.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reed.fcmguard.LocaleTag
import com.reed.fcmguard.R
import com.reed.fcmguard.ui.components.InfoNote
import com.reed.fcmguard.ui.components.SectionCard
import com.reed.fcmguard.ui.components.StatusLine
import com.reed.fcmguard.ui.components.StatusTone
import com.reed.fcmguard.ui.theme.ThemeMode
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Settings.
 *
 * Appearance is applied by the caller immediately (no restart), while a language change
 * re-attaches the Activity context because the platform resolves resources at attach
 * time. Anything privileged stays behind the same verified execution layer: this screen
 * only persists configuration and opens system screens.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenWriteSettings: () -> Unit,
    onOpenFcmDiagnostics: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAutostart: () -> Unit,
    onOpenLanguageManager: () -> Unit,
    onLanguageChanged: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showLanguageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        launch { viewModel.messages.collect(onMessage) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermission() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "appearance") {
            SectionCard(
                title = stringResource(R.string.appearance_title),
                subtitle = stringResource(R.string.appearance_summary)
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = ThemeMode.entries
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size)
                        ) {
                            Text(stringResource(themeModeLabel(mode)))
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.dynamic_color_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (state.dynamicColorSupported) {
                                stringResource(R.string.dynamic_color_summary)
                            } else {
                                stringResource(R.string.dynamic_color_unsupported)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.dynamicColor && state.dynamicColorSupported,
                        onCheckedChange = viewModel::setDynamicColor,
                        enabled = state.dynamicColorSupported
                    )
                }
            }
        }

        item(key = "language") {
            SectionCard(
                title = stringResource(R.string.language),
                subtitle = stringResource(R.string.language_summary)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = languageLabel(state.language),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { showLanguageDialog = true }) {
                        Text(stringResource(R.string.change_language))
                    }
                }
                InfoNote(
                    text = stringResource(R.string.language_follow_system_note),
                    icon = Icons.Filled.Info
                )
            }
        }

        item(key = "language_manager") {
            SectionCard(
                title = stringResource(R.string.settings_language_manager_title),
                subtitle = stringResource(R.string.settings_language_manager_summary)
            ) {
                OutlinedButton(
                    onClick = onOpenLanguageManager,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_language_manager_open))
                }
            }
        }

        item(key = "advanced") {
            SectionCard(
                title = stringResource(R.string.advanced_title),
                subtitle = stringResource(R.string.advanced_summary)
            ) {
                OutlinedTextField(
                    value = state.key,
                    onValueChange = viewModel::setKey,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_key)) },
                    shape = MaterialTheme.shapes.small
                )
                OutlinedTextField(
                    value = state.requiredItem,
                    onValueChange = viewModel::setRequiredItem,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_item)) },
                    shape = MaterialTheme.shapes.small
                )
                FilledTonalButton(
                    onClick = viewModel::saveConfiguration,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.save_configuration))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                StatusLine(
                    text = if (state.canWrite) {
                        stringResource(R.string.status_granted)
                    } else {
                        stringResource(R.string.status_not_granted)
                    },
                    tone = if (state.canWrite) StatusTone.OK else StatusTone.WARN
                )
                if (!state.canWrite) {
                    OutlinedButton(
                        onClick = onOpenWriteSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.grant_modify))
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenDiagnostics) {
                        Text(stringResource(R.string.open_diagnostics))
                    }
                    OutlinedButton(onClick = onOpenFcmDiagnostics) {
                        Text(stringResource(R.string.open_fcm_diag))
                    }
                    OutlinedButton(onClick = onOpenAutostart) {
                        Text(stringResource(R.string.open_autostart_manager))
                    }
                }
            }
        }

        item(key = "about") {
            AboutCard(versionName = state.versionName)
        }
    }

    if (showLanguageDialog) {
        LanguageDialog(
            current = state.language,
            onSelect = {
                showLanguageDialog = false
                if (it != state.language) {
                    viewModel.setLanguage(it)
                    onLanguageChanged()
                }
            },
            onDismiss = { showLanguageDialog = false }
        )
    }
}

@Composable
private fun AboutCard(versionName: String) {
    SectionCard(title = stringResource(R.string.settings_about)) {
        Text(
            text = stringResource(R.string.about_version, versionName),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = stringResource(R.string.about_upstream_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = stringResource(R.string.about_license_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LanguageDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val labels = stringArrayResource(R.array.language_entries)
    val entries = LanguageOptions.codes.zip(labels.asList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column {
                for ((code, label) in entries) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(code) },
                        color = if (code == current) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
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
private fun languageLabel(code: String): String {
    if (code == LocaleTag.SYSTEM) return stringResource(R.string.follow_system)
    return try {
        Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())
    } catch (_: Throwable) {
        code
    }
}

private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

/**
 * Language codes stay explicit (not derived from the locale list) so the legacy
 * pre-API-33 path keeps working with exactly the tags it always stored.
 */
object LanguageOptions {
    /** Must stay in sync with `res/values/arrays.xml` and the `locale-config`. */
    val codes = listOf(
        "system", "en", "zh-CN", "zh-TW", "fr", "ja", "ko", "es", "pt", "de", "ru", "vi"
    )
}
