package app.vela.core.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading RainViewer's radar at the car and putting it over the forecast ([RainRadar]). */
class RainRadarTest {
    private fun argb(hex: String): Int = // "#rrggbbaa", the table's own form
        ((hex.substring(7, 9).toInt(16) shl 24) or hex.substring(1, 7).toInt(16))

    @Test fun `table colors read back to reflectivity and rain or snow`() {
        assertEquals(20 to false, RainRadar.classify(argb("#00a3e0ff")))
        assertEquals(45 to false, RainRadar.classify(argb("#ff4400ff")))
        assertEquals(25 to true, RainRadar.classify(argb("#5f9fffff")))
        assertNull(RainRadar.classify(0))
        assertNull("far from every color", RainRadar.classify(argb("#7f00ffff")))
    }

    private fun tile(fill: Int) = IntArray(RainRadar.TILE * RainRadar.TILE) { fill }

    @Test fun `a wet patch around the car reads wet, a lone pixel does not`() {
        val wet = RainRadar.read(tile(argb("#0077aaff")), null, 100, 100, 1000L)
        assertTrue(wet.wet); assertEquals(25, wet.dbz); assertFalse(wet.snow)
        val one = tile(0).also { it[100 * RainRadar.TILE + 100] = argb("#0077aaff") }
        assertFalse(RainRadar.read(one, null, 100, 100, 1000L).wet)
        // Drizzle under the rain floor is dry.
        assertFalse(RainRadar.read(tile(argb("#cec08796")), null, 100, 100, 1000L).wet)
    }

    @Test fun `a view reads its whole area, so rain across town counts`() {
        // Dry at the center, a shower 10 px (about 10 km) off it covering a fifth of the view.
        val t = tile(0)
        for (y in 80..120) for (x in 108..118) t[y * RainRadar.TILE + x] = argb("#0077aaff")
        assertFalse("the car's own spot is dry", RainRadar.read(t, null, 100, 100, 1000L).wet)
        assertTrue("the view is wet", RainRadar.read(t, null, 100, 100, 1000L, radius = 20).wet)
        // A few wet pixels in a wide view are not rain over it.
        val speck = tile(0).also { for (x in 100..103) it[100 * RainRadar.TILE + x] = argb("#0077aaff") }
        assertFalse(RainRadar.read(speck, null, 100, 100, 1000L, radius = 20).wet)
        assertEquals(RainRadar.MAX_RADIUS_PX, RainRadar.radiusPx(500_000.0, 35.2))
        assertTrue(RainRadar.radiusPx(10_000.0, 35.2) in 9..12)
    }

    @Test fun `no radar there is not dry`() {
        val r = RainRadar.read(tile(0), tile(0xFF000000.toInt()), 10, 10, 1000L)
        assertFalse(r.covered)
        val rain = WeatherNow(WeatherNow.Kind.RAIN, 0.7f, 10.0, 90.0)
        assertSame(rain, RainRadar.combine(rain, r, 1000L))
    }

    private val now = 10_000L
    private fun reading(wet: Boolean, dbz: Int = 0, snow: Boolean = false, at: Long = now - 300) = RainRadar.Reading(true, wet, dbz, snow, at)

    @Test fun `the radar overrules the forecast both ways`() {
        val rainForecast = WeatherNow(WeatherNow.Kind.RAIN, 0.7f, 20.0, 180.0)
        val dry = RainRadar.combine(rainForecast, reading(false), now)!!
        assertEquals(WeatherNow.Kind.CLEAR, dry.kind); assertEquals(20.0, dry.windKmh, 0.0)
        val clear = WeatherNow(WeatherNow.Kind.CLEAR, 0f, 5.0, 0.0)
        val wet = RainRadar.combine(clear, reading(true, 38), now)!!
        assertEquals(WeatherNow.Kind.RAIN, wet.kind); assertEquals(1.0f, wet.intensity, 0f)
        assertEquals(WeatherNow.Kind.SNOW, RainRadar.combine(clear, reading(true, 15, snow = true), now)!!.kind)
        assertEquals(WeatherNow.Kind.STORM, RainRadar.combine(clear, reading(true, 52), now)!!.kind)
        // Fog stays when the radar is dry.
        val fog = WeatherNow(WeatherNow.Kind.FOG, 0f, 3.0, 0.0)
        assertSame(fog, RainRadar.combine(fog, reading(false), now))
    }

    @Test fun `an old frame leaves the forecast`() {
        val rain = WeatherNow(WeatherNow.Kind.RAIN, 0.7f, 10.0, 90.0)
        assertSame(rain, RainRadar.combine(rain, reading(false, at = now - RainRadar.MAX_AGE_S - 1), now))
    }

    @Test fun `the maps list gives the newest frame`() {
        val f = RainRadar.latestFrame("""{"host":"https://tilecache.rainviewer.com","radar":{"past":[{"time":100,"path":"/v2/radar/a"},{"time":700,"path":"/v2/radar/b"}],"nowcast":[]}}""")!!
        assertEquals("/v2/radar/b", f.path); assertEquals(700L, f.timeS)
        assertEquals("https://tilecache.rainviewer.com/v2/radar/b/256/7/20/49/2/1_1.png", RainRadar.tileUrl(f, 20, 49))
        val px = RainRadar.pixelOf(37.7749, -122.4194)
        assertEquals(20, px[0]); assertEquals(49, px[1])
    }
}
