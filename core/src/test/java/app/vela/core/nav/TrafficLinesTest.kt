package app.vela.core.nav

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Traffic read off the raster along a road and turned into lines ([TrafficLines]). */
class TrafficLinesTest {
    private val start = LatLng(37.7749, -122.4194)
    private fun east(m: Double) = LatLng(start.lat, start.lng + m / (111_320.0 * Math.cos(Math.toRadians(start.lat))))
    private val road = listOf(east(0.0), east(300.0))

    /** Meters east of [start] for a point, and meters north of the road. */
    private fun along(p: LatLng) = (p.lng - start.lng) * 111_320.0 * Math.cos(Math.toRadians(start.lat))
    private fun across(p: LatLng) = (p.lat - start.lat) * 111_320.0

    @Test fun `Google's colors map to levels`() {
        assertEquals(0, TrafficLines.level(0x16, 0xe0, 0x98, 255))
        assertEquals(1, TrafficLines.level(0xff, 0xcf, 0x43, 255))
        assertEquals(2, TrafficLines.level(0xf2, 0x4e, 0x42, 255))
        assertEquals(3, TrafficLines.level(0x70, 0x23, 0x23, 255))
        assertEquals(0, TrafficLines.level(0xff, 0xcf, 0x43, 40))
    }

    @Test fun `a slow stretch drawn beside the road becomes a line on it`() {
        // Google's line for one direction, 5 m north of the center, slow from 100 m to 200 m.
        val runs = TrafficLines.runs(listOf(road)) { p ->
            if (kotlin.math.abs(across(p) - 5.0) < 2.0 && along(p) in 100.0..200.0) 1 else 0
        }
        assertEquals(1, runs.size)
        assertEquals(1, runs[0].level)
        assertEquals(100.0, along(runs[0].points.first()), 7.0)
        assertEquals(200.0, along(runs[0].points.last()), 13.0)
        assertTrue(runs[0].points.all { kotlin.math.abs(across(it)) < 0.01 })
    }

    @Test fun `a cross street's few meters of color are dropped`() {
        val runs = TrafficLines.runs(listOf(road)) { p -> if (along(p) in 148.0..160.0) 2 else 0 }
        assertTrue(runs.isEmpty())
    }

    @Test fun `a short gap is bridged and a short blip joins its neighbor`() {
        val runs = TrafficLines.runs(listOf(road)) { p ->
            val m = along(p)
            when {
                m in 50.0..120.0 -> 2
                m in 120.0..128.0 -> 0 // one missing step
                m in 128.0..140.0 -> 3 // a blip under MIN_RUN_M
                m in 140.0..220.0 -> 2
                else -> 0
            }
        }
        assertEquals(listOf(2), runs.map { it.level }.distinct())
        assertEquals(1, runs.size)
    }

    @Test fun `stretches at different levels meet`() {
        val runs = TrafficLines.runs(listOf(road)) { p -> if (along(p) < 150.0) 1 else 3 }
        assertEquals(listOf(1, 3), runs.map { it.level })
        assertEquals(runs[0].points.last(), runs[1].points.first())
    }

    @Test fun `pixel lookup lands in the right tile`() {
        val px = TrafficLines.pixelOf(LatLng(37.7749, -122.4194), 16, 256)
        assertEquals(10482, px[0]); assertEquals(25331, px[1])
        assertTrue(px[2] in 0..255 && px[3] in 0..255)
    }
}
