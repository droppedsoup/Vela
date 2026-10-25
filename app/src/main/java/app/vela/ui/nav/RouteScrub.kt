package app.vela.ui.nav

import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.nav.RouteProjection

/**
 * Scrubbing the road ahead (SPEC 4.7, Route bar scrub): during a drive the driver drags the
 * arrow up the route bar and the map flies along the route to that point, as if driving there.
 * The bar sets [fraction]; `NavController` detaches the follow camera while it is set, keeps
 * [route] and [traveledM] current, and ends it [RESET_MS] after the finger lets go or when the
 * compass is tapped; `VelaMapView` moves the camera to [target].
 */
object RouteScrub {
    const val RESET_MS = 7_000L

    /** How far up the bar the arrow is, 0 (the car) to 1 (the top of the bar); null when idle. */
    val fraction = mutableStateOf<Float?>(null)

    /** When the finger last let go (elapsed ms); 0 while it is down or nothing is scrubbed. */
    val releasedAt = mutableLongStateOf(0L)

    /** The drive's route and progress, kept by NavController; the bar's span, kept by the bar. */
    @Volatile var route: Route? = null
    @Volatile var traveledM: Double = 0.0
    @Volatile var spanM: Double = 0.0

    @Volatile private var cumFor: Route? = null
    @Volatile private var cum: DoubleArray = DoubleArray(0)

    val active: Boolean get() = fraction.value != null

    fun move(f: Float) {
        fraction.value = f.coerceIn(0f, 1f)
        releasedAt.longValue = 0L
    }

    fun release() {
        if (active) releasedAt.longValue = SystemClock.elapsedRealtime()
    }

    fun end() {
        if (fraction.value != null) fraction.value = null
        releasedAt.longValue = 0L
    }

    /** Where the camera goes for the current [fraction]: the point that far along the route
     *  ahead and the route's heading there, looking a little ahead so a bend reads smoothly. */
    fun target(): Pair<LatLng, Double>? {
        val f = fraction.value ?: return null
        val r = route ?: return null
        val poly = r.polyline
        if (poly.size < 2) return null
        if (cumFor !== r) { cum = RouteProjection.cumulative(poly); cumFor = r }
        val c = cum
        val total = c.last()
        val at = (traveledM + f * spanM).coerceIn(0.0, total)
        val p = RouteProjection.pointAt(poly, c, at)
        val ahead = RouteProjection.pointAt(poly, c, (at + 40.0).coerceAtMost(total))
        val bearing = if (ahead == p) RouteProjection.bearingAt(poly, c, at) else bearingTo(p, ahead)
        return p to bearing
    }

    private fun bearingTo(a: LatLng, b: LatLng): Double {
        val dy = b.lat - a.lat
        val dx = (b.lng - a.lng) * kotlin.math.cos(Math.toRadians((a.lat + b.lat) / 2))
        return (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0
    }
}
