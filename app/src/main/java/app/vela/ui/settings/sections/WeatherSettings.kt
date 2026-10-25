package app.vela.ui.settings.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vela.R
import app.vela.core.data.weather.WeatherNow
import app.vela.ui.dpadHighlight
import app.vela.ui.map.WeatherFx
import app.vela.ui.map.WeatherOverlay
import app.vela.ui.settings.CollapsibleSectionTitle
import app.vela.ui.settings.Hint
import app.vela.ui.settings.SubHead
import app.vela.ui.settings.ToggleRow
import app.vela.ui.theme.isMapDark

/** The effects the editor offers, in its chip order. Wind is drawn when nothing falls, so its
 *  dials are kept under [WeatherNow.Kind.CLEAR]. */
private val EDITED = listOf(
    WeatherNow.Kind.RAIN, WeatherNow.Kind.SNOW, WeatherNow.Kind.SLEET,
    WeatherNow.Kind.STORM, WeatherNow.Kind.FOG, WeatherNow.Kind.CLEAR,
)

/**
 * The weather effects editor (SPEC 6, Weather effects): a live preview of one effect over a
 * stand-in map, the conditions it is simulated in (intensity, wind), and that effect's own dials,
 * which the map uses from then on. "Show on the map" draws the simulation on the real map until
 * it is turned off or the app restarts.
 */
@Composable
internal fun WeatherEditor() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    CollapsibleSectionTitle(stringResource(R.string.settings_weather_customize), open, Modifier.padding(horizontal = 16.dp)) { open = !open }
    if (!open) return
    var kind by remember { mutableStateOf(WeatherFx.simulated.value?.kind ?: WeatherNow.Kind.RAIN) }
    var intensity by remember { mutableFloatStateOf(0.7f) }
    var windKmh by remember { mutableFloatStateOf(20f) }
    var windFrom by remember { mutableFloatStateOf(270f) }
    // Wind alone draws only past WINDY_KMH, so the wind chip simulates at least that.
    val simWind = if (kind == WeatherNow.Kind.CLEAR) maxOf(windKmh, WeatherNow.WINDY_KMH.toFloat() + 10f) else windKmh
    LaunchedEffect(kind, intensity, simWind, windFrom) {
        val falls = kind != WeatherNow.Kind.CLEAR && kind != WeatherNow.Kind.FOG
        WeatherFx.simulated.value = WeatherNow(kind, if (falls) intensity else 0f, simWind.toDouble(), windFrom.toDouble())
    }
    // Leaving the editor ends the simulation unless it is on the map.
    DisposableEffect(Unit) { onDispose { if (!WeatherFx.simulateOnMap.value) WeatherFx.simulated.value = null } }

    // The preview starts in the map's own look and can be flipped, since the effects are drawn
    // differently over the dark map.
    val mapDark = isMapDark()
    var dark by remember(mapDark) { mutableStateOf(mapDark) }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(220.dp).clip(RoundedCornerShape(16.dp)),
    ) {
        MapBackdrop(dark)
        WeatherOverlay(dark = dark, screenWindDeg = { (windFrom + 180f) % 360f }, weather = WeatherFx.simulated.value)
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((isDark, label) in listOf(false to R.string.settings_weather_light_map, true to R.string.settings_weather_dark_map)) {
            FilterChip(
                selected = dark == isDark,
                onClick = { dark = isDark },
                label = { Text(stringResource(label)) },
                shape = CircleShape,
                modifier = Modifier.dpadHighlight(CircleShape),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (k in EDITED) {
            FilterChip(
                selected = kind == k,
                onClick = { kind = k },
                label = { Text(stringResource(kindLabel(k))) },
                shape = CircleShape,
                modifier = Modifier.dpadHighlight(CircleShape),
            )
        }
    }

    SubHead(stringResource(R.string.settings_weather_conditions))
    if (kind != WeatherNow.Kind.CLEAR && kind != WeatherNow.Kind.FOG) {
        Dial(stringResource(R.string.settings_weather_intensity), intensity, 0.4f..1f, label = "${(intensity * 100).toInt()}%") { intensity = it }
    }
    if (kind != WeatherNow.Kind.FOG) {
        Dial(stringResource(R.string.settings_weather_wind_speed), windKmh, 0f..90f, label = "${windKmh.toInt()} km/h") { windKmh = it }
        Dial(stringResource(R.string.settings_weather_wind_from), windFrom, 0f..359f, label = "${windFrom.toInt()}°") { windFrom = it }
    }

    SubHead(stringResource(R.string.settings_weather_look, stringResource(kindLabel(kind))))
    val t = WeatherFx.tune(kind)
    fun put(n: WeatherFx.Tune) = WeatherFx.setTune(context, kind, n)
    val pct = { v: Float -> "${(v * 100).toInt()}%" }
    when (kind) {
        WeatherNow.Kind.FOG -> {
            Dial(stringResource(R.string.settings_weather_haze_strength), t.opacity, 0f..3f, label = pct(t.opacity)) { put(t.copy(opacity = it)) }
            Dial(stringResource(R.string.settings_weather_haze_height), t.extra, 0.2f..1.6f, label = pct(t.extra)) { put(t.copy(extra = it)) }
        }
        else -> {
            val sizeLabel = when (kind) {
                WeatherNow.Kind.SNOW -> R.string.settings_weather_flake_size
                WeatherNow.Kind.CLEAR -> R.string.settings_weather_streak_length
                else -> R.string.settings_weather_drop_length
            }
            Dial(stringResource(R.string.settings_weather_amount), t.amount, 0f..2f, label = pct(t.amount)) { put(t.copy(amount = it)) }
            Dial(stringResource(R.string.settings_weather_opacity), t.opacity, 0f..3f, label = pct(t.opacity)) { put(t.copy(opacity = it)) }
            Dial(stringResource(sizeLabel), t.size, 0.4f..2.5f, label = pct(t.size)) { put(t.copy(size = it)) }
            Dial(stringResource(R.string.settings_weather_speed), t.speed, 0.2f..2.5f, label = pct(t.speed)) { put(t.copy(speed = it)) }
            if (kind == WeatherNow.Kind.STORM) {
                Dial(stringResource(R.string.settings_weather_flash), t.extra, 0f..3f, label = pct(t.extra)) { put(t.copy(extra = it)) }
            }
        }
    }
    TextButton(
        onClick = { WeatherFx.resetTune(context, kind) },
        modifier = Modifier.padding(horizontal = 8.dp).dpadHighlight(CircleShape),
    ) { Text(stringResource(R.string.settings_weather_reset, stringResource(kindLabel(kind)))) }

    ToggleRow(
        label = stringResource(R.string.settings_weather_on_map),
        checked = WeatherFx.simulateOnMap.value,
        onCheckedChange = { WeatherFx.simulateOnMap.value = it },
        hint = stringResource(R.string.settings_weather_on_map_hint),
    )
    Hint(stringResource(R.string.settings_weather_editor_hint))
}

/** What the map is drawing now and where it came from: the radar (with its frame's age) or the
 *  forecast alone. */
@Composable
internal fun WeatherNowLine() {
    val w = WeatherFx.now.value
    val text = if (w == null) stringResource(R.string.settings_weather_now_waiting) else {
        val what = stringResource(
            when {
                w.kind == WeatherNow.Kind.CLEAR && w.windy -> R.string.settings_weather_wind
                w.kind == WeatherNow.Kind.CLEAR -> R.string.settings_weather_clear
                else -> kindLabel(w.kind)
            },
        )
        val frame = WeatherFx.radarFrameS.value
        if (frame != null) {
            val min = ((System.currentTimeMillis() / 1000 - frame) / 60).toInt().coerceAtLeast(0)
            androidx.compose.ui.res.pluralStringResource(R.plurals.settings_weather_now_radar, min, what, min)
        } else stringResource(R.string.settings_weather_now_forecast, what)
    }
    Hint(text)
}

private fun kindLabel(k: WeatherNow.Kind): Int = when (k) {
    WeatherNow.Kind.RAIN -> R.string.settings_weather_rain
    WeatherNow.Kind.SNOW -> R.string.settings_weather_snow
    WeatherNow.Kind.SLEET -> R.string.settings_weather_sleet
    WeatherNow.Kind.STORM -> R.string.settings_weather_storm
    WeatherNow.Kind.FOG -> R.string.settings_weather_fog
    WeatherNow.Kind.CLEAR -> R.string.settings_weather_wind
}

/** One dial: its name and value on a line, the slider under it. */
@Composable
private fun Dial(name: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.dpadHighlight(RoundedCornerShape(12.dp)),
        )
    }
}

/** A stand-in map for the preview: land, blocks and a few roads in the map's light or dark look. */
@Composable
private fun MapBackdrop(dark: Boolean) {
    val land = if (dark) Color(0xFF1B2333) else Color(0xFFF8F7F7)
    val block = if (dark) Color(0xFF223A5E) else Color(0xFFE8E9ED)
    val road = if (dark) Color(0xFF3E5574) else Color(0xFFAAB9C9)
    val route = Color(0xFF1A73E8)
    Canvas(Modifier.fillMaxSize().background(land)) {
        val w = size.width; val h = size.height
        for (i in 0 until 6) for (j in 0 until 4) {
            drawRect(block, topLeft = Offset(w * (0.05f + i * 0.16f), h * (0.08f + j * 0.24f)), size = androidx.compose.ui.geometry.Size(w * 0.1f, h * 0.14f))
        }
        drawLine(road, Offset(0f, h * 0.55f), Offset(w, h * 0.45f), strokeWidth = 10.dp.toPx())
        drawLine(road, Offset(w * 0.3f, 0f), Offset(w * 0.42f, h), strokeWidth = 8.dp.toPx())
        drawLine(road, Offset(w * 0.75f, 0f), Offset(w * 0.68f, h), strokeWidth = 6.dp.toPx())
        drawLine(route, Offset(w * 0.42f, h), Offset(w * 0.36f, h * 0.5f), strokeWidth = 8.dp.toPx())
        drawLine(route, Offset(w * 0.36f, h * 0.5f), Offset(w, h * 0.45f), strokeWidth = 8.dp.toPx())
    }
}
