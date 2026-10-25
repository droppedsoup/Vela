package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A round trip that ends where it began: north 800 m, east 400 m, back south and west to the
 *  start. The first maneuver and the arrival share a coordinate. Driven at 12 m/s with fixes
 *  scattered up to 6 m off the line, the drive must never arrive early or count backward, and
 *  must arrive at the end. */
class NavEngineRoundTripTest {
    private val start = LatLng(38.5449, -121.7405)
    private fun p(northM: Double, eastM: Double) =
        LatLng(start.lat + northM / 111_320.0, start.lng + eastM / (111_320.0 * Math.cos(Math.toRadians(start.lat))))
    private val corners = listOf(p(0.0, 0.0), p(800.0, 0.0), p(800.0, 400.0), p(0.0, 400.0), p(0.0, 0.0))
    // Points every 25 m along each side.
    private val line = corners.zipWithNext().flatMapIndexed { i, (a, b) ->
        val n = (a.distanceTo(b) / 25.0).toInt()
        (0 until n).map { k -> LatLng(a.lat + (b.lat - a.lat) * k / n, a.lng + (b.lng - a.lng) * k / n) } +
            (if (i == 3) listOf(b) else emptyList())
    }
    private val total = line.zipWithNext { a, b -> a.distanceTo(b) }.sum()
    private fun man(type: ManeuverType, at: LatLng, d: Double) = Maneuver(type, type.name, at, d, d / 12)
    private val route = Route(
        polyline = line,
        legs = listOf(RouteLeg(total, total / 12, null, listOf(
            man(ManeuverType.DEPART, corners[0], 800.0),
            man(ManeuverType.TURN_RIGHT, corners[1], 400.0),
            man(ManeuverType.TURN_RIGHT, corners[2], 800.0),
            man(ManeuverType.TURN_RIGHT, corners[3], 400.0),
            man(ManeuverType.ARRIVE, corners[4], 0.0),
        ))),
        distanceMeters = total, durationSeconds = total / 12, durationInTrafficSeconds = null,
    )

    @Test fun aRoundTripNeverArrivesAtItsStartAndArrivesAtItsEnd() {
        val cum = NavEngine.cumulative(line)
        val rnd = java.util.Random(7)
        var state = NavState()
        var at = 0.0
        var lastTraveled = 0.0
        var lastStep = 0
        var arrivedAt = -1.0
        while (at <= total + 30.0 && arrivedAt < 0) {
            val onLine = RouteProjection.pointAt(line, cum, minOf(at, total))
            val fix = LatLng(
                onLine.lat + (rnd.nextDouble() * 12.0 - 6.0) / 111_320.0,
                onLine.lng + (rnd.nextDouble() * 12.0 - 6.0) / 87_000.0,
            )
            state = NavEngine.update(route, state, fix, false, speedMps = if (at < total) 12.0 else 0.0).first
            if (state.arrived) arrivedAt = at
            assertTrue("progress went back at $at m: ${state.traveledM} after $lastTraveled", state.traveledM >= lastTraveled - 2.0)
            assertTrue("step went back at $at m", state.stepIndex >= lastStep)
            assertFalse("off route at $at m on a fix within 6 m of the line", state.offRoute)
            lastTraveled = maxOf(lastTraveled, state.traveledM)
            lastStep = state.stepIndex
            at += 12.0
        }
        assertTrue("never arrived", arrivedAt >= 0)
        assertTrue("arrived at $arrivedAt m of $total", arrivedAt >= total - 120.0)
        assertEquals(route.maneuvers.size - 1, lastStep)
    }
}
