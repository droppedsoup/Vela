package app.vela.core.nav

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which police reports are live, which lie on a route, and when one is announced ([PoliceAlerts]). */
class PoliceAlertsTest {

    // A straight north-south road through Davis, about 2.2 km long.
    private val road = listOf(LatLng(38.5400, -121.7400), LatLng(38.5600, -121.7400))
    private val cum = RouteProjection.cumulative(road)

    private fun report(id: String, lat: Double, lng: Double = -121.7400, heading: Double? = 0.0, at: Long = 0L) =
        PoliceReport(id, LatLng(lat, lng), heading, at)

    @Test fun `old reports drop out after their lifetime and a zero lifetime keeps all`() {
        val rs = listOf(report("a", 38.55, at = 0L), report("b", 38.55, at = 50 * 60_000L))
        assertEquals(listOf("b"), PoliceAlerts.live(rs, nowMs = 70 * 60_000L, lifetimeMs = 60 * 60_000L).map { it.id })
        assertEquals(2, PoliceAlerts.live(rs, nowMs = 70 * 60_000L, lifetimeMs = 0L).size)
    }

    @Test fun `a report dated in the future counts as made now`() {
        val rs = listOf(report("a", 38.55, at = 10_000_000L))
        assertEquals(1, PoliceAlerts.live(rs, nowMs = 0L, lifetimeMs = 1_000L).size)
    }

    @Test fun `a report beside the road counts and one on a cross street does not`() {
        val beside = report("beside", 38.5500, lng = -121.7405) // about 43 m off the line
        val cross = report("cross", 38.5520, heading = 90.0) // on the line, made heading east
        val far = report("far", 38.5500, lng = -121.7420) // about 170 m off
        val m = PoliceAlerts.alongRoute(road, cum, listOf(cross, far, beside))
        assertEquals(listOf("beside"), m.map { it.first })
        assertEquals(1112.0, m[0].second, 15.0)
    }

    @Test fun `a report from the other direction still counts`() {
        val m = PoliceAlerts.alongRoute(road, cum, listOf(report("south", 38.55, heading = 180.0)))
        assertEquals(1, m.size)
    }

    @Test fun `reports close together along the route are one spot keyed by the first`() {
        val rs = listOf(report("b", 38.5510), report("a", 38.5500), report("c", 38.5560))
        assertEquals(listOf("a", "c"), PoliceAlerts.alongRoute(road, cum, rs).map { it.first })
    }

    @Test fun `a second report near a live one replaces it`() {
        val first = report("a", 38.5500, at = 1L)
        val again = report("b", 38.5505, at = 2L) // about 56 m on
        val other = report("c", 38.5550, at = 3L) // about 560 m on
        val out = PoliceAlerts.add(PoliceAlerts.add(listOf(first), other), again)
        assertEquals(listOf("c", "b"), out.map { it.id })
    }

    @Test fun `announces once inside the lead window and never behind`() {
        val ahead = listOf("p" to 1_000.0, "q" to 1_900.0)
        // 25 m/s gives a 500 m lead.
        assertNull(PoliceAlerts.due(ahead, traveledM = 400.0, speedMps = 25.0, announced = emptySet()))
        assertEquals("p", PoliceAlerts.due(ahead, traveledM = 600.0, speedMps = 25.0, announced = emptySet()))
        assertNull(PoliceAlerts.due(ahead, traveledM = 600.0, speedMps = 25.0, announced = setOf("p")))
        assertNull(PoliceAlerts.due(ahead, traveledM = 1_950.0, speedMps = 25.0, announced = setOf("p")))
        assertNull("parked", PoliceAlerts.due(ahead, traveledM = 900.0, speedMps = 0.5, announced = emptySet()))
    }

    @Test fun `the lead scales with speed between its floor and cap`() {
        assertEquals(PoliceAlerts.MIN_LEAD_M, PoliceAlerts.leadDistanceM(5.0), 0.0)
        assertEquals(600.0, PoliceAlerts.leadDistanceM(30.0), 0.0)
        assertEquals(PoliceAlerts.MAX_LEAD_M, PoliceAlerts.leadDistanceM(80.0), 0.0)
    }

    @Test fun `the file format round-trips and skips bad lines`() {
        val rs = listOf(report("a", 38.5512345, heading = 12.0, at = 42L), report("b", 38.56, heading = null, at = 7L))
        val text = PoliceAlerts.format(rs) + "junk line\n\t1\t2\t3\t4\n"
        val back = PoliceAlerts.parse(text)
        assertEquals(2, back.size)
        assertEquals("a", back[0].id)
        assertEquals(38.551235, back[0].loc.lat, 1e-6)
        assertEquals(12.0, back[0].headingDeg!!, 0.0)
        assertNull(back[1].headingDeg)
        assertTrue(back.all { it.loc.lng == -121.74 })
    }
}
