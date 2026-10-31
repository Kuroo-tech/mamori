package com.reed.fcmguard.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.reed.fcmguard.HyperOsSettings
import com.reed.fcmguard.R
import com.reed.fcmguard.ui.diagnostics.DiagnosticsScreen
import com.reed.fcmguard.ui.fcm.FcmScreen
import com.reed.fcmguard.ui.history.HistoryScreen
import com.reed.fcmguard.ui.home.HomeScreen
import com.reed.fcmguard.ui.language.LanguageManagerScreen
import com.reed.fcmguard.ui.settings.SettingsScreen
import com.reed.fcmguard.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

private enum class Destination(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector
) {
    HOME("home", R.string.nav_home, Icons.Filled.Home),
    FCM("fcm", R.string.nav_apps, Icons.Filled.Notifications),
    HISTORY("history", R.string.nav_history, Icons.AutoMirrored.Filled.List),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings)
}

/** Diagnostics and the language manager are reached from Settings, not the bottom bar. */
private const val DIAGNOSTICS_ROUTE = "diagnostics"
private const val LANGUAGE_ROUTE = "language"

private val topLevelDestinations = Destination.entries.toList()

/**
 * The navigation shell.
 *
 * Four top-level destinations map to the four things the app does: observe, inspect FCM
 * apps, audit history, configure. Screens receive callbacks for anything that needs an
 * Activity (permission screens, diagnostics) and never start activities themselves.
 */
@Composable
fun MamoriApp(
    settingsViewModel: SettingsViewModel,
    onOpenWriteSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenFcmDiagnostics: () -> Unit,
    onOpenAutostart: () -> Unit,
    onShareLog: (String) -> Unit,
    onLanguageChanged: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                for (destination in topLevelDestinations) {
                    NavigationBarItem(
                        // Diagnostics is a child of Settings, so Settings stays selected
                        // while the user is inside it.
                        selected = currentRoute == destination.route ||
                            (destination == Destination.SETTINGS &&
                                currentRoute in setOf(DIAGNOSTICS_ROUTE, LANGUAGE_ROUTE)),
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.testTag("nav_${destination.route}"),
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(destination.label)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.HOME.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Destination.HOME.route) {
                HomeScreen(
                    onOpenWriteSettings = onOpenWriteSettings,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onOpenHistory = { navController.navigate(Destination.HISTORY.route) },
                    onMessage = showMessage
                )
            }
            composable(Destination.FCM.route) {
                FcmScreen(onOpenAutostart = onOpenAutostart)
            }
            composable(Destination.HISTORY.route) {
                HistoryScreen()
            }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onOpenWriteSettings = onOpenWriteSettings,
                    onOpenFcmDiagnostics = onOpenFcmDiagnostics,
                    onOpenDiagnostics = { navController.navigate(DIAGNOSTICS_ROUTE) },
                    onOpenAutostart = onOpenAutostart,
                    onOpenLanguageManager = { navController.navigate(LANGUAGE_ROUTE) },
                    onLanguageChanged = onLanguageChanged,
                    onMessage = showMessage
                )
            }
            composable(LANGUAGE_ROUTE) {
                LanguageManagerScreen(
                    onOpenSystemLanguageSettings = {
                        HyperOsSettings.openSystemLanguageSettings(context)
                    },
                    onOpenWriteSettings = onOpenWriteSettings,
                    onOpenAppSettings = { packageName ->
                        HyperOsSettings.openAppInfo(context, packageName)
                    },
                    onLocaleChanged = onLanguageChanged,
                    onMessage = showMessage
                )
            }
            composable(DIAGNOSTICS_ROUTE) {
                DiagnosticsScreen(onShareLog = onShareLog)
            }
        }
    }
}
