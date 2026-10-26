package app.todo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.todo.ui.theme.LocalTodoPalette

/**
 * Neumorphic (soft UI) surface.
 *
 * The depth comes from two coloured shadows rendered by the platform shadow
 * renderer: a light one pushed up-left and a tinted one pushed down-right. No
 * blur filter is emulated with drawBehind, so the effect is hardware accelerated
 * on every supported device.
 *
 * A pressed surface flattens (smaller elevation, sunken fill) instead of showing
 * a fake inner shadow — one consistent pressed state for every control.
 */
@Composable
fun NeumorphicSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 6.dp,
    pressed: Boolean = false,
    color: Color = LocalTodoPalette.current.surface,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalTodoPalette.current
    val lightShadow = palette.shadowLight.copy(alpha = if (palette.isDark) 0.40f else 0.95f)
    val darkShadow = palette.shadowDark.copy(alpha = if (palette.isDark) 0.85f else 0.55f)
    val effectiveElevation = if (pressed) elevation / 3 else elevation
    val shift = if (pressed) 0.5.dp else 1.5.dp

    Box(modifier, contentAlignment = contentAlignment) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = -shift, y = -shift)
                .shadow(effectiveElevation, shape, clip = false, ambientColor = lightShadow, spotColor = lightShadow),
        )
        Box(
            Modifier
                .matchParentSize()
                .offset(x = shift, y = shift)
                .shadow(effectiveElevation, shape, clip = false, ambientColor = darkShadow, spotColor = darkShadow),
        )
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(if (pressed) palette.surfaceSunken else color),
        )
        Box(Modifier.padding(contentPadding), contentAlignment = contentAlignment, content = content)
    }
}

@Composable
fun NeumorphicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val palette = LocalTodoPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    NeumorphicSurface(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = if (accent) 8.dp else 5.dp,
        pressed = pressed,
        color = when {
            !enabled -> palette.surfaceSunken
            accent -> palette.accent
            else -> palette.surface
        },
        contentPadding = contentPadding,
        // Without this the content box is top-start aligned, so a full-width button
        // shows its label on the left instead of centred.
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

@Composable
fun NeumorphicIconButton(
    kind: TodoIconKind,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    iconSize: Dp = 22.dp,
    accent: Boolean = false,
    enabled: Boolean = true,
) {
    val palette = LocalTodoPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    NeumorphicSurface(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = CircleShape,
        elevation = if (accent) 9.dp else 5.dp,
        pressed = pressed,
        color = if (accent) palette.accent else palette.surface,
        contentAlignment = Alignment.Center,
    ) {
        TodoIcon(
            kind = kind,
            contentDescription = contentDescription,
            size = iconSize,
            tint = if (accent) palette.onAccent else palette.textPrimary,
        )
    }
}

@Composable
fun NeumorphicChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
) {
    val palette = LocalTodoPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    NeumorphicSurface(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Tab,
            onClick = onClick,
        ),
        shape = CircleShape,
        elevation = if (selected) 6.dp else 3.dp,
        pressed = pressed,
        color = if (selected) palette.accent else palette.surface,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dotColor != null) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) palette.onAccent else palette.textSecondary,
            )
        }
    }
}
