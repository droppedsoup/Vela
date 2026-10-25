package app.vela.ui.nav

import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Scrubbing the road ahead (SPEC 4.7, Route bar scrub): during a drive the driver drags the
 * arrow up the route bar and the car drives up the route to that point, the follow camera with
 * it, and back down as the arrow comes down. While scrubbed the bar stands for the whole trip:
 * the bottom is the car, the top the destination, and its label reads [remainingM]. The bar sets
 * [fraction]; `VelaMapView` draws the car that share of the rest of the route ahead of its place;
 * `NavController` keeps [traveledM] and [remainingM] current and ends
 * the scrub [RESET_MS] after the finger lets go; the compass and Re-center end it too, and the
 * car then drives back to where it is.
 */
object RouteScrub {
    const val RESET_MS = 7_000L

    /** How far up the bar the arrow is, 0 (the car) to 1 (the top of the bar); null when idle. */
    val fraction = mutableStateOf<Float?>(null)

    /** When the finger last let go (elapsed ms); 0 while it is down or nothing is scrubbed. */
    val releasedAt = mutableLongStateOf(0L)

    /** The drive's progress and the road left to the destination, kept by NavController. */
    @Volatile var traveledM: Double = 0.0
    val remainingM = androidx.compose.runtime.mutableDoubleStateOf(0.0)

    val active: Boolean get() = fraction.value != null

    fun move(f: Float) {
        fraction.value = f.coerceIn(0f, 1f)
        releasedAt.longValue = 0L
    }

    fun release() {
        if (active) releasedAt.longValue = SystemClock.elapsedRealtime()
    }

    fun end() {
        if (fraction.value != null) fraction.value = null
        releasedAt.longValue = 0L
    }
}
