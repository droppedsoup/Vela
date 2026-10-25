package app.vela.core.data.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Live precipitation from RainViewer's public radar (SPEC 6, Weather effects): keyless, worldwide
 * where radars reach, a new frame every 10 minutes. The forecast ([MetNoWeather]) says what the
 * hour should bring; this says whether it is raining or snowing where the car is now. The free
 * tier serves tiles to z7 (about 1 km a pixel) in its "Universal Blue" colors only, so a pixel is
 * read back to reflectivity (dBZ) and rain or snow through RainViewer's published color table
 * (`rainviewer_api_colors_table.csv`, the Universal Blue column, rain rows from 5 dBZ and snow rows
 * from 0). Its coverage tile is clear where a radar reaches and black where none does.
 */
object RainRadar {
    const val ZOOM = 7
    const val TILE = 256

    /** Rain counts from this reflectivity (light rain); below it is drizzle or clutter. */
    const val RAIN_DBZ_MIN = 15
    /** Snow reflects less for the same fall, so it counts from lower. */
    const val SNOW_DBZ_MIN = 10
    /** From here a cell is a thunderstorm whatever the forecast says. */
    const val STORM_DBZ = 50
    /** A frame older than this says nothing about now. */
    const val MAX_AGE_S = 30 * 60L
    /** Pixels around the car read (about 2 km at z7), and how many must show precipitation. */
    const val RADIUS_PX = 2
    const val MIN_WET_PX = 3

    data class Frame(val host: String, val path: String, val timeS: Long)

    /** What the radar shows at a place: [wet] with [dbz] and [snow], or dry, or no radar there. */
    data class Reading(val covered: Boolean, val wet: Boolean, val dbz: Int, val snow: Boolean, val frameTimeS: Long)

    const val MAPS_URL = "https://api.rainviewer.com/public/weather-maps.json"

    /** The newest past frame of the maps list, or null. */
    fun latestFrame(body: String): Frame? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val host = root["host"]!!.jsonPrimitive.content
        val last = root["radar"]!!.jsonObject["past"]!!.jsonArray.last().jsonObject
        Frame(host, last["path"]!!.jsonPrimitive.content, last["time"]!!.jsonPrimitive.longOrNull ?: 0L)
    }.getOrNull()

    fun tileUrl(f: Frame, x: Int, y: Int): String = "${f.host}${f.path}/$TILE/$ZOOM/$x/$y/2/1_1.png"
    fun coverageUrl(x: Int, y: Int): String = "https://tilecache.rainviewer.com/v2/coverage/0/$TILE/$ZOOM/$x/$y/0/0_0.png"

    /** Tile x, y and pixel x, y of a point at [ZOOM]. */
    fun pixelOf(lat: Double, lng: Double): IntArray {
        val n = (1 shl ZOOM).toDouble()
        val fx = (lng + 180.0) / 360.0 * n
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val fy = (1.0 - kotlin.math.ln(kotlin.math.tan(r) + 1.0 / kotlin.math.cos(r)) / Math.PI) / 2.0 * n
        val tx = kotlin.math.floor(fx).toInt(); val ty = kotlin.math.floor(fy).toInt()
        return intArrayOf(tx, ty, ((fx - tx) * TILE).toInt().coerceIn(0, TILE - 1), ((fy - ty) * TILE).toInt().coerceIn(0, TILE - 1))
    }

    /** A radar pixel (ARGB) as (dBZ, snow), or null when clear or not a table color. */
    fun classify(argb: Int): Pair<Int, Boolean>? {
        val a = argb ushr 24
        if (a < 0x20) return null
        var best: Pair<Int, Boolean>? = null
        var bestD = Int.MAX_VALUE
        fun scan(dbz: IntArray, cols: IntArray, snow: Boolean) {
            for (i in cols.indices) {
                val c = cols[i]
                val d = sq((c ushr 24) - a) + sq(((c shr 16) and 0xff) - ((argb shr 16) and 0xff)) +
                    sq(((c shr 8) and 0xff) - ((argb shr 8) and 0xff)) + sq((c and 0xff) - (argb and 0xff))
                if (d < bestD) { bestD = d; best = dbz[i] to snow }
            }
        }
        scan(RAIN_DBZ, RAIN_ARGB, false)
        scan(SNOW_DBZ, SNOW_ARGB, true)
        // A pixel far from every table color (a smoothed edge) is left out rather than guessed.
        return if (bestD <= 3 * 24 * 24) best else null
    }

    private fun sq(v: Int) = v * v

    /** Wet when at least this share of the pixels read show precipitation (and [MIN_WET_PX]). */
    const val WET_SHARE = 0.08

    /** Pixels to read around a place for a view [radiusM] across at [lat]: the view's own area,
     *  from [RADIUS_PX] (the car) to [MAX_RADIUS_PX] (about 40 km). */
    fun radiusPx(radiusM: Double, lat: Double): Int {
        val mPerPx = 40_075_016.0 * kotlin.math.cos(Math.toRadians(lat)) / (TILE * (1 shl ZOOM))
        return (radiusM / mPerPx).toInt().coerceIn(RADIUS_PX, MAX_RADIUS_PX)
    }
    const val MAX_RADIUS_PX = 40

    /** The reading at pixel ([px], [py]) of a [TILE]-wide tile over a disc of [radius] pixels:
     *  wet when [WET_SHARE] of its pixels (and at least [MIN_WET_PX]) show precipitation, at their
     *  median reflectivity, snow when most do. [coverage] is the coverage tile's pixels, null
     *  when unknown (counted as covered). Pixels past the tile's edge are not read. */
    fun read(pixels: IntArray, coverage: IntArray?, px: Int, py: Int, frameTimeS: Long, radius: Int = RADIUS_PX): Reading {
        val covered = coverage == null || (coverage[py * TILE + px] ushr 24) < 0x80
        if (!covered) return Reading(false, false, 0, false, frameTimeS)
        val wet = ArrayList<Pair<Int, Boolean>>()
        var seen = 0
        val r2 = radius * radius
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx * dx + dy * dy > r2 && radius > RADIUS_PX) continue
            val x = px + dx; val y = py + dy
            if (x !in 0 until TILE || y !in 0 until TILE) continue
            seen++
            val c = classify(pixels[y * TILE + x]) ?: continue
            if (c.first >= (if (c.second) SNOW_DBZ_MIN else RAIN_DBZ_MIN)) wet += c
        }
        if (wet.size < MIN_WET_PX || wet.size < seen * WET_SHARE) return Reading(true, false, 0, false, frameTimeS)
        val dbz = wet.map { it.first }.sorted()[wet.size / 2]
        val snow = wet.count { it.second } * 2 > wet.size
        return Reading(true, true, dbz, snow, frameTimeS)
    }

    /**
     * The weather to draw: the forecast's, with what falls taken from the radar when it has a
     * fresh reading here. Wet radar sets rain or snow (sleet when the forecast says sleet, storm
     * when it says thunder or the cell reaches [STORM_DBZ]) at the radar's intensity; dry radar
     * clears what the forecast had falling (fog and wind stay). No reading, no coverage or an old
     * frame leaves the forecast as it is.
     */
    fun combine(forecast: WeatherNow?, radar: Reading?, nowS: Long): WeatherNow? {
        if (radar == null || !radar.covered || nowS - radar.frameTimeS > MAX_AGE_S) return forecast
        val wind = forecast?.windKmh ?: 0.0
        val from = forecast?.windFromDeg ?: 0.0
        if (!radar.wet) {
            val kind = forecast?.kind
            return if (kind == WeatherNow.Kind.FOG || kind == WeatherNow.Kind.CLEAR) forecast
            else WeatherNow(WeatherNow.Kind.CLEAR, 0f, wind, from)
        }
        val kind = when {
            forecast?.kind == WeatherNow.Kind.STORM || radar.dbz >= STORM_DBZ -> WeatherNow.Kind.STORM
            forecast?.kind == WeatherNow.Kind.SLEET -> WeatherNow.Kind.SLEET
            radar.snow -> WeatherNow.Kind.SNOW
            else -> WeatherNow.Kind.RAIN
        }
        val d = radar.dbz
        val intensity = if (radar.snow) when { d < 20 -> 0.4f; d < 30 -> 0.7f; else -> 1.0f }
            else when { d < 25 -> 0.4f; d < 35 -> 0.7f; else -> 1.0f }
        return WeatherNow(kind, intensity, wind, from)
    }

    // RainViewer's Universal Blue colors (ARGB) and the reflectivity each stands for.
    private val RAIN_DBZ = intArrayOf(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95)
    private val RAIN_ARGB = intArrayOf(0x64928871.toInt(), 0x6E9E9375.toInt(), 0x78AA9E79.toInt(), 0x82B6A97E.toInt(), 0x8CC2B482.toInt(), 0x96CEC087.toInt(), 0xA0D2C48B.toInt(), 0xAAD6C88F.toInt(), 0xB4DACC93.toInt(), 0xBEDED097.toInt(), 0xFF88DDEE.toInt(), 0xFF6CD1EB.toInt(), 0xFF51C5E8.toInt(), 0xFF36BAE5.toInt(), 0xFF1BAEE2.toInt(), 0xFF00A3E0.toInt(), 0xFF009AD5.toInt(), 0xFF0091CA.toInt(), 0xFF0088BF.toInt(), 0xFF007FB4.toInt(), 0xFF0077AA.toInt(), 0xFF0070A3.toInt(), 0xFF00699C.toInt(), 0xFF006295.toInt(), 0xFF005B8E.toInt(), 0xFF005588.toInt(), 0xFF005180.toInt(), 0xFF004E78.toInt(), 0xFF004A70.toInt(), 0xFF004768.toInt(), 0xFFFFEE00.toInt(), 0xFFFFE000.toInt(), 0xFFFFD200.toInt(), 0xFFFFC500.toInt(), 0xFFFFB700.toInt(), 0xFFFFAA00.toInt(), 0xFFFF9F00.toInt(), 0xFFFF9500.toInt(), 0xFFFF8B00.toInt(), 0xFFFF8100.toInt(), 0xFFFF4400.toInt(), 0xFFF23600.toInt(), 0xFFE62800.toInt(), 0xFFD91B00.toInt(), 0xFFCD0D00.toInt(), 0xFFC10000.toInt(), 0xFFA80000.toInt(), 0xFF8F0000.toInt(), 0xFF760000.toInt(), 0xFF5D0000.toInt(), 0xFFFFAAFF.toInt(), 0xFFFF9FFF.toInt(), 0xFFFF95FF.toInt(), 0xFFFF8BFF.toInt(), 0xFFFF81FF.toInt(), 0xFFFF77FF.toInt(), 0xFFFF6CFF.toInt(), 0xFFFF62FF.toInt(), 0xFFFF58FF.toInt(), 0xFFFF4EFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt())
    private val SNOW_DBZ = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95)
    private val SNOW_ARGB = intArrayOf(0x7FC7FFFF.toInt(), 0x8CC6FFFF.toInt(), 0x99C5FFFF.toInt(), 0xA5C4FFFF.toInt(), 0xB2C3FFFF.toInt(), 0xBFC3FFFF.toInt(), 0xCCC2FFFF.toInt(), 0xD8C1FFFF.toInt(), 0xE5C0FFFF.toInt(), 0xF2BFFFFF.toInt(), 0xFFBFFFFF.toInt(), 0xFFB8F8FF.toInt(), 0xFFB2F2FF.toInt(), 0xFFABEBFF.toInt(), 0xFFA5E5FF.toInt(), 0xFF9FDFFF.toInt(), 0xFF98D8FF.toInt(), 0xFF92D2FF.toInt(), 0xFF8BCBFF.toInt(), 0xFF85C5FF.toInt(), 0xFF7FBFFF.toInt(), 0xFF78B8FF.toInt(), 0xFF72B2FF.toInt(), 0xFF6BABFF.toInt(), 0xFF65A5FF.toInt(), 0xFF5F9FFF.toInt(), 0xFF5B9BFF.toInt(), 0xFF5898FF.toInt(), 0xFF5595FF.toInt(), 0xFF5292FF.toInt(), 0xFF4F8FFF.toInt(), 0xFF4B8BFF.toInt(), 0xFF4888FF.toInt(), 0xFF4585FF.toInt(), 0xFF4282FF.toInt(), 0xFF3F7FFF.toInt(), 0xFF3B7BFF.toInt(), 0xFF3878FF.toInt(), 0xFF3575FF.toInt(), 0xFF3272FF.toInt(), 0xFF2F6FFF.toInt(), 0xFF2B6BFF.toInt(), 0xFF2868FF.toInt(), 0xFF2565FF.toInt(), 0xFF2262FF.toInt(), 0xFF1F5FFF.toInt(), 0xFF1B5BFF.toInt(), 0xFF1858FF.toInt(), 0xFF1555FF.toInt(), 0xFF1252FF.toInt(), 0xFF0F4FFF.toInt(), 0xFF0C4BFF.toInt(), 0xFF0948FF.toInt(), 0xFF0645FF.toInt(), 0xFF0242FF.toInt(), 0xFF003FFF.toInt(), 0xFF003BFF.toInt(), 0xFF0038FF.toInt(), 0xFF0035FF.toInt(), 0xFF0032FF.toInt(), 0xFF002FFF.toInt(), 0xFF002BFF.toInt(), 0xFF0028FF.toInt(), 0xFF0025FF.toInt(), 0xFF0022FF.toInt(), 0xFF001FFF.toInt(), 0xFF001BFF.toInt(), 0xFF0018FF.toInt(), 0xFF0015FF.toInt(), 0xFF0012FF.toInt(), 0xFF000FFF.toInt(), 0xFF000CFF.toInt(), 0xFF0009FF.toInt(), 0xFF0006FF.toInt(), 0xFF0002FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt())

}
