package com.sahayat.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.sahayat.app.ui.theme.SColors

/** True while the enclosing interactive surface is hovered, pressed or focused. */
val LocalHighlighted = compositionLocalOf { false }

/**
 * Content color that follows the global interaction rule:
 * cream surface → black text/icons, otherwise [normal].
 */
@Composable
fun tint(normal: Color): Color {
    val target = if (LocalHighlighted.current) SColors.OnCream else normal
    return animateColorAsState(target, tween(140), label = "tint").value
}

/**
 * The single interactive surface used by buttons, cards, rows, tabs and nav items.
 * Hover / press / keyboard focus → cream background + black content, with a subtle press scale.
 */
@Composable
fun InteractiveSurface(
    onClick: (() -> Unit)?,
    shape: Shape,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    border: BorderStroke? = null,
    role: Role = Role.Button,
    gloss: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val highlighted = onClick != null && enabled && (hovered || pressed || focused)

    val bg by animateColorAsState(if (highlighted) SColors.Cream else color, tween(140), label = "bg")
    val scale by animateFloatAsState(if (pressed && enabled && onClick != null) 0.97f else 1f, tween(110), label = "scale")
    val alpha = if (enabled) 1f else 0.45f

    var m = modifier
        .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
        .clip(shape)
        .background(bg, shape)
    if (gloss && !highlighted && color.alpha > 0f) m = m.background(SColors.cardGloss, shape)
    if (border != null && !highlighted) m = m.border(border, shape)
    if (onClick != null) {
        m = m
            .hoverable(source, enabled)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
    }
    CompositionLocalProvider(LocalHighlighted provides highlighted) {
        Box(m, content = content)
    }
}
