package app.vela.core.nav

import app.vela.core.model.LatLng
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Live traffic as lines on the road (SPEC 6, Rasters). Google's traffic arrives only as raster
 * tiles, drawn thin and to each side of the road, one line per direction; scaled up for a close,
 * tilted view they turn into wide jagged strips beside the road. This reads those tiles as a color
 * lookup instead: it walks each road of the open map every [STEP_M], reads the traffic level on
 * the centerline and at [SIDE_M] to either side (so both directions count), and returns the
 * stretches of slow traffic as lines along the road's own geometry, which the map draws smooth.
 *
 * Levels: 0 none or flowing, 1 slow (yellow), 2 heavy (red), 3 stopped (dark red).
 */
object TrafficLines {
    const val STEP_M = 6.0

    /** Offsets across the road read at each step. Google's line for each direction sits a few
     *  meters off the center at z16. */
    val SIDE_M = doubleArrayOf(0.0, 3.0, -3.0, 6.0, -6.0, 9.0, -9.0)

    /** A stretch shorter than this joins its neighbor, or goes when it has none: a cross street
     *  reads the congested road it crosses for a few meters. */
    const val MIN_RUN_M = 30.0

    /** A gap in the traffic this short is bridged, so one missing pixel does not cut a line. */
    const val GAP_M = 13.0

    data class Run(val level: Int, val points: List<LatLng>)

    /** The level a traffic tile pixel stands for (unpremultiplied ARGB parts). Google's colors:
     *  free-flow green (`#16e098`), slow yellow (`#ffcf43`), heavy red, stopped dark red (`#702323`). */
    fun level(r: Int, g: Int, b: Int, a: Int): Int = when {
        a < 100 -> 0
        g - r > 30 && g >= b -> 0
        r >= 200 && g >= 140 && b < 140 -> 1
        r >= 190 && g < 140 -> 2
        r in 81..189 && r > 2 * g -> 3
        else -> 0
    }

    /** The stretches of slow traffic along [roads], each at one level, read through [sample]. */
    fun runs(roads: List<List<LatLng>>, sample: (LatLng) -> Int): List<Run> {
        val out = ArrayList<Run>()
        for (road in roads) if (road.size >= 2) runsOf(road, sample, out)
        return out
    }

    private fun runsOf(road: List<LatLng>, sample: (LatLng) -> Int, out: MutableList<Run>) {
        val pts = ArrayList<LatLng>()
        val lv = ArrayList<Int>()
        // Resample every STEP_M, reading across the road at each point.
        for (i in 0 until road.size - 1) {
            val a = road[i]; val b = road[i + 1]
            val k = cos(Math.toRadians(a.lat))
            val dx = (b.lng - a.lng) * 111_320.0 * k
            val dy = (b.lat - a.lat) * 111_320.0
            val len = hypot(dx, dy)
            if (len <= 0.0) continue
            val nx = -dy / len; val ny = dx / len // unit normal, meters
            val n = kotlin.math.ceil(len / STEP_M).toInt().coerceAtLeast(1)
            val last = if (i == road.size - 2) n else n - 1
            for (j in 0..last) {
                val t = j.toDouble() / n
                val p = LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
                var best = 0
                for (s in SIDE_M) {
                    val q = if (s == 0.0) p else LatLng(p.lat + ny * s / 111_320.0, p.lng + nx * s / (111_320.0 * k))
                    val v = sample(q)
                    if (v > best) best = v
                    if (best == 3) break
                }
                pts += p; lv += best
            }
        }
        if (pts.size < 2) return
        smooth(lv)
        var i = 0
        while (i < lv.size) {
            if (lv[i] == 0) { i++; continue }
            var j = i
            while (j + 1 < lv.size && lv[j + 1] == lv[i]) j++
            // Carry the line one step into the next stretch, so neighboring stretches meet.
            val end = minOf(j + 1, lv.size - 1)
            if (end > i) out += Run(lv[i], pts.subList(i, end + 1).toList())
            i = j + 1
        }
    }

    /** Bridges short gaps and folds short stretches into a neighbor, in place. */
    internal fun smooth(lv: MutableList<Int>) {
        val gap = (GAP_M / STEP_M).toInt()
        // A gap of up to [gap] steps between two slow stretches takes the lower of their levels.
        var i = 0
        while (i < lv.size) {
            if (lv[i] != 0) { i++; continue }
            var j = i
            while (j < lv.size && lv[j] == 0) j++
            if (i > 0 && j < lv.size && j - i <= gap) {
                val fill = minOf(lv[i - 1], lv[j])
                for (k in i until j) lv[k] = fill
            }
            i = j
        }
        // A stretch under MIN_RUN_M takes the level of the longer neighbor it touches, else goes.
        val minSteps = (MIN_RUN_M / STEP_M).toInt()
        var changed = true
        var guard = 0
        while (changed && guard++ < 4) {
            changed = false
            val spans = spans(lv)
            for ((idx, sp) in spans.withIndex()) {
                val (s, e, v) = sp
                if (v == 0 || e - s + 1 >= minSteps) continue
                val prev = spans.getOrNull(idx - 1)?.takeIf { it.third != 0 }
                val next = spans.getOrNull(idx + 1)?.takeIf { it.third != 0 }
                val to = when {
                    prev != null && next != null -> if (prev.second - prev.first >= next.second - next.first) prev.third else next.third
                    prev != null -> prev.third
                    next != null -> next.third
                    else -> 0
                }
                for (k in s..e) lv[k] = to
                changed = true
                break
            }
        }
    }

    private fun spans(lv: List<Int>): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        var i = 0
        while (i < lv.size) {
            var j = i
            while (j + 1 < lv.size && lv[j + 1] == lv[i]) j++
            out += Triple(i, j, lv[i])
            i = j + 1
        }
        return out
    }

    /** Pixel position of [p] at zoom [z] in tiles of [size] pixels, as (tile x, tile y, px, py). */
    fun pixelOf(p: LatLng, z: Int, size: Int): IntArray {
        val n = (1 shl z).toDouble()
        val fx = (p.lng + 180.0) / 360.0 * n
        val r = Math.toRadians(p.lat)
        val fy = (1.0 - kotlin.math.ln(kotlin.math.tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0 * n
        val tx = kotlin.math.floor(fx).toInt(); val ty = kotlin.math.floor(fy).toInt()
        return intArrayOf(tx, ty, ((fx - tx) * size).toInt().coerceIn(0, size - 1), ((fy - ty) * size).toInt().coerceIn(0, size - 1))
    }

}
