package app.vela.ui.map

import app.vela.ui.map.MapcraftTextures.Block
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Mapcraft block textures ([MapcraftTextures]). */
class MapcraftTexturesTest {
    private fun lum(c: Int) = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)

    @Test fun everyBlockIsAFullOpaqueGrid() {
        for (b in Block.entries) for (night in listOf(false, true)) {
            val t = MapcraftTextures.texels(b, night)
            assertEquals(b.key, MapcraftTextures.N * MapcraftTextures.N, t.size)
            assertTrue(b.key, t.all { (it ushr 24) == 0xFF })
        }
    }

    @Test fun texturesAreTheSameOnEveryCall() {
        for (b in Block.entries) assertArrayEquals(b.key, MapcraftTextures.texels(b, false), MapcraftTextures.texels(b, false))
    }

    @Test fun nightIsDarker() {
        for (b in Block.entries) {
            val day = MapcraftTextures.texels(b, false).map(::lum).average()
            val night = MapcraftTextures.texels(b, true).map(::lum).average()
            assertTrue(b.key, night < day * 0.7)
        }
    }

    @Test fun blocksAreTextured() {
        // A flat block would read as a plain color, not a block.
        for (b in Block.entries) assertTrue(b.key, MapcraftTextures.texels(b, false).toSet().size >= 3)
    }

    @Test fun bevelLightsTheTopEdgeAndShadesTheBottom() {
        val t = MapcraftTextures.texels(Block.GRASS, false)
        val n = MapcraftTextures.N
        val top = (1 until n - 1).map { lum(t[it]) }.average()
        val bottom = (1 until n - 1).map { lum(t[(n - 1) * n + it]) }.average()
        assertTrue(top > bottom + 20)
    }

    @Test fun averageIsAColorString() {
        for (b in Block.entries) assertTrue(MapcraftTextures.average(b, false).matches(Regex("#[0-9a-f]{6}")))
        assertNotEquals(MapcraftTextures.average(Block.WATER, false), MapcraftTextures.average(Block.GRASS, false))
    }

    /** Desk check, opt-in: `-DvelaMapcraftOut=<folder>` writes `sheet.png` (every block, day row
     *  above night row) and each block as `<key>.png` and `<key>-night.png`, a texel 6 pixels
     *  square, for looking at and for a GL JS render. */
    @Test fun contactSheet() {
        val dir = java.io.File(System.getProperty("velaMapcraftOut") ?: return).apply { mkdirs() }
        val s = 6; val n = MapcraftTextures.N; val gap = 4; val w = n * s
        fun draw(img: java.awt.image.BufferedImage, t: IntArray, ox: Int, oy: Int) {
            for (y in 0 until w) for (x in 0 until w) img.setRGB(ox + x, oy + y, t[(y / s) * n + x / s] and 0xFFFFFF)
        }
        val blocks = Block.entries
        val sheet = java.awt.image.BufferedImage(blocks.size * (w + gap), 2 * (w + gap), java.awt.image.BufferedImage.TYPE_INT_RGB)
        blocks.forEachIndexed { i, b ->
            listOf(false, true).forEachIndexed { row, night ->
                val t = MapcraftTextures.texels(b, night)
                draw(sheet, t, i * (w + gap), row * (w + gap))
                val one = java.awt.image.BufferedImage(w, w, java.awt.image.BufferedImage.TYPE_INT_RGB)
                draw(one, t, 0, 0)
                javax.imageio.ImageIO.write(one, "png", java.io.File(dir, b.key + (if (night) "-night" else "") + ".png"))
            }
        }
        javax.imageio.ImageIO.write(sheet, "png", java.io.File(dir, "sheet.png"))
    }
}
