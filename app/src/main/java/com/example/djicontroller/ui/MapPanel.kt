package com.example.djicontroller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.PatrolRoute
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Trasa patrolu na mapie (sekcja 6): pozycja drona, punkt startu (stacja), trasa i jej punkty.
 *
 * Podkład: kafelki OpenStreetMap — wystarczające do testów. Przed lotami produkcyjnymi
 * podmień [MAP_STYLE_JSON] na własny serwer kafelków lub zdjęcia satelitarne (polityka OSM
 * nie pozwala na intensywne użycie ich serwerów).
 */
@Composable
fun MapPanel(drone: DroneState, route: PatrolRoute?, modifier: Modifier = Modifier) {
    // Podgląd w Android Studio nie uruchamia natywnego MapView — rysujemy uproszczoną mapę.
    if (LocalInspectionMode.current) {
        MapPreviewSketch(drone, route, modifier)
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centered by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.setStyle(Style.Builder().fromJson(MAP_STYLE_JSON)) { loaded ->
                loaded.addSource(GeoJsonSource(SRC_ROUTE_LINE))
                loaded.addSource(GeoJsonSource(SRC_ROUTE_POINTS))
                loaded.addSource(GeoJsonSource(SRC_HOME))
                loaded.addSource(GeoJsonSource(SRC_DRONE))
                loaded.addLayer(
                    LineLayer(LAYER_ROUTE_LINE, SRC_ROUTE_LINE).withProperties(
                        PropertyFactory.lineColor(ACCENT_HEX),
                        PropertyFactory.lineWidth(3f),
                        PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                    ),
                )
                loaded.addLayer(
                    CircleLayer(LAYER_ROUTE_POINTS, SRC_ROUTE_POINTS).withProperties(
                        PropertyFactory.circleRadius(5f),
                        PropertyFactory.circleColor("#FFFFFF"),
                        PropertyFactory.circleStrokeColor(ACCENT_HEX),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
                loaded.addLayer(
                    CircleLayer(LAYER_HOME, SRC_HOME).withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor(OK_HEX),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
                loaded.addLayer(
                    CircleLayer(LAYER_DRONE, SRC_DRONE).withProperties(
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleColor(ACCENT_HEX),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
                style = loaded
            }
            map = m
        }
    }

    val dronePosition = drone.location?.takeIf { it.isValid }
    val home = route?.station ?: drone.homeLocation?.takeIf { it.isValid }

    LaunchedEffect(style, dronePosition, home, route) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SRC_DRONE)?.setGeoJson(pointCollection(listOfNotNull(dronePosition)))
        s.getSourceAs<GeoJsonSource>(SRC_HOME)?.setGeoJson(pointCollection(listOfNotNull(home)))
        val routePoints = route?.waypoints?.map { it.location }.orEmpty()
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE_POINTS)?.setGeoJson(pointCollection(routePoints))
        val linePoints = if (route != null) listOf(route.station) + routePoints + route.station else emptyList()
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE_LINE)?.setGeoJson(lineCollection(linePoints))

        val target = dronePosition ?: home
        val m = map
        if (!centered && target != null && m != null) {
            m.cameraPosition = CameraPosition.Builder().target(LatLng(target.latitude, target.longitude)).zoom(17.0).build()
            centered = true
        }
    }

    AndroidView(modifier = modifier, factory = { mapView })
}

/** Szkic mapy dla podglądu: trasa, stacja i dron w lokalnym układzie metrów (północ w górę). */
@Composable
private fun MapPreviewSketch(drone: DroneState, route: PatrolRoute?, modifier: Modifier) {
    val origin = route?.station ?: drone.homeLocation ?: drone.location
    Canvas(modifier.background(Color(0xFF203227))) {
        if (origin == null || !origin.isValid) return@Canvas
        val all = listOfNotNull(drone.location) + route?.waypoints?.map { it.location }.orEmpty() + origin
        val ne = all.map { origin.northEastTo(it) }
        val span = (ne.maxOf { maxOf(kotlin.math.abs(it.first), kotlin.math.abs(it.second)) }).coerceAtLeast(50.0)
        val scale = (minOf(size.width, size.height) * 0.42f) / span.toFloat()
        fun toOffset(p: GeoPoint): Offset {
            val (n, e) = origin.northEastTo(p)
            return Offset(center.x + e.toFloat() * scale, center.y - n.toFloat() * scale)
        }
        val accent = Color(0xFFE8792B)
        if (route != null) {
            val path = (listOf(route.station) + route.waypoints.map { it.location } + route.station).map(::toOffset)
            path.zipWithNext { a, b ->
                drawLine(accent, a, b, strokeWidth = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)))
            }
            route.waypoints.forEach {
                drawCircle(Color.White, 9f, toOffset(it.location))
                drawCircle(accent, 9f, toOffset(it.location), style = Stroke(3f))
            }
        }
        drawCircle(Color(0xFF3DBE7A), 12f, toOffset(origin))
        drone.location?.takeIf { it.isValid }?.let {
            drawCircle(Color.White, 15f, toOffset(it))
            drawCircle(accent, 12f, toOffset(it))
        }
    }
}

private fun pointCollection(list: List<GeoPoint>): FeatureCollection =
    FeatureCollection.fromFeatures(list.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })

private fun lineCollection(list: List<GeoPoint>): FeatureCollection =
    if (list.size < 2) {
        FeatureCollection.fromFeatures(emptyList<Feature>())
    } else {
        FeatureCollection.fromFeatures(
            listOf(Feature.fromGeometry(LineString.fromLngLats(list.map { Point.fromLngLat(it.longitude, it.latitude) }))),
        )
    }

private const val SRC_DRONE = "drone"
private const val SRC_HOME = "home"
private const val SRC_ROUTE_LINE = "route-line"
private const val SRC_ROUTE_POINTS = "route-points"
private const val LAYER_DRONE = "drone-layer"
private const val LAYER_HOME = "home-layer"
private const val LAYER_ROUTE_LINE = "route-line-layer"
private const val LAYER_ROUTE_POINTS = "route-points-layer"
private const val ACCENT_HEX = "#E8792B"
private const val OK_HEX = "#3DBE7A"

/** Styl MapLibre z rastrowymi kafelkami OSM. Do podmiany na produkcyjne źródło kafelków. */
const val MAP_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [ { "id": "osm", "type": "raster", "source": "osm" } ]
}
"""
