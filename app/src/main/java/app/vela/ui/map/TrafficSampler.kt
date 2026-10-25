package app.vela.ui.map

import app.vela.core.model.LatLng
import app.vela.core.nav.TrafficLines

/**
 * The traffic levels of the traffic tiles the map has loaded (SPEC 6, Rasters), for
 * [TrafficLines] to read along the roads. [TrafficTileFilter] fills it as each tile arrives: one
 * [GRID] x [GRID] grid of levels per tile, the newest [MAX_TILES] kept (64 KB each). [level] reads
 * the deepest tile that holds a point, z16 down to z12.
 */
object TrafficSampler {
    const val GRID = 256
    private const val MAX_TILES = 64
    private const val TOP_Z = 16
    private const val LOW_Z = 12
    private val TILE = Regex("""!1m3!1i(\d+)!2i(\d+)!3i(\d+)""")

    private val tiles = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, ByteArray>) = size > MAX_TILES
    }

    /** Bumped on every tile kept: new traffic to read. */
    @Volatile var generation = 0
        private set

    @Volatile private var registered = false

    /** z, x, y of a traffic tile request, or null. */
    fun tileOf(url: String): IntArray? =
        TILE.find(url)?.groupValues?.let { g -> intArrayOf(g[1].toInt(), g[2].toInt(), g[3].toInt()) }

    fun put(z: Int, x: Int, y: Int, levels: ByteArray) {
        if (!registered) {
            registered = true
            app.vela.ui.MemoryPressure.register { level -> if (app.vela.ui.MemoryPressure.isSevere(level)) clear() }
        }
        synchronized(tiles) { tiles[key(z, x, y)] = levels }
        generation++
    }

    fun clear() = synchronized(tiles) { tiles.clear() }

    /** The traffic level at [p] in the deepest tile held, 0 when none holds it. */
    fun level(p: LatLng): Int {
        for (z in TOP_Z downTo LOW_Z) {
            val px = TrafficLines.pixelOf(p, z, GRID)
            val g = synchronized(tiles) { tiles[key(z, px[0], px[1])] } ?: continue
            return g[px[3] * GRID + px[2]].toInt()
        }
        return 0
    }

    private fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 56) or (x.toLong() shl 28) or y.toLong()
}
