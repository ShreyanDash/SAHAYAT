package com.sahayat.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SDimens
import com.sahayat.app.ui.theme.SShapes
import com.sahayat.app.ui.theme.SType

enum class ButtonKind { Primary, Secondary, Danger, Ghost }

@Composable
fun SButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    icon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(SColors.Accent, Color(0xFF04140B), null)
        ButtonKind.Secondary -> Triple(SColors.CardAlt, SColors.TextPrimary, BorderStroke(1.dp, SColors.Stroke))
        ButtonKind.Danger -> Triple(SColors.Danger, Color.White, null)
        ButtonKind.Ghost -> Triple(Color.Transparent, SColors.TextSecondary, null)
    }
    InteractiveSurface(
        onClick = if (loading) null else onClick,
        shape = SShapes.Pill,
        color = bg,
        border = border,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val c = tint(fg)
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = c, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(icon, null, tint = c, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = SType.Button, color = c, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconTint: Color = SColors.TextPrimary,
) {
    InteractiveSurface(
        onClick = onClick,
        shape = CircleShape,
        color = SColors.Chip,
        border = BorderStroke(1.dp, SColors.Stroke),
        enabled = enabled,
        modifier = modifier.size(SDimens.IconButton + 4.dp),
    ) {
        Icon(icon, contentDescription, tint = tint(iconTint), modifier = Modifier.align(Alignment.Center).size(22.dp))
    }
}

@Composable
fun SCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = SColors.Card,
    padding: PaddingValues = PaddingValues(SDimens.CardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    InteractiveSurface(
        onClick = onClick,
        shape = SShapes.Card,
        color = color,
        border = BorderStroke(1.dp, SColors.Stroke),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** Rounded-square icon tile used at the start of rows and cards. */
@Composable
fun IconBadge(icon: ImageVector, color: Color = SColors.Accent, size: Int = 48) {
    val highlighted = LocalHighlighted.current
    val shape = RoundedCornerShape((size * 0.3f).dp)
    val bg = when {
        highlighted -> Color(0x14000000)
        color == SColors.Accent -> SColors.IconTile
        else -> color.copy(alpha = 0.12f)
    }
    Box(
        Modifier
            .size(size.dp)
            .background(bg, shape)
            .border(1.dp, if (highlighted) Color(0x33000000) else color.copy(alpha = 0.25f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint(color), modifier = Modifier.size((size * 0.46f).dp))
    }
}

/**
 * Row card matching the SAHAYAT reference: icon tile, centred bold title with a grey subtitle,
 * chevron. By default the card hugs its content (like the reference); pass [fullWidth] to stretch.
 */
@Composable
fun SListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconColor: Color = SColors.Accent,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    fullWidth: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    InteractiveSurface(
        onClick = onClick,
        shape = SShapes.CardSmall,
        color = SColors.Card,
        border = BorderStroke(1.dp, SColors.Stroke),
        modifier = modifier.then(if (fullWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = 260.dp)),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                IconBadge(icon, iconColor)
                Spacer(Modifier.width(18.dp))
            }
            Column(
                Modifier.weight(1f, fill = fullWidth),
                horizontalAlignment = if (fullWidth) Alignment.Start else Alignment.CenterHorizontally,
            ) {
                Text(
                    title, style = SType.CardTitle, color = tint(SColors.TextPrimary),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = if (fullWidth) TextAlign.Start else TextAlign.Center,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        subtitle, style = SType.Body, color = tint(SColors.TextSecondary),
                        textAlign = if (fullWidth) TextAlign.Start else TextAlign.Center,
                    )
                }
            }
            if (value != null) {
                Spacer(Modifier.width(10.dp))
                Text(value, style = SType.Caption, color = tint(SColors.TextSecondary), textAlign = TextAlign.End)
            }
            trailing?.invoke(this)
            if (showChevron) {
                Spacer(Modifier.width(16.dp))
                Icon(SIcons.Chevron, null, tint = tint(SColors.TextSecondary))
            }
        }
    }
}

/** Key/value line; a null value renders the honest "Unavailable" label. */
@Composable
fun InfoLine(label: String, value: String?, valueColor: Color = SColors.TextPrimary) {
    val s = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = SType.Body, color = tint(SColors.TextSecondary), modifier = Modifier.weight(1f))
        Text(
            value ?: s.unavailable,
            style = SType.BodyStrong,
            color = tint(if (value == null) SColors.TextTertiary else valueColor),
            textAlign = TextAlign.End,
        )
    }
}

enum class Tone { Ok, Warn, Danger, Neutral }

fun Tone.color(): Color = when (this) {
    Tone.Ok -> SColors.Accent
    Tone.Warn -> SColors.Warning
    Tone.Danger -> SColors.Danger
    Tone.Neutral -> SColors.Neutral
}

@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = tone.color()
    Row(
        modifier
            .background(c.copy(alpha = 0.15f), SShapes.Pill)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(c, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(text, style = SType.Caption, color = tint(if (tone == Tone.Neutral) SColors.TextSecondary else c))
    }
}

/** Section header ("Monitoring", "Device"), with an optional green text action on the right. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    if (text.isBlank() && action == null) {
        Spacer(modifier.height(4.dp))
        return
    }
    Row(
        modifier.fillMaxWidth().padding(start = 2.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = SType.Section, modifier = Modifier.weight(1f))
        if (action != null) {
            InteractiveSurface(onClick = action.second, shape = SShapes.Pill, color = Color.Transparent, gloss = false) {
                Text(
                    action.first,
                    style = SType.Button,
                    color = tint(SColors.Accent),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Pill tabs (History filters, pairing mode, sensitivity). */
@Composable
fun <T> SSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(SColors.Card, SShapes.Pill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            InteractiveSurface(
                onClick = { onSelect(value) },
                shape = SShapes.Pill,
                color = if (isSel) SColors.Accent else Color.Transparent,
                role = Role.Tab,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
            ) {
                Text(
                    label,
                    style = SType.Caption,
                    color = tint(if (isSel) Color(0xFF04140B) else SColors.TextSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp),
                )
            }
        }
    }
}

@Composable
fun STextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    minLines: Int = 1,
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = hint?.let { { Text(it, color = SColors.TextTertiary) } },
            isError = error != null,
            singleLine = singleLine,
            minLines = minLines,
            enabled = enabled,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
            shape = SShapes.Field,
            textStyle = SType.BodyStrong,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SColors.Accent,
                unfocusedBorderColor = SColors.Stroke,
                focusedLabelColor = SColors.Accent,
                unfocusedLabelColor = SColors.TextSecondary,
                cursorColor = SColors.Accent,
                focusedContainerColor = SColors.Card,
                unfocusedContainerColor = SColors.Card,
                disabledContainerColor = SColors.Card,
                errorContainerColor = SColors.Card,
                focusedTextColor = SColors.TextPrimary,
                unfocusedTextColor = SColors.TextPrimary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(error, style = SType.Caption, color = SColors.Danger, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        }
    }
}

@Composable
fun SSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    InteractiveSurface(
        onClick = { onCheckedChange(!checked) },
        shape = SShapes.CardSmall,
        color = SColors.Card,
        enabled = enabled,
        role = Role.Switch,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                IconBadge(icon)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = SType.BodyStrong, color = tint(SColors.TextPrimary))
                if (subtitle != null) Text(subtitle, style = SType.Caption, color = tint(SColors.TextSecondary))
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF04140B),
                    checkedTrackColor = SColors.Accent,
                    uncheckedThumbColor = SColors.TextSecondary,
                    uncheckedTrackColor = SColors.CardAlt,
                    uncheckedBorderColor = SColors.Stroke,
                ),
            )
        }
    }
}

@Composable
fun MetricTile(
    icon: ImageVector,
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Ok,
    onClick: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    InteractiveSurface(
        onClick = onClick,
        shape = SShapes.CardSmall,
        color = SColors.Card,
        border = BorderStroke(1.dp, SColors.Stroke),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = tint(if (value == null) SColors.TextTertiary else tone.color()), modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(12.dp))
            Text(label, style = SType.Caption, color = tint(SColors.TextSecondary))
            Spacer(Modifier.height(2.dp))
            Text(
                value ?: s.unavailable,
                style = if (value == null) SType.BodyStrong else SType.Metric,
                color = tint(if (value == null) SColors.TextTertiary else SColors.TextPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun Banner(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector? = null, action: Pair<String, () -> Unit>? = null) {
    val c = tone.color()
    Row(
        modifier
            .fillMaxWidth()
            .background(c.copy(alpha = 0.12f), SShapes.CardSmall)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon ?: when (tone) {
                Tone.Ok -> SIcons.Check
                Tone.Warn -> SIcons.Warning
                Tone.Danger -> SIcons.Error
                Tone.Neutral -> SIcons.Info
            },
            null, tint = c, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = SType.Caption, color = SColors.TextPrimary, modifier = Modifier.weight(1f))
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            InteractiveSurface(onClick = action.second, shape = SShapes.Pill, color = c.copy(alpha = 0.2f)) {
                Text(action.first, style = SType.Caption, color = tint(SColors.TextPrimary), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, SColors.TextSecondary, size = 64)
        Spacer(Modifier.height(16.dp))
        Text(title, style = SType.CardTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = SType.Body, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            SButton(action.first, action.second)
        }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val s = LocalStrings.current
    EmptyState(
        icon = SIcons.CloudOff,
        title = s.unavailable,
        body = message,
        modifier = modifier,
        action = onRetry?.let { s.retry to it },
    )
}

@Composable
fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = SColors.Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SColors.CardAlt,
        shape = SShapes.Card,
        title = { Text(title, style = SType.CardTitle) },
        text = { Text(text, style = SType.Body) },
        confirmButton = {
            SButton(confirmLabel, onConfirm, kind = if (danger) ButtonKind.Danger else ButtonKind.Primary)
        },
        dismissButton = { SButton(s.cancel, onDismiss, kind = ButtonKind.Ghost) },
    )
}

/** Remembers a boolean dialog flag. */
@Composable
fun rememberFlag(initial: Boolean = false) = remember { mutableStateOf(initial) }

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))
