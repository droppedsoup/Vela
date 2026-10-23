package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Route

/**
 * Where each intermediate stop falls in a route's maneuver list (issue #519). Every route source
 * builds ONE leg and the via boundaries carry no DEPART/ARRIVE, so a stop is invisible in the
 * turn list unless it is located by geometry: project the stop onto the polyline for its
 * along-route distance, project each maneuver the same way, and the first maneuver at or past
 * the stop (less a little slack, the stop's own pin sits a few meters off the road) starts the
 * next leg. A stop that projects nowhere near the route (a stale list) is skipped.
 */
object RouteStops {
    /** (maneuver index where the leg after this stop begins, stop label), in route order. */
    fun legStarts(route: Route, stops: List<Pair<LatLng, String>>, slackM: Double = 40.0, tolM: Double = 250.0): List<Pair<Int, String>> {
        val poly = route.polyline
        val maneuvers = route.maneuvers
        if (poly.size < 2 || stops.isEmpty() || maneuvers.isEmpty()) return emptyList()
        val cum = RouteProjection.cumulative(poly)
        val total = cum.last()
        // Follow the road forward for BOTH maneuvers and stops. A route that doubles back
        // crosses the same coordinates twice; a global nearest-point search assigns the stop
        // to its earlier pass and can make the next leg start before the previous stop.
        var maneuverFromM = 0.0
        val manAlong = maneuvers.map { maneuver ->
            val (at, _) = NavEngine.projectAlong(poly, cum, maneuver.location, maneuverFromM + 0.5, total)
            maneuverFromM = at.coerceAtLeast(maneuverFromM)
            maneuverFromM
        }
        val out = ArrayList<Pair<Int, String>>()
        var fromIndex = 0
        var fromM = 0.0
        for ((loc, label) in stops) {
            val (along, off) = NavEngine.projectAlong(poly, cum, loc, fromM + 0.5, total)
            if (off > tolM) continue
            fromM = along.coerceAtLeast(fromM)
            var i = fromIndex
            while (i < maneuvers.size && manAlong[i] < along - slackM) i++
            if (i <= 0 || i >= maneuvers.size - 1) continue
            out += i to label
            fromIndex = i
        }
        return out
    }

}
