package com.reed.fcmguard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reed.fcmguard.R
import com.reed.fcmguard.ui.theme.LocalStatusColors

/** Four honest tones. There is deliberately no "success-ish" fifth state. */
enum class StatusTone { OK, WARN, ERROR, NEUTRAL }

@Composable
fun toneColor(tone: StatusTone): Color {
    val colors = LocalStatusColors.current
    return when (tone) {
        StatusTone.OK -> colors.ok
        StatusTone.WARN -> colors.warn
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
        StatusTone.NEUTRAL -> colors.neutral
    }
}

@Composable
fun toneContainer(tone: StatusTone): Color {
    val colors = LocalStatusColors.current
    return when (tone) {
        StatusTone.OK -> colors.okContainer
        StatusTone.WARN -> colors.warnContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        StatusTone.NEUTRAL -> colors.neutralContainer
    }
}

/**
 * A tinted container for one part of a screen. Tonal surfaces are used instead of
 * elevational shadows so the dashboard stays readable on both light and dark HyperOS
 * themes.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    tonal: Boolean = true,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (tonal) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (title != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            content()
        }
    }
}

/**
 * A short status value with a colored dot. The dot is never the only signal: the text
 * itself states the status, so a color-blind or monochrome reading is still correct.
 */
@Composable
fun StatusLine(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Decorative: the adjacent text carries the status, so no semantics are attached.
        Surface(
            modifier = Modifier.size(10.dp),
            shape = CircleShape,
            color = toneColor(tone)
        ) {}
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** A compact pill for a tone-carrying label (for example an Autostart state). */
@Composable
fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = toneContainer(tone),
        contentColor = toneColor(tone)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/**
 * Renders the comma-separated whitelist as individual chips. Packages that Mamori
 * recognises (the required item and itself) stay emphasized; everything else is shown
 * verbatim, because the list belongs to the user, not to the app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PackageChips(
    whitelist: String?,
    requiredItem: String,
    ownPackage: String,
    modifier: Modifier = Modifier
) {
    val packages = remember(whitelist) {
        whitelist.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    if (packages.isEmpty()) {
        Text(
            text = stringResource(R.string.no_packages_listed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier
        )
        return
    }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (packageName in packages) {
            val emphasized = packageName == requiredItem || packageName == ownPackage
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = if (emphasized) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (emphasized) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/** Informational note with an icon. Used for explanatory copy, never for fake status. */
@Composable
fun InfoNote(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.NEUTRAL
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = toneContainer(tone).copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = toneColor(tone),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Label in the secondary color, value in the primary color. Long values wrap. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
