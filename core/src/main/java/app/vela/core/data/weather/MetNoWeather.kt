package app.vela.core.data.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The weather the map's effects draw (SPEC 6, Weather effects). */
data class WeatherNow(
    val kind: Kind,
    /** 0.4 light, 0.7 moderate, 1.0 heavy. */
    val intensity: Float,
    val windKmh: Double,
    /** The compass direction the wind blows FROM, degrees. */
    val windFromDeg: Double,
) {
    enum class Kind { CLEAR, RAIN, SLEET, SNOW, STORM, FOG }

    /** Wind strong enough to draw on its own when nothing falls. */
    val windy: Boolean get() = windKmh >= WINDY_KMH

    companion object {
        const val WINDY_KMH = 29.0
    }
}

/**
 * MET Norway's locationforecast (`api.met.no`), free and keyless, worldwide. Its terms ask for an
 * identifying User-Agent and at most four decimals of position; Vela sends one decimal (about
 * 11 km), which is all a rain effect needs. The weather for the next hour is read from the first
 * time step: `next_1_hours.summary.symbol_code` for what falls, `instant` for the wind.
 */
object MetNoWeather {
    fun url(lat: Double, lng: Double): String = String.format(
        java.util.Locale.US,
        "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.1f&lon=%.1f",
        lat, lng,
    )

    /** The weather of the reply's first time step. */
    fun parse(body: String): WeatherNow? = parseSeries(body).firstOrNull()?.weather

    /** One time step of the forecast: from [atMs] (epoch ms), this weather. */
    data class Step(val atMs: Long, val weather: WeatherNow)

    /** Every time step of the reply, hourly near the start and coarser later, in order. A step
     *  that does not read is skipped; an unreadable reply is empty. */
    fun parseSeries(body: String): List<Step> = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        root["properties"]!!.jsonObject["timeseries"]!!.jsonArray.mapNotNull { e ->
            runCatching {
                val step = e.jsonObject
                val at = java.time.Instant.parse(step["time"]!!.jsonPrimitive.content).toEpochMilli()
                val data = step["data"]!!.jsonObject
                val details = data["instant"]!!.jsonObject["details"]!!.jsonObject
                val symbol = (data["next_1_hours"] ?: data["next_6_hours"] ?: data["next_12_hours"])
                    ?.let { (it as JsonObject)["summary"]?.jsonObject?.get("symbol_code")?.jsonPrimitive?.content }
                    .orEmpty()
                val windMs = details["wind_speed"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val windFrom = details["wind_from_direction"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val (kind, intensity) = kindOf(symbol)
                Step(at, WeatherNow(kind, intensity, windMs * 3.6, windFrom))
            }.getOrNull()
        }.sortedBy { it.atMs }
    }.getOrDefault(emptyList())

    /** The weather for [nowMs]: the last step that has begun, or the first when none has. Null
     *  when the forecast ended more than [STALE_MS] ago, so an old answer is not drawn. */
    fun at(series: List<Step>, nowMs: Long): WeatherNow? {
        if (series.isEmpty()) return null
        if (nowMs - series.last().atMs > STALE_MS) return null
        return (series.lastOrNull { it.atMs <= nowMs } ?: series.first()).weather
    }

    const val STALE_MS = 3 * 60 * 60_000L

    /** A symbol code ("lightrain", "heavysnowshowers_night", "rainandthunder", "fog") as a kind
     *  and an intensity. */
    fun kindOf(symbol: String): Pair<WeatherNow.Kind, Float> {
        val s = symbol.substringBefore('_')
        val intensity = when {
            s.startsWith("light") -> 0.4f
            s.startsWith("heavy") -> 1.0f
            else -> 0.7f
        }
        val kind = when {
            "thunder" in s -> WeatherNow.Kind.STORM
            "sleet" in s -> WeatherNow.Kind.SLEET
            "snow" in s -> WeatherNow.Kind.SNOW
            "rain" in s -> WeatherNow.Kind.RAIN
            s == "fog" -> WeatherNow.Kind.FOG
            else -> WeatherNow.Kind.CLEAR
        }
        return kind to (if (kind == WeatherNow.Kind.CLEAR || kind == WeatherNow.Kind.FOG) 0f else intensity)
    }
}
