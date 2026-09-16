package com.sahayat.app.ui.screens.map

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahayat.app.core.Format
import com.sahayat.app.core.Remote
import com.sahayat.app.core.valueOrNull
import com.sahayat.app.data.model.EventLocation
import com.sahayat.app.location.PhoneLocation
import com.sahayat.app.ui.LocalContainer
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.LocationMap
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.formatCoords
import com.sahayat.app.ui.components.openDirections
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.flow.flowOf

/**
 * Full-screen in-app map of an alert's location. If [eventId] is given, the position follows the live
 * updates the patient's phone writes to the event.
 */
@Composable
fun MapScreen(initial: EventLocation, label: String?, eventId: String?, onBack: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val container = LocalContainer.current
    val flow = remember(eventId) {
        if (eventId.isNullOrBlank()) flowOf(Remote.Loading) else container.alertService.observeEvent(eventId)
    }
    val event by flow.collectAsStateWithLifecycle(Remote.Loading)
    val location = event.valueOrNull?.location ?: initial

    ScreenScaffold(
        title = label?.ifBlank { null } ?: s.location,
        eyebrow = s.mapTitle,
        onBack = onBack,
        scrollable = false,
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SButton(s.directions, { openDirections(context, location) }, Modifier.weight(1f), icon = SIcons.Location)
                SButton(
                    s.share,
                    {
                        val text = listOfNotNull(label?.ifBlank { null }, PhoneLocation.mapsLink(location)).joinToString(": ")
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        runCatching { context.startActivity(Intent.createChooser(send, s.share)) }
                    },
                    Modifier.weight(1f),
                    kind = ButtonKind.Secondary,
                    icon = SIcons.Share,
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(formatCoords(location), style = SType.BodyStrong)
            location.capturedAtMs?.let { Text(s.locationUpdated(Format.relative(it)), style = SType.Caption) }
            if (!eventId.isNullOrBlank()) Text(s.liveLocationNote, style = SType.Caption)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(22.dp)),
        ) {
            LocationMap(location, label, Modifier.fillMaxSize(), interactive = true)
        }
    }
}
