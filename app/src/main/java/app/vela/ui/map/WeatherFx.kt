package app.vela.ui.map

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
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
 * nothing is asked or drawn with the setting off (pref `weather_fx`, on). Started in `VelaApp`.
 */
object WeatherFx {
    val on = mutableStateOf(true)

    /** The weather being drawn; null before the first answer or with the setting off. */
    val now = mutableStateOf<WeatherNow?>(null)

    private const val REFRESH_MS = 20 * 60_000L
    private const val KEY = "weather_fx"

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        if (!value) now.value = null
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

/**
 * The effect layer. Particle places are worked out from their fixed traits and one clock, read
 * only in the draw phase, so a frame redraws without recomposing; the clock advances at about
 * 30 frames a second and stops when nothing moves (clear skies, fog, the setting off).
 * [screenWindDeg] is where the wind blows toward on screen (0 up, 90 right).
 */
@Composable
fun WeatherOverlay(dark: Boolean, screenWindDeg: () -> Float, modifier: Modifier = Modifier) {
    val w = WeatherFx.now.value ?: return
    if (!WeatherFx.on.value) return
    val falls = w.kind != WeatherNow.Kind.CLEAR && w.kind != WeatherNow.Kind.FOG
    val animate = falls || w.windy
    val clock = remember { mutableFloatStateOf(0f) }
    val flash = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate, w.kind) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        var nextFlash = SystemClock.uptimeMillis() + 6_000L
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
    val rain = remember { Particles(110, 11) }
    val snow = remember { Particles(80, 23) }
    val gusts = remember { Particles(9, 37) }
    val rainInk = if (dark) Color(0xFFDDE7F5) else Color(0xFF5B7A99)
    val snowInk = if (dark) Color.White else Color(0xFF8FA6BF)
    val windInk = if (dark) Color.White else Color(0xFF6B7F94)
    Canvas(modifier.fillMaxSize()) {
        val t = clock.floatValue
        val td = t.toDouble()
        val wd = screenWindDeg() * (PI / 180.0).toFloat()
        // Sideways drift on screen from the wind, as a share of the fall.
        val windX = (sin(wd) * (w.windKmh / 60.0).coerceAtMost(1.0)).toFloat()
        val W = size.width; val H = size.height
        val k = w.intensity
        val dp = density
        if (w.kind == WeatherNow.Kind.RAIN || w.kind == WeatherNow.Kind.STORM || w.kind == WeatherNow.Kind.SLEET) {
            val n = (rain.x.size * (0.35f + 0.65f * k)).toInt().let { if (w.kind == WeatherNow.Kind.SLEET) it / 2 else it }
            val alpha = if (dark) 0.20f + 0.10f * k else 0.28f + 0.12f * k
            for (i in 0 until n) {
                // Positions in screen shares, worked in doubles: the clock runs for hours.
                val fallN = 1.1 * rain.speed[i] * td
                val y = (((rain.y[i] + fallN) % 1.1) * H).toFloat() - H * 0.05f
                val len = (14f + 14f * rain.size[i]) * dp * (0.7f + 0.3f * k)
                val dx = windX * len * 0.6f
                val x = ((((rain.x[i] + windX * fallN * 0.35 * H / W) % 1.0) + 1.0) % 1.0 * W).toFloat()
                drawLine(rainInk.copy(alpha = alpha), Offset(x, y), Offset(x + dx, y + len), strokeWidth = 1.1f * dp, cap = StrokeCap.Round)
            }
        }
        if (w.kind == WeatherNow.Kind.SNOW || w.kind == WeatherNow.Kind.SLEET) {
            val n = (snow.x.size * (0.35f + 0.65f * k)).toInt().let { if (w.kind == WeatherNow.Kind.SLEET) it / 2 else it }
            for (i in 0 until n) {
                val fallN = 0.09 * snow.speed[i] * td
                val y = (((snow.y[i] + fallN) % 1.05) * H).toFloat() - H * 0.02f
                val sway = sin(t * 0.9f + snow.phase[i]) * 12f * dp
                val x = (((((snow.x[i] + windX * fallN * 0.8 * H / W) % 1.0) + 1.0) % 1.0) * W).toFloat() + sway
                drawCircle(snowInk.copy(alpha = 0.55f + 0.25f * snow.size[i]), radius = (1.2f + 2.0f * snow.size[i]) * dp, center = Offset(x, y))
            }
        }
        if (!falls && w.windy) {
            // A few long faint streaks crossing with the wind, each fading in and out.
            val strength = ((w.windKmh - WeatherNow.WINDY_KMH) / 40.0).coerceIn(0.3, 1.0).toFloat()
            val dirX = sin(wd); val dirY = -cos(wd)
            for (i in gusts.x.indices) {
                val life = ((td * 0.35 * gusts.speed[i] + gusts.phase[i]) % 1.0).toFloat()
                val fade = sin(life * PI.toFloat())
                val travel = (life - 0.5f) * W * 1.2f
                val cx = gusts.x[i] * W + dirX * travel
                val cy = gusts.y[i] * H + dirY * travel
                val len = (60f + 80f * gusts.size[i]) * dp
                drawLine(
                    windInk.copy(alpha = 0.16f * fade * strength),
                    Offset(cx - dirX * len / 2, cy - dirY * len / 2), Offset(cx + dirX * len / 2, cy + dirY * len / 2),
                    strokeWidth = 1.4f * dp, cap = StrokeCap.Round,
                )
            }
        }
        if (w.kind == WeatherNow.Kind.FOG) {
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.10f else 0.22f), Color.Transparent), endY = H * 0.6f))
        }
        val f = flash.floatValue
        if (f > 0f) drawRect(Color.White.copy(alpha = 0.10f * f))
    }
}
