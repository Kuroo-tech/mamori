package com.reed.fcmguard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Navigation and settings smoke tests.
 *
 * These are compiled on every build but require a device or emulator to execute. They
 * assert only what is deterministic without a write permission: which screen is shown,
 * which segment is selected, and that the language dialog lists every declared locale.
 * Nothing here writes the system setting.
 */
@RunWith(AndroidJUnit4::class)
class MamoriNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun text(resId: Int): String = compose.activity.getString(resId)

    @Test
    fun bottomBarReachesEveryTopLevelScreen() {
        compose.onNodeWithText(text(R.string.status_title)).assertIsDisplayed()

        compose.onNodeWithTag("nav_fcm").performClick()
        compose.onNodeWithText(text(R.string.fcm_apps_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.fcm_apps_not_scanned)).assertIsDisplayed()

        compose.onNodeWithTag("nav_history").performClick()
        compose.onNodeWithText(text(R.string.operation_history)).assertIsDisplayed()

        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithText(text(R.string.appearance_title)).assertIsDisplayed()

        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithText(text(R.string.protection_title)).assertIsDisplayed()
    }

    @Test
    fun themeSegmentedButtonsReflectTheSelection() {
        compose.onNodeWithTag("nav_settings").performClick()

        compose.onNodeWithText(text(R.string.theme_dark)).performClick()
        compose.onNodeWithText(text(R.string.theme_dark)).assertIsSelected()

        compose.onNodeWithText(text(R.string.theme_light)).performClick()
        compose.onNodeWithText(text(R.string.theme_light)).assertIsSelected()

        compose.onNodeWithText(text(R.string.theme_system)).performClick()
        compose.onNodeWithText(text(R.string.theme_system)).assertIsSelected()
    }

    @Test
    fun languageDialogListsVietnamese() {
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithText(text(R.string.change_language)).performClick()

        // The entries are the native language names from values/arrays.xml.
        compose.onNodeWithText("Tiếng Việt").assertIsDisplayed()
        compose.onNodeWithText("English").assertIsDisplayed()
    }
}
