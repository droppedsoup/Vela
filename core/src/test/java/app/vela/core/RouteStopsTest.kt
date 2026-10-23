package app.vela.core

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.nav.RouteStops
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteStopsTest {
    // A straight route east along one latitude, 1 km per point, Davis-ish.
    private val poly = (0..10).map { LatLng(38.55, -121.74 + it * 0.0115) }
    private fun m(type: ManeuverType, i: Int) = Maneuver(type, "", poly[i], 1000.0, 60.0)
    private val route = Route(
        polyline = poly, distanceMeters = 10_000.0, durationSeconds = 600.0, durationInTrafficSeconds = null,
        legs = listOf(RouteLeg(10_000.0, 600.0, null, listOf(m(ManeuverType.DEPART, 0), m(ManeuverType.TURN_LEFT, 3), m(ManeuverType.TURN_RIGHT, 6), m(ManeuverType.ARRIVE, 10)))),
    )

    @Test fun stopBetweenTurnsStartsTheNextLegAtTheFollowingTurn() {
        val stops = listOf(poly[4].copy(lat = 38.5501) to "Co-op") // 11 m off the road, between turns at 3 and 6
        assertEquals(listOf(2 to "Co-op"), RouteStops.legStarts(route, stops))
    }

    @Test fun stopOnATurnCountsThatTurnAsTheLegStart() {
        assertEquals(listOf(1 to "A"), RouteStops.legStarts(route, listOf(poly[3] to "A")))
    }

    @Test fun twoStopsInOrder() {
        assertEquals(listOf(1 to "A", 2 to "B"), RouteStops.legStarts(route, listOf(poly[2] to "A", poly[5] to "B")))
    }

    @Test fun outAndBackStopsStartLegsOnTheirCorrectPass() {
        // Return via a street already crossed outbound. The second stop must be assigned to
        // its return pass, not the earlier visit to the same coordinates.
        val line = listOf(
            LatLng(38.55, -121.90),
            LatLng(38.55, -121.70),
            LatLng(38.55, -121.80),
            LatLng(38.55, -121.88),
        )
        val steps = listOf(
            Maneuver(ManeuverType.DEPART, "", line[0], 1000.0, 60.0),
            Maneuver(ManeuverType.TURN_LEFT, "", line[1], 1000.0, 60.0),
            Maneuver(ManeuverType.TURN_RIGHT, "", line[2], 1000.0, 60.0),
            Maneuver(ManeuverType.ARRIVE, "", line[3], 0.0, 0.0),
        )
        val drive = Route(
            polyline = line, distanceMeters = 0.0, durationSeconds = 600.0,
            durationInTrafficSeconds = null,
            legs = listOf(RouteLeg(0.0, 600.0, null, steps)),
        )
        assertEquals(
            listOf(1 to "First", 2 to "Return"),
            RouteStops.legStarts(drive, listOf(line[1] to "First", line[2] to "Return")),
        )
    }

    @Test fun farStopsAndStopsPastTheEndAreSkipped() {
        assertEquals(emptyList<Pair<Int, String>>(), RouteStops.legStarts(route, listOf(LatLng(38.60, -121.74) to "far")))
        assertEquals(emptyList<Pair<Int, String>>(), RouteStops.legStarts(route, listOf(poly[10] to "end")))
    }
}
