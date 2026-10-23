package app.vela.core.location

/**
 * Whether a GPS-reported course is trustworthy enough to steer navigation.
 *
 * Bearing is a direction of travel, not the phone's compass orientation. At very low speed it
 * becomes unstable. Android 8+ also reports bearing accuracy independently of horizontal GPS
 * accuracy; an uncertain course must not send the map arrow sideways or tell routing that
 * the driver has taken the wrong road.
 */
object CourseQuality {
    const val MAX_BEARING_ACCURACY_DEG = 45f
    const val WIDE_FIX_M = 30f
    const val WIDE_FIX_MIN_SPEED_MPS = 3f

    fun measured(
        bearingDeg: Float?,
        speedMps: Float?,
        horizontalAccuracyM: Float?,
        bearingAccuracyDeg: Float?,
        movingFloorMps: Float = 0.5f,
    ): Float? {
        if (bearingDeg == null || !bearingDeg.isFinite()) return null
        if (speedMps == null || !speedMps.isFinite() || speedMps <= movingFloorMps) return null
        if (bearingAccuracyDeg != null &&
            (!bearingAccuracyDeg.isFinite() || bearingAccuracyDeg > MAX_BEARING_ACCURACY_DEG)
        ) return null
        if (horizontalAccuracyM != null &&
            (!horizontalAccuracyM.isFinite() ||
                (horizontalAccuracyM > WIDE_FIX_M && speedMps < WIDE_FIX_MIN_SPEED_MPS))
        ) return null
        return ((bearingDeg % 360f) + 360f) % 360f
    }
}
