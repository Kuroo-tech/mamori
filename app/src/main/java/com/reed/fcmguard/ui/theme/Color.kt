package com.reed.fcmguard.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Static fallback palette, used when dynamic color is unavailable or turned off.
 *
 * The palette is tonal-M3-shaped from the app's historical accent (#0B57D0) so the
 * redesign stays recognisable next to the old View UI. Only Material 3 color roles are
 * exposed: no screen should reference a raw hex value.
 */
internal val LightPrimary = Color(0xFF0B57D0)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFD8E2FF)
internal val LightOnPrimaryContainer = Color(0xFF001A41)

internal val LightSecondary = Color(0xFF565E71)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFDAE2F9)
internal val LightOnSecondaryContainer = Color(0xFF131C2B)

internal val LightTertiary = Color(0xFF6E5676)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFF7D8FF)
internal val LightOnTertiaryContainer = Color(0xFF28132F)

internal val LightError = Color(0xFFBA1A1A)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFFFDAD6)
internal val LightOnErrorContainer = Color(0xFF410002)

internal val LightBackground = Color(0xFFFDFBFF)
internal val LightOnBackground = Color(0xFF1A1C1E)
internal val LightSurface = Color(0xFFFDFBFF)
internal val LightOnSurface = Color(0xFF1A1C1E)
internal val LightSurfaceVariant = Color(0xFFE1E2EC)
internal val LightOnSurfaceVariant = Color(0xFF44474F)
internal val LightOutline = Color(0xFF74777F)
internal val LightOutlineVariant = Color(0xFFC4C6D0)
internal val LightSurfaceContainerLow = Color(0xFFF7F7FB)
internal val LightSurfaceContainer = Color(0xFFF1F2F7)
internal val LightSurfaceContainerHigh = Color(0xFFEBECF1)
internal val LightInverseSurface = Color(0xFF2F3033)
internal val LightInverseOnSurface = Color(0xFFF1F0F4)

internal val DarkPrimary = Color(0xFFADC6FF)
internal val DarkOnPrimary = Color(0xFF002E69)
internal val DarkPrimaryContainer = Color(0xFF004494)
internal val DarkOnPrimaryContainer = Color(0xFFD8E2FF)

internal val DarkSecondary = Color(0xFFBEC6DC)
internal val DarkOnSecondary = Color(0xFF283141)
internal val DarkSecondaryContainer = Color(0xFF3E4759)
internal val DarkOnSecondaryContainer = Color(0xFFDAE2F9)

internal val DarkTertiary = Color(0xFFDBBCE3)
internal val DarkOnTertiary = Color(0xFF3E2846)
internal val DarkTertiaryContainer = Color(0xFF553F5D)
internal val DarkOnTertiaryContainer = Color(0xFFF7D8FF)

internal val DarkError = Color(0xFFFFB4AB)
internal val DarkOnError = Color(0xFF690005)
internal val DarkErrorContainer = Color(0xFF93000A)
internal val DarkOnErrorContainer = Color(0xFFFFDAD6)

internal val DarkBackground = Color(0xFF111318)
internal val DarkOnBackground = Color(0xFFE2E2E9)
internal val DarkSurface = Color(0xFF111318)
internal val DarkOnSurface = Color(0xFFE2E2E9)
internal val DarkSurfaceVariant = Color(0xFF44474F)
internal val DarkOnSurfaceVariant = Color(0xFFC4C6D0)
internal val DarkOutline = Color(0xFF8E9099)
internal val DarkOutlineVariant = Color(0xFF44474F)
internal val DarkSurfaceContainerLow = Color(0xFF191C20)
internal val DarkSurfaceContainer = Color(0xFF1D2024)
internal val DarkSurfaceContainerHigh = Color(0xFF282A2F)
internal val DarkInverseSurface = Color(0xFFE2E2E9)
internal val DarkInverseOnSurface = Color(0xFF2F3033)

/**
 * Semantic status colors. Material 3 has no "success"/"warning" role, so these live
 * next to the scheme instead of being hardcoded per composable. Both variants keep a
 * contrast ratio above 4.5:1 against their container.
 */
internal val LightStatusOk = Color(0xFF116D33)
internal val LightStatusOkContainer = Color(0xFFB7F0C8)
internal val LightStatusWarn = Color(0xFF7A5900)
internal val LightStatusWarnContainer = Color(0xFFFFE08C)
internal val DarkStatusOk = Color(0xFF7DDA9C)
internal val DarkStatusOkContainer = Color(0xFF005322)
internal val DarkStatusWarn = Color(0xFFF5C44E)
internal val DarkStatusWarnContainer = Color(0xFF5C4300)
