package com.sahayat.app.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sahayat.app.core.Format
import com.sahayat.app.data.model.EventLocation
import com.sahayat.app.ui.i18n.AppStrings
import com.sahayat.app.ui.navigation.Routes
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SType
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Locale

/**
 * OpenStreetMap view (osmdroid): free, no API key or billing. Shows the real reported position with its
 * accuracy circle. Map tiles need internet; without it the map is grey but the coordinates still show.
 */
@Composable
fun LocationMap(
    location: EventLocation,
    label: String?,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    zoom: Double = 17.0,
    /** For previews: taps go here instead of panning the map. */
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(
                if (interactive) CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
                else CustomZoomButtonsController.Visibility.NEVER,
            )
            isTilesScaledToDpi = true
            controller.setZoom(zoom)
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }
    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                val point = GeoPoint(location.lat, location.lng)
                mv.overlays.clear()
                location.accuracyM?.takeIf { it in 1.0..5_000.0 }?.let { acc ->
                    mv.overlays.add(
                        Polygon(mv).apply {
                            points = Polygon.pointsAsCircle(point, acc)
                            fillPaint.color = AColor.argb(40, 42, 201, 118)
                            outlinePaint.color = AColor.argb(160, 42, 201, 118)
                            outlinePaint.strokeWidth = 3f
                        },
                    )
                }
                mv.overlays.add(
                    Marker(mv).apply {
                        position = point
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = label
                    },
                )
                mv.controller.setCenter(point)
                mv.invalidate()
            },
        )
        if (onClick != null) {
            // Transparent layer on top so a tap opens the full map instead of panning the preview.
            Box(Modifier.fillMaxSize().clickable(onClick = onClick))
        }
        Text(
            "© OpenStreetMap contributors",
            fontSize = 9.sp,
            color = androidx.compose.ui.graphics.Color(0xFF333333),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
        )
    }
}

fun openDirections(context: Context, l: EventLocation) {
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${l.lat},${l.lng}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${l.lat},${l.lng}?q=${l.lat},${l.lng}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(String.format(Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f", l.lat, l.lng)),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(nav) }.isSuccess) return
    if (runCatching { context.startActivity(geo) }.isSuccess) return
    runCatching { context.startActivity(web) }
}

fun formatCoords(l: EventLocation): String =
    String.format(Locale.US, "%.5f, %.5f", l.lat, l.lng) + (l.accuracyM?.let { " · ±${it.toInt()} m" } ?: "")

/** Location card used on the SOS and history screens: live map preview + in-app map + directions. */
@Composable
fun EventLocationCard(location: EventLocation?, s: AppStrings, eventId: String?, patientName: String?) {
    SCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (location != null) SIcons.Location else SIcons.LocationOff, null,
                tint = if (location != null) SColors.Accent else SColors.TextTertiary,
            )
            Text("  ${s.location}", style = SType.CardTitle, modifier = Modifier.weight(1f))
            location?.capturedAtMs?.let { Text(s.locationUpdated(Format.relative(it)), style = SType.Caption) }
        }
        VSpace(8)
        if (location == null) {
            Text(s.locationUnavailable, style = SType.Body)
        } else {
            LocationDetails(location, s, eventId, patientName)
        }
    }
}

@Composable
private fun LocationDetails(location: EventLocation, s: AppStrings, eventId: String?, patientName: String?) {
    val context = LocalContext.current
    val navigate = LocalNavigator.current
    val open: () -> Unit = { navigate(Routes.map(location, patientName, eventId)) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(18.dp)),
    ) {
        LocationMap(location, patientName, Modifier.fillMaxSize(), interactive = false, zoom = 16.0, onClick = open)
    }
    VSpace(8)
        Text(formatCoords(location), style = SType.BodyStrong)
        location.source?.let { Text(s.locationSource(it), style = SType.Caption) }
        VSpace(10)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SButton(s.viewOnMap, open, Modifier.weight(1f), icon = SIcons.Location)
            SButton(s.directions, { openDirections(context, location) }, Modifier.weight(1f), kind = ButtonKind.Secondary)
        }
}
