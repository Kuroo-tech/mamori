package com.reed.fcmguard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reed.fcmguard.core.log.MamoriLog
import com.reed.fcmguard.ui.MamoriApp
import com.reed.fcmguard.ui.settings.SettingsViewModel
import com.reed.fcmguard.ui.theme.MamoriTheme
import com.reed.fcmguard.ui.theme.ThemeMode
import java.util.Locale

/**
 * The only Activity.
 *
 * It owns three things the Compose tree cannot: the localized/theme-overridden base
 * context, system bar appearance, and starting system screens. Everything else lives in
 * Compose and the verified execution layer.
 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Locale first so the theme override is built on the localized configuration.
        super.attachBaseContext(ThemeHelper.apply(LocaleHelper.apply(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.migrateLegacyPreference(this)
        configureWindow()
        MamoriLog.attach(applicationContext)
        MamoriLog.info(TAG, "MainActivity created")
        setContent { MamoriRoot() }
    }

    override fun onResume() {
        super.onResume()
        configureWindow()
    }

    @Composable
    private fun MamoriRoot() {
        val settingsViewModel: SettingsViewModel = viewModel()
        val settings by settingsViewModel.state.collectAsStateWithLifecycle()
        val dark = when (settings.themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
        val view = LocalView.current
        SideEffect {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }

        MamoriTheme(
            mode = settings.themeMode,
            dynamicColor = settings.dynamicColor && settings.dynamicColorSupported
        ) {
            MamoriApp(
                settingsViewModel = settingsViewModel,
                onOpenWriteSettings = ::openWriteSettings,
                onOpenNotificationSettings = ::openNotificationSettings,
                onOpenFcmDiagnostics = ::openFcmDiagnostics,
                onOpenAutostart = ::openAutostartManager,
                onShareLog = ::shareLog,
                onLanguageChanged = { recreate() }
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun openWriteSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.permission_missing))
        } catch (_: Throwable) {
            toast(getString(R.string.permission_missing))
        }
    }

    private fun openNotificationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            }
            startActivity(intent)
        } catch (_: Throwable) {
            // Some ROMs hide this screen; the dashboard status already explains the mode.
        }
    }

    private fun openAutostartManager() {
        if (!HyperOsSettings.openAutoStartManager(this)) {
            toast(getString(R.string.autostart_manager_unavailable))
        }
    }

    private fun openFcmDiagnostics() {
        val knownActivities = listOf(
            "com.google.android.gms.gcm.GcmDiagnostics",
            "com.google.android.gms.gtalkservice.diagnostics.GTalkServiceDiagnostics"
        )
        for (className in knownActivities) {
            if (startPlayServicesActivity(className)) return
        }

        try {
            val info = packageManager.getPackageInfo(
                "com.google.android.gms",
                PackageManager.GET_ACTIVITIES
            )
            val activities = info.activities
            if (activities != null) {
                for (activity: ActivityInfo in activities) {
                    val name = activity.name ?: continue
                    val lower = name.lowercase(Locale.ROOT)
                    val diagnostics = lower.contains("gcm") ||
                        lower.contains("fcm") ||
                        lower.contains("gtalk")
                    if (diagnostics && lower.contains("diagnostic")) {
                        if (startPlayServicesActivity(name)) return
                    }
                }
            }
        } catch (_: Throwable) {
            // Diagnostics are best-effort: the fallback message below is accurate.
        }

        toast(getString(R.string.diagnostics_unavailable))
    }

    private fun startPlayServicesActivity(className: String): Boolean = try {
        startActivity(
            Intent().apply {
                setClassName("com.google.android.gms", className)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        true
    } catch (_: Throwable) {
        false
    }

    /** Shares the plain-text log through the system chooser; no file provider needed. */
    private fun shareLog(text: String) {
        if (text.isBlank()) {
            toast(getString(R.string.diagnostics_log_empty))
            return
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_title))
            .putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_share_log)))
        } catch (_: Throwable) {
            toast(getString(R.string.diagnostics_share_unavailable))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
