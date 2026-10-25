package app.vela.ui.map

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.vela.core.data.naming.RoadNameTiles
import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import app.vela.core.nav.TrafficLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Slow traffic as smooth lines on the roads (SPEC 6, Rasters). From [FROM_ZOOM] the raster
 * overlay fades to nearly nothing (it stays loaded, which keeps its tiles coming for
 * [TrafficSampler]) and these lines fade in: the open map's roads around the camera, colored by
 * [TrafficLines] from the traffic levels. Rebuilt off the main thread, at most every
 * [PASS_MS], and only when the camera has moved [MOVE_M], the zoom changed, or new traffic tiles
 * arrived; a parked map does no work.
 */
internal object TrafficLinesLayer {
    const val SRC = "vela-traffic-lines-src"
    const val LAYER = "vela-traffic-lines"
    private const val RASTER_LAYER = "vela-traffic"

    /** Lines show from here; the raster gives way over the same half zoom level. */
    const val FROM_ZOOM = 13.5f
    const val FULL_ZOOM = 14.5f

    private const val PASS_MS = 1_500L
    private const val MOVE_M = 120.0
    private const val NEW_TILES_MS = 3_000L
    private const val REFRESH_MS = 30_000L

    // Roads a car drives on; service roads and tracks carry no traffic Google draws.
    private val DRAWN = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor")

    /** How far around the camera roads are read: the tilted drive view reaches the horizon, and
     *  traffic past this is the raster's to show when the map zooms out. */
    private fun radiusM(zoom: Double) = if (zoom >= 16.0) 900.0 else 1_600.0

    fun ensure(style: Style): Boolean = runCatching {
        if (style.getLayer(RASTER_LAYER) == null) return@runCatching false
        if (style.getSource(SRC) == null) style.addSource(GeoJsonSource(SRC))
        if (style.getLayer(LAYER) == null) {
            val layer = LineLayer(LAYER, SRC).apply {
                setMinZoom(FROM_ZOOM)
                setProperties(
                    PropertyFactory.lineColor(
                        Expression.match(
                            Expression.get("l"), Expression.color(0xFFF2B21B.toInt()),
                            Expression.stop(2, Expression.color(0xFFE5483C.toInt())),
                            Expression.stop(3, Expression.color(0xFF9E1B1B.toInt())),
                        ),
                    ),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(
                            Expression.exponential(1.5f), Expression.zoom(),
                            Expression.stop(13.5f, 2.5f), Expression.stop(16f, 5f),
                            Expression.stop(18f, 9f), Expression.stop(20f, 16f),
                        ),
                    ),
                    PropertyFactory.lineOpacity(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(FROM_ZOOM, 0f), Expression.stop(FULL_ZOOM, 0.95f),
                        ),
                    ),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineBlur(0.6f),
                )
            }
            style.addLayerAbove(layer, RASTER_LAYER)
        }
        true
    }.getOrDefault(false)

    fun remove(style: Style) {
        runCatching { style.getLayer(LAYER)?.let { style.removeLayer(it) } }
        runCatching { style.getSource(SRC)?.let { style.removeSource(it) } }
    }

    private fun set(style: Style, runs: List<TrafficLines.Run>) = runCatching {
        style.getSourceAs<GeoJsonSource>(SRC)?.setGeoJson(
            FeatureCollection.fromFeatures(
                runs.map { r ->
                    Feature.fromGeometry(LineString.fromLngLats(r.points.map { Point.fromLngLat(it.lng, it.lat) }))
                        .apply { addNumberProperty("l", r.level) }
                },
            ),
        )
    }

    /** The traffic lines around the camera, or null when the roads could not be read. */
    private suspend fun build(center: LatLng, zoom: Double): List<TrafficLines.Run>? {
        val r = radiusM(zoom)
        val dLat = r / 111_320.0
        val dLng = r / (111_320.0 * Math.cos(Math.toRadians(center.lat)).coerceAtLeast(0.2))
        val s = center.lat - dLat; val n = center.lat + dLat
        val w = center.lng - dLng; val e = center.lng + dLng
        val roads = RoadNameTiles.roadsInBox(s, w, n, e) ?: return null
        val inBox = roads.asSequence()
            .filter { it.cls in DRAWN }
            .map { it.points }
            .filter { pts -> pts.any { it.lat in s..n && it.lng in w..e } }
            .toList()
        return TrafficLines.runs(inBox, TrafficSampler::level)
    }

    @Composable
    fun Effect(map: MapLibreMap?, style: Style?, shown: Boolean) {
        LaunchedEffect(map, style, shown) {
            val m = map ?: return@LaunchedEffect
            val st = style ?: return@LaunchedEffect
            if (!shown) { remove(st); return@LaunchedEffect }
            var lastAt: LatLng? = null
            var lastZoom = -1.0
            var lastGen = -1
            var lastMs = 0L
            while (isActive) {
                val cam = m.cameraPosition
                val target = cam.target
                if (cam.zoom >= FROM_ZOOM && target != null && ensure(st)) {
                    val c = LatLng(target.latitude, target.longitude)
                    val now = SystemClock.elapsedRealtime()
                    val gen = TrafficSampler.generation
                    val due = lastAt == null ||
                        lastAt.distanceTo(c) > MOVE_M ||
                        kotlin.math.abs(cam.zoom - lastZoom) > 0.6 ||
                        (gen != lastGen && now - lastMs > NEW_TILES_MS) ||
                        now - lastMs > REFRESH_MS
                    if (due) {
                        val runs = withContext(Dispatchers.Default) { runCatching { build(c, cam.zoom) }.getOrNull() }
                        if (runs != null && isActive) {
                            set(st, runs)
                            lastAt = c; lastZoom = cam.zoom; lastGen = gen; lastMs = now
                        }
                    }
                }
                delay(PASS_MS)
            }
        }
    }

    /** The raster's opacity once the lines take over: full below [FROM_ZOOM], almost nothing
     *  from [FULL_ZOOM]. Not 0: a layer at 0 is skipped and its tiles stop loading. */
    fun rasterOpacity(full: Float): Expression = Expression.interpolate(
        Expression.linear(), Expression.zoom(),
        Expression.stop(FROM_ZOOM, full), Expression.stop(FULL_ZOOM, 0.004f),
    )
}
