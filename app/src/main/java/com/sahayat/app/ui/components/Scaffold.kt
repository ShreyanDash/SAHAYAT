package com.sahayat.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.unit.sp
import com.sahayat.app.ui.navigation.Routes
import com.sahayat.app.ui.theme.SShapes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SDimens
import com.sahayat.app.ui.theme.SType

/** Global app status shown as banners on every screen. */
data class GlobalStatus(val online: Boolean = true, val preview: Boolean = false, val backendConfigured: Boolean = true)

val LocalGlobalStatus = compositionLocalOf { GlobalStatus() }

/** Whether the screen is drawn inside the tab host (which already pads for the bottom bar). */
val LocalInTabs = compositionLocalOf { false }

/** Navigation hook for the header menu; provided by SahayatRoot. */
val LocalNavigator = compositionLocalOf<(String) -> Unit> { {} }

/** Glossy black background: deep vertical gradient plus a soft top-left sheen. */
@Composable
fun GradientBackground(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SColors.background)
            .background(SColors.sheen),
    ) { content() }
}

@Composable
fun StatusBanners() {
    val g = LocalGlobalStatus.current
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (g.preview) Banner(s.previewBanner, Tone.Warn, icon = SIcons.Info)
        else if (!g.backendConfigured) Banner(s.backendBanner, Tone.Warn, icon = SIcons.CloudOff)
        if (!g.online) Banner(s.offlineBanner, Tone.Neutral, icon = SIcons.WifiOff)
    }
}

/** Circular menu button with quick links, shown top-right on every screen. */
@Composable
private fun HeaderMenu() {
    val s = LocalStrings.current
    val navigate = LocalNavigator.current
    var open by remember { mutableStateOf(false) }
    Box {
        SIconButton(SIcons.Menu, s.menu, { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = SColors.CardAlt,
            shape = SShapes.CardSmall,
        ) {
            listOf(
                s.notifications to Routes.NOTIFICATION_SETTINGS,
                s.deviceTitle to Routes.DEVICE,
                s.diagnostics to Routes.DIAGNOSTICS,
                s.profile to Routes.PROFILE,
                s.language to Routes.LANGUAGE,
                s.helpAbout to Routes.HELP,
            ).forEach { (label, route) ->
                DropdownMenuItem(
                    text = { Text(label, style = SType.BodyStrong) },
                    onClick = {
                        open = false
                        navigate(route)
                    },
                )
            }
        }
    }
}

/**
 * Standard screen: glossy background, circular back / menu buttons, green eyebrow,
 * large title, global status banners and scrollable content.
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    eyebrow: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    showMenu: Boolean = true,
    scrollable: Boolean = true,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalStrings.current
    val inTabs = LocalInTabs.current
    GradientBackground {
        Column(
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .then(if (inTabs) Modifier else Modifier.navigationBarsPadding())
                .imePadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = SDimens.ScreenPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) SIconButton(SIcons.Back, s.back, onBack)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                if (showMenu) {
                    Spacer(Modifier.width(8.dp))
                    HeaderMenu()
                }
            }
            val body = Modifier
                .weight(1f)
                .fillMaxWidth()
                .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
                .padding(horizontal = SDimens.ScreenPadding)
            Column(body, verticalArrangement = Arrangement.spacedBy(SDimens.Gap)) {
                Column(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
                    if (eyebrow != null) {
                        Text(eyebrow.uppercase(), style = SType.Eyebrow)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(title, style = SType.Title, maxLines = 2)
                    if (subtitle != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(subtitle, style = SType.Body.copy(fontSize = 15.sp))
                    }
                }
                StatusBanners()
                content()
                Spacer(Modifier.height(24.dp))
            }
            if (bottom != null) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = SDimens.ScreenPadding, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = bottom,
                )
            }
        }
    }
}

/**
 * Purely decorative sensor ring. When [live] is false it is drawn static and dim, so it never
 * suggests that sensor data is flowing when it isn't.
 */
@Composable
fun SensorRing(live: Boolean, modifier: Modifier = Modifier, size: Dp = 220.dp, content: @Composable () -> Unit = {}) {
    val transition = rememberInfiniteTransition(label = "ring")
    val sweep by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "sweep")
    val pulse by transition.animateFloat(0.92f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "pulse")
    val color = if (live) SColors.Accent else SColors.Neutral
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val r = this.size.minDimension / 2 - stroke
            val scale = if (live) pulse else 1f
            drawCircle(color.copy(alpha = 0.10f), radius = r * scale)
            drawCircle(color.copy(alpha = 0.25f), radius = r * 0.78f, style = Stroke(1.dp.toPx()))
            drawCircle(color.copy(alpha = 0.35f), radius = r, style = Stroke(stroke))
            if (live) {
                rotate(sweep) {
                    drawArc(
                        color = color,
                        startAngle = 0f,
                        sweepAngle = 70f,
                        useCenter = false,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                        style = Stroke(stroke * 1.6f, cap = StrokeCap.Round),
                    )
                }
            } else {
                // Static dashed marks: "waiting", not "measuring".
                for (i in 0 until 12) {
                    rotate(i * 30f) {
                        drawLine(Color(0x556B7A86), Offset(center.x, center.y - r + 8f), Offset(center.x, center.y - r + 20f), strokeWidth = 2f)
                    }
                }
            }
        }
        content()
    }
}
