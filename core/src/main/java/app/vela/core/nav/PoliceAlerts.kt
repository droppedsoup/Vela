package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo

/** One police sighting the driver reported: where, the heading of travel when it was reported
 *  (null when the phone had none), and when, in wall-clock milliseconds. */
data class PoliceReport(val id: String, val loc: LatLng, val headingDeg: Double?, val atMs: Long)

/**
 * The rules for police reports (SPEC 4.9, Police reports): which reports are still live, which lie
 * on a route, when one is announced, and the file format they are kept in. Reports are made on
 * the phone and stay on it; there is no shared feed.
 */
object PoliceAlerts {

    /** A report counts on a route within this of the line. Wider than a camera's 45 m: a patrol
     *  car waits on a shoulder, a ramp or a lot beside the road. */
    const val ON_ROUTE_M = 60.0

    /** A report's heading must lie within this of the road's axis, as an undirected line, so a
     *  report made on a cross street is not announced on the road it crosses. */
    const val AXIS_DEG = 50.0

    /** A new report within this of a live one replaces it: pressing Report twice, or again on the
     *  next drive past the same spot, keeps one report with the newer time. */
    const val MERGE_M = 150.0

    /** Seconds of warning aimed for, and the floor and cap on that distance. Longer than a speed
     *  camera's 12 s: a driver wants time to check the speed before a patrol car sees it. */
    const val LEAD_SECONDS = 20.0
    const val MIN_LEAD_M = 300.0
    const val MAX_LEAD_M = 1_200.0

    /** Reports within this of each other along the route are one announcement. */
    const val JOIN_M = 200.0

    fun leadDistanceM(speedMps: Double): Double =
        (speedMps * LEAD_SECONDS).coerceIn(MIN_LEAD_M, MAX_LEAD_M)

    /** The reports still live at [nowMs] for a lifetime of [lifetimeMs] (0 or less keeps every
     *  report). A report dated in the future (a clock set back) counts as made now. */
    fun live(reports: List<PoliceReport>, nowMs: Long, lifetimeMs: Long): List<PoliceReport> =
        if (lifetimeMs <= 0L) reports else reports.filter { nowMs - minOf(it.atMs, nowMs) < lifetimeMs }

    /** [reports] with a new one added at [loc]: any live report within [MERGE_M] whose heading
     *  agrees (or either has none) gives way to it. */
    fun add(reports: List<PoliceReport>, report: PoliceReport): List<PoliceReport> =
        reports.filterNot { old ->
            old.loc.distanceTo(report.loc) <= MERGE_M &&
                (old.headingDeg == null || report.headingDeg == null || CameraFacing.axisDiff(old.headingDeg, report.headingDeg) <= AXIS_DEG)
        } + report

    /** The reports on [poly] as (id, meters along it), ascending, with reports within [JOIN_M]
     *  of the one before merged into it: one announcement per spot, keyed by its first report. */
    fun alongRoute(poly: List<LatLng>, cum: DoubleArray, reports: List<PoliceReport>): List<Pair<String, Double>> {
        if (poly.size < 2) return emptyList()
        val on = reports.mapNotNull { r ->
            val m = RouteProjection.alongMeters(poly, cum, r.loc, ON_ROUTE_M) ?: return@mapNotNull null
            val h = r.headingDeg
            if (h != null && CameraFacing.axisDiff(h, RouteProjection.bearingAt(poly, cum, m)) > AXIS_DEG) null else r.id to m
        }.sortedBy { it.second }
        val out = ArrayList<Pair<String, Double>>()
        var last = Double.NEGATIVE_INFINITY
        for (p in on) {
            if (out.isEmpty() || p.second - last > JOIN_M) out.add(p)
            last = p.second
        }
        return out
    }

    /** The key in [ahead] (id, meters along the same line, ascending) to announce at [traveledM],
     *  or null: the nearest one not yet in [announced] that is ahead and inside the lead window.
     *  Never one already passed, and nothing while crawling or parked. */
    fun due(ahead: List<Pair<String, Double>>, traveledM: Double, speedMps: Double, announced: Set<String>): String? {
        if (speedMps < CameraAlerts.MOVING_FLOOR_MPS) return null
        val lead = leadDistanceM(speedMps)
        var best: String? = null
        var bestAhead = Double.MAX_VALUE
        for ((id, m) in ahead) {
            if (id in announced) continue
            val d = m - traveledM
            if (d <= 0.0 || d > lead) continue
            if (d < bestAhead) { bestAhead = d; best = id }
        }
        return best
    }

    /** One report per line: `id`, lat, lng, heading (empty when none), time, tab-separated. */
    fun format(reports: List<PoliceReport>): String = buildString {
        for (r in reports) {
            append(r.id).append('\t')
            append(String.format(java.util.Locale.US, "%.6f\t%.6f\t", r.loc.lat, r.loc.lng))
            r.headingDeg?.let { append(String.format(java.util.Locale.US, "%.0f", it)) }
            append('\t').append(r.atMs).append('\n')
        }
    }

    /** The reports in [text]; a line that does not read is skipped. */
    fun parse(text: String): List<PoliceReport> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 5) return@mapNotNull null
        val lat = f[1].toDoubleOrNull() ?: return@mapNotNull null
        val lng = f[2].toDoubleOrNull() ?: return@mapNotNull null
        val at = f[4].trim().toLongOrNull() ?: return@mapNotNull null
        if (f[0].isBlank() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
        PoliceReport(f[0], LatLng(lat, lng), f[3].toDoubleOrNull(), at)
    }.toList()
}
