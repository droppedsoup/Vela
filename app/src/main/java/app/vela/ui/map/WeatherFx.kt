package app.vela.ui.map

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import app.vela.core.VelaConfig
import app.vela.core.data.weather.MetNoWeather
import app.vela.core.data.weather.RainRadar
import app.vela.core.model.distanceTo
import app.vela.core.data.weather.WeatherNow
import app.vela.core.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Weather effects over the map (SPEC 6, Weather effects): faint rain, snow, sleet, storm, fog
 * or wind drawn over the map for the weather where the map is, from MET Norway
 * ([MetNoWeather]) for the kind of weather, with what is falling taken from RainViewer's live
 * radar ([RainRadar]) where it reaches. The whole hourly forecast is kept and the hour that applies
 * is picked every tick, so rain that stops at four stops on the map at four with no request; the forecast is
 * asked again when the position (to one decimal, about 11 km) changes, which a drive does every
 * few minutes, and when the answer expires (its `Expires` header, at least [MIN_REFRESH_MS] and
 * at most [MAX_REFRESH_MS] on). Nothing is asked or drawn with the setting off (pref `weather_fx`, on). Each effect's look is
 * tuned in Settings > Map ([tune], [Tune]), and [simulated] draws a chosen weather instead of
 * the real one, in the settings preview and, with [simulateOnMap], on the map. Started in `VelaApp`.
 */
object WeatherFx {
    val on = mutableStateOf(true)

    /** The real weather; null before the first answer or with the setting off. */
    val now = mutableStateOf<WeatherNow?>(null)

    /** A weather chosen in the settings editor, drawn in its preview; null for the real one. */
    val simulated = mutableStateOf<WeatherNow?>(null)

    /** Draw [simulated] on the map too, in place of the real weather. Not kept across launches. */
    val simulateOnMap = mutableStateOf(false)

    /** What the map draws now. */
    val shown: WeatherNow? get() = (if (simulateOnMap.value) simulated.value else null) ?: now.value

    /** The real weather where the Android Auto map is (`CarMapRenderer`), kept apart from [now]:
     *  the phone and the car can show different places. */
    val carNow = mutableStateOf<WeatherNow?>(null)

    /** What the car map draws now. */
    val carShown: WeatherNow? get() = if (!on.value) null else (if (simulateOnMap.value) simulated.value else null) ?: carNow.value

    /** How far around the car the weather is read while the map follows it, and the widest view
     *  one weather is drawn for (`VelaMapView` and `CarMapRenderer` both). */
    const val CAR_RADIUS_M = 2_500.0
    const val WIDEST_M = 150_000.0

    /**
     * One effect's dials, each a multiplier on the built-in look (1 is the default): how many
     * particles ([amount]), how strong ([opacity]), how long or big ([size]) and how fast
     * ([speed]); [extra] is the storm's flash strength and the fog's height.
     */
    data class Tune(
        val amount: Float = 1f,
        val opacity: Float = 1f,
        val size: Float = 1f,
        val speed: Float = 1f,
        val extra: Float = 1f,
    )

    private val tunes = mutableStateMapOf<WeatherNow.Kind, Tune>()

    fun tune(kind: WeatherNow.Kind): Tune = tunes[kind] ?: Tune()

    fun setTune(context: Context, kind: WeatherNow.Kind, t: Tune) {
        tunes[kind] = t
        val e = prefs(context).edit()
        e.putFloat(key(kind, "amount"), t.amount).putFloat(key(kind, "opacity"), t.opacity)
            .putFloat(key(kind, "size"), t.size).putFloat(key(kind, "speed"), t.speed).putFloat(key(kind, "extra"), t.extra)
        e.apply()
    }

    fun resetTune(context: Context, kind: WeatherNow.Kind) = setTune(context, kind, Tune())

    private const val MIN_REFRESH_MS = 10 * 60_000L
    private const val MAX_REFRESH_MS = 60 * 60_000L
    private const val KEY = "weather_fx"
    private fun key(kind: WeatherNow.Kind, dial: String) = "weather_fx_${kind.name.lowercase()}_$dial"

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        val p = prefs(context)
        on.value = p.getBoolean(KEY, true)
        for (k in WeatherNow.Kind.entries) {
            if (!p.contains(key(k, "amount"))) continue
            tunes[k] = Tune(
                p.getFloat(key(k, "amount"), 1f), p.getFloat(key(k, "opacity"), 1f),
                p.getFloat(key(k, "size"), 1f), p.getFloat(key(k, "speed"), 1f), p.getFloat(key(k, "extra"), 1f),
            )
        }
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        if (!value) { now.value = null; simulateOnMap.value = false }
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    /** The forecast for a cell and how long it may be kept (ms), or null. */
    private suspend fun fetch(lat: Double, lng: Double): Pair<List<MetNoWeather.Step>, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(MetNoWeather.url(lat, lng)).header("User-Agent", VelaConfig.VELA_UA).build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val steps = r.body?.string()?.let(MetNoWeather::parseSeries).orEmpty()
                    if (steps.isEmpty()) return@use null
                    val keep = r.headers.getDate("Expires")?.time?.let { it - System.currentTimeMillis() } ?: MIN_REFRESH_MS
                    steps to keep.coerceIn(MIN_REFRESH_MS, MAX_REFRESH_MS)
                }
        }.getOrNull()
    }

    /** Where the drawn weather comes from now, for the settings line: the radar's frame time
     *  (epoch s) when the radar decided what falls, else null (the forecast alone). */
    val radarFrameS = mutableStateOf<Long?>(null)

    private const val RADAR_CHECK_MS = 2 * 60_000L
    private const val TICK_MS = 30_000L
    private const val PLACE_POLL_MS = 250L

    /** The newest radar frame, or null. */
    private suspend fun radarFrame(): RainRadar.Frame? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(RainRadar.MAPS_URL).header("User-Agent", VelaConfig.VELA_UA).build())
                .execute().use { r -> if (r.isSuccessful) r.body?.string()?.let(RainRadar::latestFrame) else null }
        }.getOrNull()
    }

    /** A tile's pixels (ARGB, [RainRadar.TILE] square), or null. */
    private suspend fun tilePixels(url: String): IntArray? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", VelaConfig.VELA_UA).build()).execute().use { r ->
                val bytes = if (r.isSuccessful) r.body?.bytes() else null
                val bmp = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return@use null
                if (bmp.width != RainRadar.TILE || bmp.height != RainRadar.TILE) { bmp.recycle(); return@use null }
                IntArray(RainRadar.TILE * RainRadar.TILE).also { px -> bmp.getPixels(px, 0, RainRadar.TILE, 0, 0, RainRadar.TILE, RainRadar.TILE); bmp.recycle() }
            }
        }.getOrNull()
    }

    /** Forecasts by 0.1-degree cell with when each expires (elapsed ms), radar tiles by frame and
     *  tile, coverage tiles by tile: kept across places so going back to one is instant. Small
     *  (a tile's pixels are 256 KB) and cleared under memory pressure. */
    private val forecasts = lru<Pair<Long, Long>, Pair<List<MetNoWeather.Step>, Long>>(24)
    private val radarTiles = lru<String, IntArray>(4)
    private val coverageTiles = lru<Long, IntArray>(6)
    @Volatile private var frame: RainRadar.Frame? = null
    @Volatile private var frameCheckedAt = 0L
    @Volatile private var pressureHooked = false

    private fun <K, V> lru(max: Int) = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<K, V>) = size > max
    }

    private fun cellOf(p: LatLng) = Math.round(p.lat * 10) to Math.round(p.lng * 10)

    /** A place to draw the weather of: [at], and how far around it the view reaches ([radiusM]),
     *  which is how much of the radar is read. */
    data class Place(val at: LatLng, val radiusM: Double)

    /** What a place is worked by: its forecast cell and the radar disc's size in pixels. */
    private fun keyOf(p: Place) = Triple(Math.round(p.at.lat * 10), Math.round(p.at.lng * 10), RainRadar.radiusPx(p.radiusM, p.at.lat))

    /** The forecast for [cell], from the cache while it has not expired, else asked. */
    private suspend fun forecastFor(cell: Pair<Long, Long>): List<MetNoWeather.Step>? {
        val clock = SystemClock.elapsedRealtime()
        synchronized(forecasts) { forecasts[cell] }?.let { (series, until) -> if (clock < until) return series }
        val got = fetch(cell.first / 10.0, cell.second / 10.0) ?: return synchronized(forecasts) { forecasts[cell] }?.first
        synchronized(forecasts) { forecasts[cell] = got.first to (clock + got.second) }
        return got.first
    }

    /** The radar reading at [p]: the newest frame (its list checked at most every
     *  [RADAR_CHECK_MS]), its tile under [p] and that tile's coverage, each fetched once. */
    private suspend fun radarAt(p: LatLng, radiusM: Double): RainRadar.Reading? {
        val clock = SystemClock.elapsedRealtime()
        if (frame == null || clock - frameCheckedAt >= RADAR_CHECK_MS) {
            frameCheckedAt = clock
            radarFrame()?.let { frame = it }
        }
        val f = frame ?: return null
        val px = RainRadar.pixelOf(p.lat, p.lng)
        val key = "${f.path}/${px[0]}/${px[1]}"
        val ck = (px[0].toLong() shl 32) or px[1].toLong()
        val (tile, cov) = coroutineScope {
            val t = async { synchronized(radarTiles) { radarTiles[key] } ?: tilePixels(RainRadar.tileUrl(f, px[0], px[1]))?.also { synchronized(radarTiles) { radarTiles[key] = it } } }
            val c = async { synchronized(coverageTiles) { coverageTiles[ck] } ?: tilePixels(RainRadar.coverageUrl(px[0], px[1]))?.also { synchronized(coverageTiles) { coverageTiles[ck] = it } } }
            t.await() to c.await()
        }
        return tile?.let { RainRadar.read(it, cov, px[2], px[3], f.timeS, RainRadar.radiusPx(radiusM, p.lat)) }
    }

    /**
     * Keeps [now] current for the place [at] while composed and the setting is on: where the car
     * is, or the place the map is looking at when that is far from it (`VelaMapView` decides,
     * SPEC 6). Two sources, asked together: the hourly forecast for the place's 0.1-degree cell
     * for the kind of weather, wind and fog, and the live radar for what is falling there. A new
     * place is worked at once; otherwise every [TICK_MS], which picks up a new radar frame (its
     * list checked every [RADAR_CHECK_MS]), the forecast's next hour, and a drive's movement
     * (the radar tile is read again locally). [RainRadar.combine] puts them together.
     */
    @Composable
    fun Keeper(at: Place?) {
        val pos = androidx.compose.runtime.rememberUpdatedState(at)
        val enabled = on.value
        LaunchedEffect(enabled) {
            if (!enabled) return@LaunchedEffect
            keep({ pos.value }, now, showsRadar = true)
        }
    }

    /**
     * The loop behind [Keeper], for any place source: works the place [place] returns into
     * [out] until canceled, as [Keeper] describes. [showsRadar] also keeps [radarFrameS], the
     * settings line's source, which only the phone map sets.
     */
    suspend fun keep(place: () -> Place?, out: androidx.compose.runtime.MutableState<WeatherNow?>, showsRadar: Boolean = false) = coroutineScope {
        if (!pressureHooked) {
            pressureHooked = true
            app.vela.ui.MemoryPressure.register { level ->
                if (app.vela.ui.MemoryPressure.isSevere(level)) {
                    synchronized(radarTiles) { radarTiles.clear() }; synchronized(coverageTiles) { coverageTiles.clear() }
                }
            }
        }
        var lastCell: Pair<Long, Long>? = null
        while (isActive) {
            val at = place()
            if (at == null) {
                // No place: a view too wide for one weather (a region or a country).
                if (out.value != null) out.value = null
                if (showsRadar) radarFrameS.value = null
                lastCell = null
            } else {
                val p = at.at
                val cell = cellOf(p)
                val (series, reading) = coroutineScope {
                    val fc = async { forecastFor(cell) }
                    val rd = async { radarAt(p, at.radiusM) }
                    fc.await() to rd.await()
                }
                // Still the place asked for: a pan that moved on meanwhile is worked next pass.
                if (place()?.let(::keyOf) == keyOf(at)) {
                    val nowS = System.currentTimeMillis() / 1000
                    val w = RainRadar.combine(series?.let { MetNoWeather.at(it, System.currentTimeMillis()) }, reading, nowS)
                    if (showsRadar) radarFrameS.value = if (reading != null && reading.covered && nowS - reading.frameTimeS <= RainRadar.MAX_AGE_S) reading.frameTimeS else null
                    // A new place with nothing known clears the old place's weather rather than
                    // showing it there.
                    if (w != null || cell != lastCell) out.value = w
                    lastCell = cell
                }
            }
            // Sleep until the next tick or until the place changes (its cell, or a zoom that
            // changes how much of the radar is read), whichever is first.
            val from = place()?.let(::keyOf)
            // Polled rather than observed: the car's place is a plain field, and outside a
            // running activity no snapshot observer would see it change.
            withTimeoutOrNull(TICK_MS) {
                while (place()?.let(::keyOf) == from) delay(PLACE_POLL_MS)
            }
        }
    }
}

/** One drawn particle's fixed traits, picked once: where it starts, its speed and its size. */
private class Particles(n: Int, seed: Int) {
    val x = FloatArray(n); val y = FloatArray(n); val speed = FloatArray(n); val size = FloatArray(n); val phase = FloatArray(n)
    init {
        val r = java.util.Random(seed.toLong())
        for (i in 0 until n) { x[i] = r.nextFloat(); y[i] = r.nextFloat(); speed[i] = 0.7f + r.nextFloat() * 0.6f; size[i] = r.nextFloat(); phase[i] = r.nextFloat() * 6.28f }
    }
}

/** The most particles any amount dial can ask for, per effect (twice the default). */
private const val RAIN_MAX = 220

/** Mapcraft's rain and snow are drawn on a grid of cells this many dp across. */
private const val BLOCK_PX_DP = 3f

private fun snap(v: Float, cell: Float) = kotlin.math.floor(v / cell) * cell
private const val SNOW_MAX = 160
private const val GUST_MAX = 18
private const val FOG_BANKS = 6

/** How a lightning strike's light runs over time (ms since it began): a flash, a dip, a second
 *  flash, then a fade, the flicker that makes a strike read as lightning. */
private fun strikeEnvelope(ms: Long): Float = when {
    ms < 0 -> 0f
    ms < 70 -> 1f
    ms < 130 -> 0.25f
    ms < 210 -> 0.85f
    ms < 650 -> 0.85f * (1f - (ms - 210) / 440f)
    else -> 0f
}

/** One lightning bolt, in screen shares: a jagged trunk from above the top edge down into the
 *  upper half, and a branch off it. Made fresh for each strike. */
private class Bolt(val trunk: FloatArray, val branch: FloatArray, val startMs: Long) {
    companion object {
        fun make(r: java.util.Random, now: Long): Bolt {
            val x0 = 0.15f + r.nextFloat() * 0.7f
            val end = 0.42f + r.nextFloat() * 0.25f
            val n = 11 + r.nextInt(4)
            val trunk = FloatArray((n + 1) * 2)
            var x = x0
            for (i in 0..n) {
                trunk[2 * i] = x; trunk[2 * i + 1] = -0.02f + end * i / n
                x += (r.nextFloat() - 0.5f) * 0.09f
            }
            val from = 3 + r.nextInt(maxOf(1, n - 6))
            val m = 4 + r.nextInt(3)
            val side = if (r.nextBoolean()) 1f else -1f
            val branch = FloatArray((m + 1) * 2)
            var bx = trunk[2 * from]; var by = trunk[2 * from + 1]
            for (i in 0..m) {
                branch[2 * i] = bx; branch[2 * i + 1] = by
                bx += side * (0.02f + r.nextFloat() * 0.04f); by += 0.025f + r.nextFloat() * 0.02f
            }
            return Bolt(trunk, branch, now)
        }
    }
}

/** The colors one map look takes: light inks on the dark map, darker ones on the light map, a
 *  blue-gray mist instead of white fog at night, a stronger flash in the dark. */
private class Inks(dark: Boolean) {
    val rain = if (dark) Color(0xFFA9C8F0) else Color(0xFF4E6E91)
    val rainAlpha = if (dark) 0.30f else 0.32f
    val snow = if (dark) Color.White else Color(0xFF7F97B2)
    val snowCore = if (dark) null else Color.White
    val wind = if (dark) Color(0xFFCFE0FF) else Color(0xFF607890)
    // White mist vanishes on the near-white light map: a gray one reads as fog there.
    val fog = if (dark) Color(0xFFA3B4C8) else Color(0xFFB4BEC9)
    val fogAlpha = if (dark) 0.32f else 0.50f
    // A pale glow washes out on the light map; a deep blue one carries the bolt there.
    val boltGlow = if (dark) Color(0xFF8FB8FF) else Color(0xFF4C6EF5)
    val boltCore = Color.White
    val sky = if (dark) Color(0xFFCFE0FF) else Color(0xFFDDE6FF)
    val skyAlpha = if (dark) 0.38f else 0.35f
}

/**
 * The effect layer. Particle places are worked out from their fixed traits and one clock, read
 * only in the draw phase, so a frame redraws without recomposing; the clock advances at about
 * 30 frames a second and stops when nothing moves (clear skies with little wind, the setting
 * off). [weather] is what to draw (the map passes [WeatherFx.shown], the settings preview the
 * simulated weather), [dark] whether the map under it is dark. [screenWindDeg] is where the
 * wind blows toward on screen (0 up, 90 right).
 */
@Composable
fun WeatherOverlay(
    dark: Boolean,
    screenWindDeg: () -> Float,
    modifier: Modifier = Modifier,
    weather: WeatherNow? = WeatherFx.shown,
) {
    val w = weather ?: return
    if (!WeatherFx.on.value) return
    val animate = WeatherPainter.animates(w)
    val clock = remember { mutableFloatStateOf(0f) }
    val painter = remember { WeatherPainter() }
    LaunchedEffect(animate, w.kind) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            val t = androidx.compose.runtime.withFrameNanos { it }
            if (t - last < 30_000_000L) continue // about 30 frames a second is plenty for a backdrop
            last = t
            painter.tick(w, SystemClock.uptimeMillis())
            clock.floatValue = (t / 1e9).toFloat() % 10_000f
        }
    }
    val blocky = app.vela.ui.MapColors.mapcraft()
    Canvas(modifier.fillMaxSize()) {
        with(painter) { paint(w, clock.floatValue, dark, screenWindDeg(), blocky) }
    }
}

/**
 * Draws one frame of the weather effects for a clock [paint]'s `t` (seconds). Holds the
 * particles' fixed traits and the storm's current bolt. The phone map draws it in a Compose
 * canvas ([WeatherOverlay]); the Android Auto map draws it over each car frame ([paintOn]).
 */
internal class WeatherPainter {
    private val rain = Particles(RAIN_MAX, 11)
    private val snow = Particles(SNOW_MAX, 23)
    private val gusts = Particles(GUST_MAX, 37)
    private val banks = Particles(FOG_BANKS, 53)
    private val lightInk = Inks(false)
    private val darkInk = Inks(true)
    private val rnd = java.util.Random()
    private var bolt: Bolt? = null
    private var nextStrike = 0L
    private var scope: androidx.compose.ui.graphics.drawscope.CanvasDrawScope? = null

    companion object {
        /** Whether [w] moves: anything falling, fog, or wind past the windy line. */
        fun animates(w: WeatherNow): Boolean = w.kind != WeatherNow.Kind.CLEAR || w.windy
    }

    /** Starts the storm's next strike when it is due (uptime ms). */
    fun tick(w: WeatherNow, nowMs: Long) {
        if (w.kind != WeatherNow.Kind.STORM) { nextStrike = 0L; return }
        if (nextStrike == 0L) nextStrike = nowMs + 2_500L
        if (nowMs >= nextStrike) {
            bolt = Bolt.make(rnd, nowMs)
            // Heavier storms strike more often: 5 to 11 s apart at full intensity.
            nextStrike = nowMs + ((7_000L + rnd.nextInt(9_000)) * (1.3f - 0.5f * w.intensity)).toLong()
        }
    }

    /** One frame on a plain Android canvas of [width] by [height] pixels at [density]. */
    fun paintOn(canvas: android.graphics.Canvas, width: Int, height: Int, density: Float, w: WeatherNow, t: Float, dark: Boolean, screenWindDeg: Float, blocky: Boolean) {
        val ds = scope ?: androidx.compose.ui.graphics.drawscope.CanvasDrawScope().also { scope = it }
        ds.draw(
            androidx.compose.ui.unit.Density(density), androidx.compose.ui.unit.LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(canvas), Size(width.toFloat(), height.toFloat()),
        ) { paint(w, t, dark, screenWindDeg, blocky) }
    }

    fun androidx.compose.ui.graphics.drawscope.DrawScope.paint(w: WeatherNow, t: Float, dark: Boolean, screenWindDeg: Float, blocky: Boolean) {
        val ink = if (dark) darkInk else lightInk
        val tune = WeatherFx.tune(w.kind)
        val windTune = WeatherFx.tune(WeatherNow.Kind.CLEAR)
        val falls = w.kind != WeatherNow.Kind.CLEAR && w.kind != WeatherNow.Kind.FOG
        val wd = screenWindDeg * (PI / 180.0).toFloat()
        // Sideways drift on screen from the wind, as a share of the fall.
        val windX = (sin(wd) * (w.windKmh / 60.0).coerceAtMost(1.0)).toFloat()
        val W = size.width; val H = size.height
        val k = w.intensity
        val dp = density
        // Each effect's clock runs at its own speed dial; positions are shares of the screen,
        // worked in doubles, since the clock runs for hours.
        val td = t.toDouble() * tune.speed
        if (w.kind == WeatherNow.Kind.FOG) {
            // A haze from the top (the far distance in a tilted view) and banks drifting across.
            val haze = (ink.fogAlpha * 0.6f * tune.opacity).coerceIn(0f, 1f)
            drawRect(Brush.verticalGradient(listOf(ink.fog.copy(alpha = haze), Color.Transparent), endY = H * (0.55f * tune.extra).coerceIn(0.1f, 1f)))
            for (i in 0 until FOG_BANKS) {
                val drift = ((banks.x[i] + td * 0.012 * banks.speed[i]) % 1.6).toFloat() - 0.3f
                val cx = drift * W
                val cy = (0.12f + banks.y[i] * 0.8f) * H
                val rx = (0.45f + 0.35f * banks.size[i]) * W
                val ry = (0.10f + 0.08f * banks.size[i]) * H * tune.extra.coerceIn(0.3f, 2f)
                val a = (ink.fogAlpha * (0.55f + 0.45f * banks.size[i]) * tune.opacity).coerceIn(0f, 1f)
                scale(1f, ry / rx, pivot = Offset(cx, cy)) {
                    drawCircle(
                        Brush.radialGradient(listOf(ink.fog.copy(alpha = a), ink.fog.copy(alpha = a * 0.4f), Color.Transparent), center = Offset(cx, cy), radius = rx),
                        radius = rx, center = Offset(cx, cy),
                    )
                }
            }
        }
        if (w.kind == WeatherNow.Kind.RAIN || w.kind == WeatherNow.Kind.STORM || w.kind == WeatherNow.Kind.SLEET) {
            val n = (RAIN_MAX / 2 * (0.35f + 0.65f * k) * tune.amount).toInt().coerceIn(0, RAIN_MAX)
                .let { if (w.kind == WeatherNow.Kind.SLEET) it / 2 else it }
            val alpha = ((ink.rainAlpha + 0.12f * k) * tune.opacity).coerceIn(0f, 1f)
            for (i in 0 until n) {
                val fallN = 1.1 * rain.speed[i] * td
                val y = (((rain.y[i] + fallN) % 1.1) * H).toFloat() - H * 0.05f
                val len = (14f + 14f * rain.size[i]) * dp * (0.7f + 0.3f * k) * tune.size
                val dx = windX * len * 0.6f
                val x = ((((rain.x[i] + windX * fallN * 0.35 * H / W) % 1.0) + 1.0) % 1.0 * W).toFloat()
                if (blocky) {
                    // Mapcraft: straight-down streaks on a pixel grid.
                    val cell = BLOCK_PX_DP * dp
                    drawRect(ink.rain.copy(alpha = alpha), Offset(snap(x, cell), snap(y, cell)), Size(cell, snap(len, cell).coerceAtLeast(cell * 3)))
                } else {
                    drawLine(ink.rain.copy(alpha = alpha), Offset(x, y), Offset(x + dx, y + len), strokeWidth = 1.1f * dp * tune.size.coerceAtLeast(0.6f), cap = StrokeCap.Round)
                }
            }
        }
        if (w.kind == WeatherNow.Kind.SNOW || w.kind == WeatherNow.Kind.SLEET) {
            val n = (SNOW_MAX / 2 * (0.35f + 0.65f * k) * tune.amount).toInt().coerceIn(0, SNOW_MAX)
                .let { if (w.kind == WeatherNow.Kind.SLEET) it / 2 else it }
            for (i in 0 until n) {
                val fallN = 0.09 * snow.speed[i] * td
                val y = (((snow.y[i] + fallN) % 1.05) * H).toFloat() - H * 0.02f
                val sway = sin(t * 0.9f * tune.speed + snow.phase[i]) * 12f * dp
                val x = (((((snow.x[i] + windX * fallN * 0.8 * H / W) % 1.0) + 1.0) % 1.0) * W).toFloat() + sway
                val r = (1.2f + 2.0f * snow.size[i]) * dp * tune.size
                val a = ((0.55f + 0.25f * snow.size[i]) * tune.opacity).coerceIn(0f, 1f)
                if (blocky) {
                    val cell = BLOCK_PX_DP * dp
                    val side = snap(r * 2f, cell).coerceAtLeast(cell)
                    val o = Offset(snap(x, cell), snap(y, cell))
                    drawRect(ink.snow.copy(alpha = a), o, Size(side, side))
                    ink.snowCore?.let { if (side > cell) drawRect(it.copy(alpha = a), o + Offset(cell / 2f, cell / 2f), Size(side - cell, side - cell)) }
                } else {
                    drawCircle(ink.snow.copy(alpha = a), radius = r, center = Offset(x, y))
                    // On the light map a white center keeps a flake reading as snow, not a gray dot.
                    ink.snowCore?.let { drawCircle(it.copy(alpha = a), radius = r * 0.55f, center = Offset(x, y)) }
                }
            }
        }
        if (!falls && w.windy) {
            // A few long faint streaks crossing with the wind, each fading in and out. Wind has
            // its own dials (kept under CLEAR: it is what draws when nothing falls).
            val strength = ((w.windKmh - WeatherNow.WINDY_KMH) / 40.0).coerceIn(0.3, 1.0).toFloat()
            val dirX = sin(wd); val dirY = -cos(wd)
            val n = (GUST_MAX / 2 * windTune.amount).toInt().coerceIn(0, GUST_MAX)
            val wtd = t.toDouble() * windTune.speed
            for (i in 0 until n) {
                val life = ((wtd * 0.35 * gusts.speed[i] + gusts.phase[i]) % 1.0).toFloat()
                val fade = sin(life * PI.toFloat())
                val travel = (life - 0.5f) * W * 1.2f
                val cx = gusts.x[i] * W + dirX * travel
                val cy = gusts.y[i] * H + dirY * travel
                val len = (60f + 80f * gusts.size[i]) * dp * windTune.size
                drawLine(
                    ink.wind.copy(alpha = (0.20f * fade * strength * windTune.opacity).coerceIn(0f, 1f)),
                    Offset(cx - dirX * len / 2, cy - dirY * len / 2), Offset(cx + dirX * len / 2, cy + dirY * len / 2),
                    strokeWidth = 1.4f * dp, cap = StrokeCap.Round,
                )
            }
        }
        val b = bolt
        if (b != null) {
            val env = strikeEnvelope(SystemClock.uptimeMillis() - b.startMs) * tune.extra.coerceIn(0f, 3f)
            if (env > 0f) {
                // The sky lights up around where the bolt comes down, then the bolt itself.
                val top = Offset(b.trunk[0] * W, H * 0.08f)
                drawRect(Brush.radialGradient(listOf(ink.sky.copy(alpha = (ink.skyAlpha * env).coerceIn(0f, 1f)), Color.Transparent), center = top, radius = maxOf(W, H) * 0.9f))
                fun path(pts: FloatArray): androidx.compose.ui.graphics.Path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(pts[0] * W, pts[1] * H)
                    var i = 2
                    while (i < pts.size) { lineTo(pts[i] * W, pts[i + 1] * H); i += 2 }
                }
                val trunk = path(b.trunk); val branch = path(b.branch)
                val e = env.coerceAtMost(1f)
                for ((p, scaleW) in listOf(trunk to 1f, branch to 0.6f)) {
                    drawPath(p, ink.boltGlow.copy(alpha = 0.30f * e), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 12f * dp * scaleW, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    drawPath(p, ink.boltGlow.copy(alpha = 0.65f * e), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.5f * dp * scaleW, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    drawPath(p, ink.boltCore.copy(alpha = e), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f * dp * scaleW, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
    }
}
