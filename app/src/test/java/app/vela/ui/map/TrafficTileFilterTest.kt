package app.vela.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of Google's traffic colors the overlay keeps ([TrafficTileFilter]). The colors are the
 *  most common ones in z14 and z15 tiles over San Francisco and Davis. */
class TrafficTileFilterTest {
    private fun rgb(hex: String) = Triple(hex.substring(1, 3).toInt(16), hex.substring(3, 5).toInt(16), hex.substring(5, 7).toInt(16))
    private fun freeFlow(hex: String) = rgb(hex).let { (r, g, b) -> TrafficTileFilter.isFreeFlow(r, g, b) }

    @Test fun freeFlowGreensAndTheirEdgesGo() {
        listOf("#16e098", "#049c64", "#0bbf80", "#08ac70", "#12cf8c", "#12d890", "#0fc784", "#0bb778", "#04a068").forEach {
            assertTrue(it, freeFlow(it))
        }
    }

    @Test fun slowAndStoppedTrafficStays() {
        listOf("#ffcf43", "#f3bf23", "#fbc733", "#f24e42", "#a82727", "#702323", "#ffffff", "#808080").forEach {
            assertFalse(it, freeFlow(it))
        }
    }

    @Test fun onlyGoogleTrafficTilesAreTouched() {
        assertTrue(TrafficTileFilter.isTrafficTile("https://www.google.com/maps/vt/pb=!1m4!1m3!1i15!2i1!3i2!2m9!1e2!2straffic!3i999999"))
        assertFalse(TrafficTileFilter.isTrafficTile("https://www.google.com/maps/vt/pb=!1m4!1m3!1i15!2i1!3i2!2m3!1e0!2sm"))
        assertFalse(TrafficTileFilter.isTrafficTile("https://tiles.openfreemap.org/planet/1/15/1/2.pbf"))
    }
}
