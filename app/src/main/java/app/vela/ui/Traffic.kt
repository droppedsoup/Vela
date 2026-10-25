package app.vela.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Whether Google's live-traffic overlay is drawn on the map. A process-wide
 * reactive holder (like [Units] / [app.vela.ui.theme.AppTheme]) so a toggle
 * anywhere flips it everywhere; persisted across launches. On by default: with it
 * off the map shows no live traffic at all until a route is planned.
 */
object Traffic {
    val on = mutableStateOf(true)

    /** The overlay during a drive too (pref `traffic_in_drive`, off). Off, the route line
     *  carries the traffic and the overlay hides until the drive ends (SPEC 6, Rasters). */
    val inDrive = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, true)
        inDrive.value = prefs(context).getBoolean(KEY_DRIVE, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    fun setInDrive(context: Context, value: Boolean) {
        inDrive.value = value
        prefs(context).edit().putBoolean(KEY_DRIVE, value).apply()
    }

    /** Whether the overlay draws now, given whether a drive is on screen. */
    fun shown(navMode: Boolean): Boolean = on.value && (!navMode || inDrive.value) && !GoogleFree.on.value

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val KEY = "traffic_on"
    private const val KEY_DRIVE = "traffic_in_drive"
}
