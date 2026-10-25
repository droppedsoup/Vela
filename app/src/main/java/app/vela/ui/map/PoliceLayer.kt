package app.vela.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.vela.core.nav.PoliceReport
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The police reports on the map (SPEC 4.9, Police reports): one badge per live report, from z10,
 * drawn above the speed camera badges. Called from VelaMapView's update pass on every style; the
 * data is set only when the list or the style changes.
 */
internal object PoliceLayer {
    private const val SRC = "vela-police-src"
    const val LAYER = "vela-police"
    private const val IMG = "vela-police-badge"
    private const val SPEEDCAM_LAYER = "vela-speedcam"

    private var applied: List<PoliceReport>? = null

    fun apply(style: Style, reports: List<PoliceReport>) {
        runCatching {
            if (style.getImage(IMG) == null) style.addImage(IMG, badge())
            val fresh = style.getSource(SRC) == null
            if (fresh) {
                style.addSource(GeoJsonSource(SRC, GeoJsonOptions().withMaxZoom(16)))
                val layer = SymbolLayer(LAYER, SRC).apply {
                    setMinZoom(10f)
                    setProperties(
                        PropertyFactory.iconImage(IMG),
                        PropertyFactory.iconSize(
                            Expression.interpolate(
                                Expression.linear(), Expression.zoom(),
                                Expression.stop(10f, 0.6f),
                                Expression.stop(14f, 0.8f),
                                Expression.stop(17f, 1.05f),
                                Expression.stop(19f, 1.35f),
                            ),
                        ),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(false),
                        PropertyFactory.iconPadding(2f),
                    )
                }
                if (style.getLayer(SPEEDCAM_LAYER) != null) style.addLayerAbove(layer, SPEEDCAM_LAYER) else style.addLayer(layer)
            }
            if (!fresh && reports === applied) return
            style.getSourceAs<GeoJsonSource>(SRC)?.setGeoJson(
                FeatureCollection.fromFeatures(
                    reports.map { r ->
                        Feature.fromGeometry(Point.fromLngLat(r.loc.lng, r.loc.lat)).apply { addStringProperty("id", r.id) }
                    },
                ),
            )
            applied = reports
        }.onFailure { android.util.Log.w("VelaPolice", "police layer: ${it.message}") }
    }

    /** A rounded blue badge with a white rim and a light bar: red and blue lamps on a white base. */
    private fun badge(): Bitmap {
        val s = 46
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun paint(color: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toInt() }
        val white = paint(0xFFFFFFFF)
        val blue = paint(0xFF1A47B8)
        c.drawRoundRect(2f, 2f, s - 2f, s - 2f, 11f, 11f, white)
        c.drawRoundRect(4.5f, 4.5f, s - 4.5f, s - 4.5f, 9f, 9f, blue)
        // Two domed lamps, red and blue, on a white bar.
        val red = paint(0xFFE53935)
        val lamp = paint(0xFF64B5F6)
        fun dome(left: Float, p: Paint) {
            val path = Path().apply {
                moveTo(left, 25f)
                lineTo(left, 19f)
                arcTo(RectF(left, 13f, left + 10f, 25f), 180f, 180f)
                lineTo(left + 10f, 25f)
                close()
            }
            c.drawPath(path, p)
        }
        dome(12f, red)
        dome(24f, lamp)
        c.drawRoundRect(10f, 25f, 36f, 31f, 2f, 2f, white)
        return bmp
    }
}
