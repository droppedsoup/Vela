package app.vela.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import app.vela.core.model.LatLng
import app.vela.core.nav.PoliceAlerts
import app.vela.core.nav.PoliceReport
import java.io.File

/**
 * The police sightings the driver reported (SPEC 4.9, Police reports), kept in
 * `files/police_reports.tsv` on this phone and nowhere else, and the two settings that go with
 * them. Started in `VelaApp`. [reports] holds the live ones only: a report older than
 * [lifetimeMin] drops out at the next read, and [lifetimeMin] 0 keeps reports until cleared.
 */
object PoliceReports {
    /** The live reports, newest last. Replaced, never mutated, so a change is an identity change. */
    val reports = mutableStateOf<List<PoliceReport>>(emptyList())

    /** Announce a report ahead (card, voice and car). On by default. */
    val alerts = mutableStateOf(true)

    /** Minutes a report stays live; 0 keeps it until cleared. */
    val lifetimeMin = mutableIntStateOf(DEFAULT_LIFETIME_MIN)

    /** The choices Settings offers for [lifetimeMin]. */
    val LIFETIME_CHOICES = listOf(30, 60, 240, 1440, 0)

    @Volatile private var file: File? = null
    private val lock = Any()
    // One writer thread, so saves land in the order they were made.
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vela-police-io") }

    fun init(context: Context) {
        val p = prefs(context)
        alerts.value = p.getBoolean(KEY_ALERTS, true)
        lifetimeMin.intValue = p.getInt(KEY_LIFETIME, DEFAULT_LIFETIME_MIN)
        val f = File(context.filesDir, FILE)
        file = f
        // A small file, read off the main thread so a launch never waits on storage.
        io.execute {
            val loaded = runCatching { if (f.exists()) PoliceAlerts.parse(f.readText()) else emptyList() }.getOrDefault(emptyList())
            synchronized(lock) { reports.value = PoliceAlerts.live(loaded, System.currentTimeMillis(), lifetimeMs()) }
        }
    }

    /** The live reports now, dropping any that have aged out since the last change. */
    fun live(): List<PoliceReport> {
        val cur = reports.value
        val now = PoliceAlerts.live(cur, System.currentTimeMillis(), lifetimeMs())
        // The same list object while nothing aged out: callers key their work on its identity.
        if (now.size == cur.size) return cur
        synchronized(lock) { reports.value = now; save(now) }
        return now
    }

    /** Report police at [loc], traveling [headingDeg] (null when unknown). */
    fun add(loc: LatLng, headingDeg: Double?) {
        val now = System.currentTimeMillis()
        val r = PoliceReport(java.lang.Long.toString(now, 36), loc, headingDeg?.let { ((it % 360.0) + 360.0) % 360.0 }, now)
        synchronized(lock) {
            val next = PoliceAlerts.add(PoliceAlerts.live(reports.value, now, lifetimeMs()), r)
            reports.value = next
            save(next)
        }
    }

    fun clear() {
        synchronized(lock) { reports.value = emptyList(); save(emptyList()) }
    }

    fun setAlerts(context: Context, on: Boolean) {
        alerts.value = on
        prefs(context).edit().putBoolean(KEY_ALERTS, on).apply()
    }

    fun setLifetime(context: Context, minutes: Int) {
        lifetimeMin.intValue = minutes
        prefs(context).edit().putInt(KEY_LIFETIME, minutes).apply()
        live()
    }

    private fun lifetimeMs(): Long = lifetimeMin.intValue * 60_000L

    /** Written beside the file and moved over it, so a crash mid-write keeps the old list. */
    private fun save(list: List<PoliceReport>) {
        val f = file ?: return
        val text = PoliceAlerts.format(list)
        io.execute {
            runCatching {
                val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
            }.onFailure { android.util.Log.w("VelaPolice", "could not save reports: ${it.message}") }
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
    private const val FILE = "police_reports.tsv"
    private const val KEY_ALERTS = "police_alerts"
    private const val KEY_LIFETIME = "police_lifetime_min"
    private const val DEFAULT_LIFETIME_MIN = 60
}
