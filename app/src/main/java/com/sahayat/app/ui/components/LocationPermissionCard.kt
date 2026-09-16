package com.sahayat.app.ui.components

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sahayat.app.location.PhoneLocation
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SType

/**
 * Patient phone: walks through the location permissions an alert needs —
 * 1) precise location, 2) "Allow all the time" (alerts happen in the background), 3) Location switched on.
 * Shows the real current state; nothing is assumed.
 */
@Composable
fun LocationPermissionCard() {
    val s = LocalStrings.current
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val fg = remember(tick) { PhoneLocation.hasPermission(context) }
    val bg = remember(tick) { PhoneLocation.hasBackground(context) }
    val on = remember(tick) { PhoneLocation.locationOn(context) }

    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (!granted) {
            // Some phones only offer "Allow all the time" in the app's settings page.
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    val ready = fg && bg && on
    SCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (ready) SIcons.Location else SIcons.LocationOff, if (ready) SColors.Accent else SColors.Warning)
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text(s.locationShareTitle, style = SType.CardTitle)
                Text(
                    when {
                        ready -> s.locationShareOn
                        !fg -> s.locationShareBody
                        !bg -> s.backgroundLocationBody
                        else -> s.locationOffBody
                    },
                    style = SType.Caption,
                )
            }
        }
        if (!ready) {
            VSpace(12)
            when {
                !fg -> SButton(
                    s.allow,
                    {
                        fgLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Secondary,
                )
                !bg && Build.VERSION.SDK_INT >= 29 -> SButton(
                    s.allowAllTheTime,
                    { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Secondary,
                )
                !on -> SButton(
                    s.turnOnLocation,
                    {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Secondary,
                )
            }
        }
    }
}
