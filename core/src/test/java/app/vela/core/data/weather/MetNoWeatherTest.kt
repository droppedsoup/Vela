package app.vela.core.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetNoWeatherTest {
    // The shape of a real compact reply (San Francisco, trimmed to the first time step).
    private val reply = """{"type":"Feature","properties":{"timeseries":[{"time":"2026-10-25T15:00:00Z","data":{
        "instant":{"details":{"air_temperature":11.9,"wind_from_direction":18.2,"wind_speed":9.0}},
        "next_1_hours":{"summary":{"symbol_code":"heavyrainandthunder"},"details":{"precipitation_amount":3.1}},
        "next_6_hours":{"summary":{"symbol_code":"rain"},"details":{}}}}]}}"""

    @Test fun `the first hour's symbol and the wind are read`() {
        val w = MetNoWeather.parse(reply)!!
        assertEquals(WeatherNow.Kind.STORM, w.kind)
        assertEquals(1.0f, w.intensity, 0f)
        assertEquals(32.4, w.windKmh, 0.01)
        assertEquals(18.2, w.windFromDeg, 0.0)
        assertTrue(w.windy)
    }

    @Test fun `symbol codes map to kinds and intensities`() {
        assertEquals(WeatherNow.Kind.RAIN to 0.4f, MetNoWeather.kindOf("lightrainshowers_day"))
        assertEquals(WeatherNow.Kind.SNOW to 1.0f, MetNoWeather.kindOf("heavysnow"))
        assertEquals(WeatherNow.Kind.SLEET to 0.7f, MetNoWeather.kindOf("sleetshowers_night"))
        assertEquals(WeatherNow.Kind.FOG to 0f, MetNoWeather.kindOf("fog"))
        assertEquals(WeatherNow.Kind.CLEAR to 0f, MetNoWeather.kindOf("partlycloudy_day"))
        assertEquals(WeatherNow.Kind.CLEAR to 0f, MetNoWeather.kindOf(""))
    }

    @Test fun `a broken reply reads as nothing`() {
        assertNull(MetNoWeather.parse("{}"))
        assertNull(MetNoWeather.parse("not json"))
    }

    @Test fun `the position goes out with one decimal`() {
        val u = MetNoWeather.url(37.774929, -122.419416)
        assertTrue(u.endsWith("lat=37.8&lon=-122.4"))
        assertFalse(WeatherNow(WeatherNow.Kind.CLEAR, 0f, 10.0, 0.0).windy)
    }

    private val series = """{"properties":{"timeseries":[
        {"time":"2026-10-25T15:00:00Z","data":{"instant":{"details":{"wind_from_direction":200,"wind_speed":3.0}},"next_1_hours":{"summary":{"symbol_code":"rain"}}}},
        {"time":"2026-10-25T16:00:00Z","data":{"instant":{"details":{"wind_from_direction":210,"wind_speed":2.0}},"next_1_hours":{"summary":{"symbol_code":"cloudy"}}}},
        {"time":"2026-10-25T17:00:00Z","data":{"instant":{"details":{"wind_from_direction":220,"wind_speed":1.0}},"next_6_hours":{"summary":{"symbol_code":"lightsnow"}}}}]}}"""
    private fun utc(h: Int, m: Int = 0) = java.time.Instant.parse(String.format("2026-10-25T%02d:%02d:00Z", h, m)).toEpochMilli()

    @Test fun `the weather follows the forecast hour by hour`() {
        val steps = MetNoWeather.parseSeries(series)
        assertEquals(3, steps.size)
        assertEquals(WeatherNow.Kind.RAIN, MetNoWeather.at(steps, utc(15, 40))!!.kind)
        // Rain until four, then it clears with no new request.
        assertEquals(WeatherNow.Kind.CLEAR, MetNoWeather.at(steps, utc(16, 5))!!.kind)
        assertEquals(WeatherNow.Kind.SNOW, MetNoWeather.at(steps, utc(17, 30))!!.kind)
        // Before the first step, the first; long after the last, nothing.
        assertEquals(WeatherNow.Kind.RAIN, MetNoWeather.at(steps, utc(14))!!.kind)
        assertNull(MetNoWeather.at(steps, utc(21)))
        assertNull(MetNoWeather.at(emptyList(), utc(15)))
    }
}
