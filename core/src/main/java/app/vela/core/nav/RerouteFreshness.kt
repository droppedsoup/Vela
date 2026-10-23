package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.distanceTo

/**
 * Protects a moving driver from adopting a reroute computed from an old position.
 * A route remains usable if it still passes close to the current fix, even if its origin
 * is some distance behind. An outdated route on a different road should instead be retried.
 */
object RerouteFreshness {
    const val MAX_ORIGIN_ADVANCE_M = 150.0
    const val CURRENT_ROUTE_CORRIDOR_M = 70.0

    fun canAdopt(route: Route, requestFrom: LatLng, newest: LatLng?): Boolean {
        if (newest == null || newest.distanceTo(requestFrom) <= MAX_ORIGIN_ADVANCE_M) return true
        if (route.polyline.size < 2) return false
        return RouteProjection.alongMeters(
            route.polyline,
            RouteProjection.cumulative(route.polyline),
            newest,
            CURRENT_ROUTE_CORRIDOR_M,
        ) != null
    }
}
