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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import app.vela.core.VelaConfig
import app.vela.core.data.weather.MetNoWeather
import app.vela.core.data.weather.WeatherNow
import app.vela.core.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Weather effects over the map (SPEC 6, Weather effects): faint rain, snow, sleet, storm, fog
 * or wind drawn over the map for the weather where the map is, from MET Norway
 * ([MetNoWeather]). Asked when the position (to one decimal) changes and every [REFRESH_MS];
 * nothing is asked or drawn with the setting off (pref `weather_fx`, on). Each effect's look is
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

    private const val REFRESH_MS = 20 * 60_000L
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

    private suspend fun fetch(lat: Double, lng: Double): WeatherNow? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(MetNoWeather.url(lat, lng)).header("User-Agent", VelaConfig.VELA_UA).build())
                .execute().use { r -> if (r.isSuccessful) r.body?.string()?.let(MetNoWeather::parse) else null }
        }.getOrNull()
    }

    /** Keeps [now] current for [at] while composed and the setting is on. */
    @Composable
    fun Keeper(at: LatLng?) {
        val cell = at?.let { Math.round(it.lat * 10) to Math.round(it.lng * 10) }
        val enabled = on.value
        LaunchedEffect(cell, enabled) {
            if (!enabled || cell == null) return@LaunchedEffect
            while (isActive) {
                val w = fetch(cell.first / 10.0, cell.second / 10.0)
                if (w != null) now.value = w
                delay(REFRESH_MS)
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
private const val SNOW_MAX = 160
private const val GUST_MAX = 18

/**
 * The effect layer. Particle places are worked out from their fixed traits and one clock, read
 * only in the draw phase, so a frame redraws without recomposing; the clock advances at about
 * 30 frames a second and stops when nothing moves (clear skies, fog, the setting off).
 * [weather] is what to draw (the map passes [WeatherFx.shown], the settings preview the
 * simulated weather). [screenWindDeg] is where the wind blows toward on screen (0 up, 90 right).
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
    val falls = w.kind != WeatherNow.Kind.CLEAR && w.kind != WeatherNow.Kind.FOG
    val animate = falls || w.windy
    val clock = remember { mutableFloatStateOf(0f) }
    val flash = remember { mutableFloatStateOf(0f) }
    val tune = WeatherFx.tune(w.kind)
    val windTune = WeatherFx.tune(WeatherNow.Kind.CLEAR)
    LaunchedEffect(animate, w.kind) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        var nextFlash = SystemClock.uptimeMillis() + 3_000L
        while (isActive) {
            val t = androidx.compose.runtime.withFrameNanos { it }
            if (t - last < 30_000_000L) continue // about 30 frames a second is plenty for a backdrop
            last = t
            clock.floatValue = (t / 1e9).toFloat() % 10_000f
            if (w.kind == WeatherNow.Kind.STORM) {
                val now = SystemClock.uptimeMillis()
                if (now >= nextFlash) { flash.floatValue = 1f; nextFlash = now + 8_000L + (Math.random() * 9_000).toLong() }
                else if (flash.floatValue > 0f) flash.floatValue = (flash.floatValue - 0.12f).coerceAtLeast(0f)
            }
        }
    }
    val rain = remember { Particles(RAIN_MAX, 11) }
    val snow = remember { Particles(SNOW_MAX, 23) }
    val gusts = remember { Particles(GUST_MAX, 37) }
    val rainInk = if (dark) Color(0xFFDDE7F5) else Color(0xFF5B7A99)
    val snowInk = if (dark) Color.White else Color(0xFF8FA6BF)
    val windInk = if (dark) Color.White else Color(0xFF6B7F94)
    Canvas(modifier.fillMaxSize()) {
        val t = clock.floatValue
        val wd = screenWindDeg() * (PI / 180.0).toFloat()
        // Sideways drift on screen from the wind, as a share of the fall.
        val windX = (sin(wd) * (w.windKmh / 60.0).coerceAtMost(1.0)).toFloat()
        val W = size.width; val H = size.height
        val k = w.intensity
        val dp = density
        // Each effect's clock runs at its own speed dial; positions are shares of the screen,
        // worked in doubles, since the clock runs for hours.
        val td = t.toDouble() * tune.speed
        if (w.kind == WeatherNow.Kind.RAIN || w.kind == WeatherNow.Kind.STORM || w.kind == WeatherNow.Kind.SLEET) {
            val n = (RAIN_MAX / 2 * (0.35f + 0.65f * k) * tune.amount).toInt().coerceIn(0, RAIN_MAX)
                .let { if (w.kind == WeatherNow.Kind.SLEET) it / 2 else it }
            val alpha = ((if (dark) 0.20f + 0.10f * k else 0.28f + 0.12f * k) * tune.opacity).coerceIn(0f, 1f)
            for (i in 0 until n) {
                val fallN = 1.1 * rain.speed[i] * td
                val y = (((rain.y[i] + fallN) % 1.1) * H).toFloat() - H * 0.05f
                val len = (14f + 14f * rain.size[i]) * dp * (0.7f + 0.3f * k) * tune.size
                val dx = windX * len * 0.6f
                val x = ((((rain.x[i] + windX * fallN * 0.35 * H / W) % 1.0) + 1.0) % 1.0 * W).toFloat()
                drawLine(rainInk.copy(alpha = alpha), Offset(x, y), Offset(x + dx, y + len), strokeWidth = 1.1f * dp * tune.size.coerceAtLeast(0.6f), cap = StrokeCap.Round)
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
                drawCircle(
                    snowInk.copy(alpha = ((0.55f + 0.25f * snow.size[i]) * tune.opacity).coerceIn(0f, 1f)),
                    radius = (1.2f + 2.0f * snow.size[i]) * dp * tune.size,
                    center = Offset(x, y),
                )
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
                    windInk.copy(alpha = (0.16f * fade * strength * windTune.opacity).coerceIn(0f, 1f)),
                    Offset(cx - dirX * len / 2, cy - dirY * len / 2), Offset(cx + dirX * len / 2, cy + dirY * len / 2),
                    strokeWidth = 1.4f * dp, cap = StrokeCap.Round,
                )
            }
        }
        if (w.kind == WeatherNow.Kind.FOG) {
            val a = ((if (dark) 0.10f else 0.22f) * tune.opacity).coerceIn(0f, 1f)
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = a), Color.Transparent), endY = H * (0.6f * tune.extra).coerceIn(0.1f, 1f)))
        }
        val f = flash.floatValue
        if (f > 0f) drawRect(Color.White.copy(alpha = (0.10f * f * tune.extra).coerceIn(0f, 1f)))
    }
}
