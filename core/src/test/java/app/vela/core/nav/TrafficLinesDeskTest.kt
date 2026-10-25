package app.vela.core.nav

import app.vela.core.data.naming.RoadNameTiles
import app.vela.core.model.LatLng
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Desk check for [TrafficLines] on real tiles, off by default:
 * `-DvelaTrafficDesk=<folder>` with `roads_14.pbf` (one z14 OpenMapTiles tile, named in
 * `roads_14.txt` as `x y`) and `grids/16_<x>_<y>.bin` (256 x 256 traffic levels per z16 tile, as
 * the app keeps them). Writes `lines.geojson` beside them for drawing.
 */
class TrafficLinesDeskTest {
    @Test fun writeLines() {
        val dir = System.getProperty("velaTrafficDesk")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val (tx, ty) = File(dir, "roads_14.txt").readText().trim().split(" ").map { it.toInt() }
        val grids = File(dir, "grids").listFiles()!!.associate { f ->
            val (z, x, y) = f.nameWithoutExtension.split("_").map { it.toInt() }
            Triple(z, x, y) to f.readBytes()
        }
        val sample: (LatLng) -> Int = { p ->
            val px = TrafficLines.pixelOf(p, 16, 256)
            grids[Triple(16, px[0], px[1])]?.let { it[px[3] * 256 + px[2]].toInt() } ?: 0
        }
        val drawn = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor")
        val roads = RoadNameTiles.decodeRoads(File(dir, "roads_14.pbf").readBytes(), 14, tx, ty).filter { it.cls in drawn }
        val t0 = System.nanoTime()
        val runs = TrafficLines.runs(roads.map { it.points }, sample)
        val ms = (System.nanoTime() - t0) / 1e6
        val features = runs.joinToString(",") { r ->
            """{"type":"Feature","properties":{"l":${r.level}},"geometry":{"type":"LineString","coordinates":[${r.points.joinToString(",") { "[${it.lng},${it.lat}]" }}]}}"""
        }
        File(dir, "lines.geojson").writeText("""{"type":"FeatureCollection","features":[$features]}""")
        println("DESK roads=${roads.size} runs=${runs.size} levels=${runs.groupingBy { it.level }.eachCount()} in ${"%.1f".format(ms)} ms")
    }
}
