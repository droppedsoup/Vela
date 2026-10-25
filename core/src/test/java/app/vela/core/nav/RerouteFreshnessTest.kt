package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RerouteFreshnessTest {
    private val start = LatLng(38.55, -121.90)
    private val east = LatLng(38.55, -121.85)
    private val line = listOf(start, east)
    private val route = Route(
        polyline = line, legs = emptyList(), distanceMeters = 0.0,
        durationSeconds = 0.0, durationInTrafficSeconds = null,
    )

    @Test fun newCandidateIsAcceptedIfVehicleHasBarelyMoved() {
        assertTrue(RerouteFreshness.canAdopt(route, start, LatLng(38.55, -121.8995)))
    }

    @Test fun oldOriginIsFineIfTheRouteStillPassesTheCar() {
        assertTrue(RerouteFreshness.canAdopt(route, start, east))
    }

    @Test fun staleCandidateOnWrongRoadIsRejected() {
        assertFalse(RerouteFreshness.canAdopt(route, start, LatLng(38.56, -121.85)))
    }

    @Test fun aStaleCandidateIsAdoptedAfterTwoRejectionsInARow() {
        val wrongRoad = LatLng(38.56, -121.85)
        assertFalse(RerouteFreshness.canAdopt(route, start, wrongRoad, staleRejects = 1))
        assertTrue(RerouteFreshness.canAdopt(route, start, wrongRoad, staleRejects = RerouteFreshness.MAX_STALE_REJECTS))
    }
}
