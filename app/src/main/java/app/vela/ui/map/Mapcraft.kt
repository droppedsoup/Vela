package app.vela.ui.map

import android.content.res.Resources
import android.graphics.Bitmap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import app.vela.ui.map.MapcraftTextures.Block as B

/**
 * The block textures of the Mapcraft look (SPEC 6.2, Mapcraft). Each is a 16 by 16 grid of
 * texels drawn from a fixed seed, so every install draws the same blocks. All of them are made
 * here; no image comes from anywhere else. A texture that [Block.bevel]s has a light top and left
 * edge and a dark bottom and right edge, so a field of it reads as separate blocks.
 */
internal object MapcraftTextures {
    const val N = 16

    /** [lit] blocks are the road surfaces: at night they keep more of their color, as if under
     *  street lights, so the roads stand out from the dark grass. */
    enum class Block(val key: String, val bevel: Boolean, val lit: Boolean = false) {
        GRASS("grass", true), MEADOW("meadow", true), LAWN("lawn", true), LEAVES("leaves", true),
        WATER("water", false), SAND("sand", true), SNOW("snow", true), MUD("mud", true),
        STONE("stone", true), COBBLE("cobble", false, lit = true), STONE_BRICKS("stone-bricks", false, lit = true),
        BRICKS("bricks", false), PLANKS("planks", false), GRAVEL("gravel", true, lit = true), PATH("path", true, lit = true),
        QUARTZ("quartz", false), TERRACOTTA("terracotta", true), DIRT("dirt", true), GLASS("glass", false),
    }

    private const val SEED = 0x6d61_7063L

    /** The block's texels, row by row, as opaque ARGB. [night] darkens them toward a blue night. */
    fun texels(b: Block, night: Boolean): IntArray {
        val px = IntArray(N * N)
        val rnd = java.util.Random(SEED + b.ordinal * 7_919L)
        fun pick(c: IntArray) = c[rnd.nextInt(c.size)]
        fun noise(c: IntArray) { for (i in px.indices) px[i] = pick(c) }
        fun at(x: Int, y: Int, c: Int) { px[(y and 15) * N + (x and 15)] = c }
        when (b) {
            Block.GRASS -> noise(intArrayOf(0x5E9C3B, 0x67A841, 0x548F35, 0x6FB247, 0x5A9638))
            Block.MEADOW -> {
                noise(intArrayOf(0x6DB548, 0x76BE4F, 0x64AA42, 0x7CC456))
                for (c in intArrayOf(0xF1D33B, 0xD9473B, 0xF4F4F4)) at(2 + rnd.nextInt(12), 2 + rnd.nextInt(12), c)
            }
            Block.LAWN -> for (y in 0 until N) for (x in 0 until N) {
                at(x, y, if (x < N / 2) pick(intArrayOf(0x74BC4C, 0x6EB548)) else pick(intArrayOf(0x5FA03E, 0x5A9939)))
            }
            Block.LEAVES -> for (i in px.indices) {
                px[i] = if (rnd.nextInt(100) < 18) 0x1B4316 else pick(intArrayOf(0x2F6E24, 0x3A7E2B, 0x28601F, 0x44892F))
            }
            Block.WATER -> {
                noise(intArrayOf(0x3466CC, 0x3A6ED6, 0x2F5DBD))
                for (y in intArrayOf(2, 7, 12)) {
                    val x0 = rnd.nextInt(N)
                    for (k in 0 until 4 + rnd.nextInt(3)) at(x0 + k, y, 0x5C8EEE)
                }
            }
            Block.SAND -> noise(intArrayOf(0xDCCF91, 0xE4D89E, 0xD3C684, 0xCDBF7C))
            Block.SNOW -> noise(intArrayOf(0xF5F8FB, 0xEAF0F5, 0xFFFFFF, 0xE2E9F0))
            Block.MUD -> for (i in px.indices) {
                px[i] = if (rnd.nextInt(100) < 10) 0x3B6090 else pick(intArrayOf(0x4C5B2F, 0x3F4D28, 0x566735, 0x3B3B23))
            }
            Block.STONE -> noise(intArrayOf(0x8B8B8B, 0x828282, 0x959595, 0x878787))
            Block.COBBLE -> {
                val shades = IntArray(16) { pick(intArrayOf(0x9C9C9C, 0x8A8A8A, 0xA6A6A6, 0x7C7C7C)) }
                for (y in 0 until N) for (x in 0 until N) {
                    val off = if ((y / 4) % 2 == 1) 2 else 0
                    val cx = (x + off) and 15
                    val mortar = y % 4 == 3 || cx % 4 == 3
                    at(x, y, if (mortar) 0x5B5B5B else shade(shades[(y / 4) * 4 + cx / 4], rnd.nextInt(13) - 6))
                }
            }
            Block.STONE_BRICKS, Block.BRICKS -> {
                val stone = b == Block.STONE_BRICKS
                val mortar = if (stone) 0x5E5E5E else 0xB5AAA0
                val faces = if (stone) intArrayOf(0x8E8E8E, 0x949494, 0x888888) else intArrayOf(0x9C4B3B, 0xA9573F, 0x8F4535)
                val shades = IntArray(8) { pick(faces) }
                for (y in 0 until N) for (x in 0 until N) {
                    val off = if ((y / 4) % 2 == 1) 4 else 0
                    val cx = (x + off) and 15
                    val c = when {
                        y % 4 == 3 || cx % 8 == 7 -> mortar
                        y % 4 == 0 -> shade(shades[(y / 4) * 2 + cx / 8], 10)
                        else -> shade(shades[(y / 4) * 2 + cx / 8], rnd.nextInt(9) - 4)
                    }
                    at(x, y, c)
                }
            }
            Block.PLANKS -> for (board in 0 until 4) {
                val face = pick(intArrayOf(0xA3834F, 0xAE8C56, 0x9B7B49, 0xA88752))
                val seam = (board * 5 + 3) % N
                for (y in board * 4 until board * 4 + 4) for (x in 0 until N) {
                    at(x, y, when {
                        y % 4 == 3 -> 0x6F5535
                        x == seam -> 0x7A5E3A
                        else -> shade(face, rnd.nextInt(9) - 4)
                    })
                }
            }
            Block.GRAVEL -> noise(intArrayOf(0x8A8581, 0x6F6A66, 0xA39E99, 0x5F5A56, 0x7D7874))
            Block.PATH -> noise(intArrayOf(0x96773F, 0xA4864B, 0x8B6D3B, 0x9D7E45))
            Block.QUARTZ -> {
                noise(intArrayOf(0xECE8E1, 0xE5E0D7, 0xF2EFEA))
                for (k in 0 until N) { at(k, 0, 0xD5CFC3); at(k, 15, 0xD5CFC3); at(0, k, 0xD5CFC3); at(15, k, 0xD5CFC3) }
            }
            Block.GLASS -> for (y in 0 until N) for (x in 0 until N) {
                // A window: a light frame, pale blue panes with a diagonal glint.
                at(x, y, when {
                    x == 0 || y == 0 || x == N - 1 || y == N - 1 || x == N / 2 || y == N / 2 -> 0xD7DCE0
                    (x + y) % 8 in 3..4 -> 0xCFEAF5
                    else -> pick(intArrayOf(0x8CC4DC, 0x86BED7, 0x92CAE1))
                })
            }
            Block.TERRACOTTA -> noise(intArrayOf(0xA2593B, 0x9C5437, 0xA95F3F))
            Block.DIRT -> noise(intArrayOf(0x866043, 0x79553A, 0x936B4C, 0x6E4C33))
        }
        if (b.bevel) for (k in 0 until N) {
            px[k] = shade(px[k], 18); px[k * N] = shade(px[k * N], 18)
            px[(N - 1) * N + k] = shade(px[(N - 1) * N + k], -30); px[k * N + N - 1] = shade(px[k * N + N - 1], -30)
        }
        for (i in px.indices) px[i] = (if (night) night(px[i], b.lit) else px[i]) or (0xFF shl 24)
        return px
    }

    /** The block's mean color, `#rrggbb`: the flat color drawn where the texture is not. */
    fun average(b: Block, night: Boolean): String {
        val t = texels(b, night)
        var r = 0L; var g = 0L; var bl = 0L
        for (c in t) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF }
        return "#%02x%02x%02x".format(r / t.size, g / t.size, bl / t.size)
    }

    private fun shade(c: Int, d: Int): Int {
        fun ch(v: Int) = (v + d).coerceIn(0, 255)
        return (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
    }

    private fun night(c: Int, lit: Boolean): Int {
        val k = if (lit) 0.52 else 0.36
        val r = ((c shr 16) and 0xFF) * k + 10
        val g = ((c shr 8) and 0xFF) * (k + 0.02) + 14
        val b = (c and 0xFF) * (k + 0.10) + 30
        return (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }
}

/**
 * Settings > Appearance > Map colors > Mapcraft (SPEC 6.2, Mapcraft): the map drawn in blocks.
 * Runs after the modern palette on a fresh style (a palette or theme change reloads the style, so
 * nothing here has to be undone). Registers the [MapcraftTextures] as `vela-mc-*` images and sets
 * them as fill, line and extrusion patterns; where an image cannot be registered the same layers
 * take each block's flat average color.
 */
internal object Mapcraft {
    /** Screen size of one texel, dp. A block is 16 of them across. */
    private const val TEXEL_DP = 1.75f

    /** Building heights are rounded up to this many meters, so roofs come out flat and stepped. */
    const val STEP_M = 3.0

    fun image(b: MapcraftTextures.Block) = "vela-mc-" + b.key

    /** Planks for houses, bricks for mid-rise, stone bricks for taller blocks, glass for towers. */
    fun buildingPattern(): Expression = Expression.step(
        Expression.toNumber(Expression.coalesce(Expression.get("render_height"), Expression.literal(0f))),
        Expression.literal(image(MapcraftTextures.Block.PLANKS)),
        Expression.stop(9, Expression.literal(image(MapcraftTextures.Block.BRICKS))),
        Expression.stop(24, Expression.literal(image(MapcraftTextures.Block.STONE_BRICKS))),
        Expression.stop(60, Expression.literal(image(MapcraftTextures.Block.GLASS))),
    )

    private fun bitmap(b: MapcraftTextures.Block, night: Boolean, density: Float): Bitmap {
        val s = Math.round(density * TEXEL_DP).coerceAtLeast(2)
        val t = MapcraftTextures.texels(b, night)
        val w = MapcraftTextures.N * s
        val out = IntArray(w * w)
        for (y in 0 until w) for (x in 0 until w) out[y * w + x] = t[(y / s) * MapcraftTextures.N + x / s]
        return Bitmap.createBitmap(out, w, w, Bitmap.Config.ARGB_8888)
    }

    fun apply(style: StyleLayers, dark: Boolean) {
        val density = Resources.getSystem().displayMetrics.density
        val textured = runCatching {
            B.entries.forEach { style.addImage(image(it), bitmap(it, dark, density)) }
        }.isSuccess
        fun avg(b: MapcraftTextures.Block) = MapcraftTextures.average(b, dark)

        style.getLayer("background")?.let { l ->
            (l as? BackgroundLayer)?.setProperties(PropertyFactory.backgroundColor(avg(B.GRASS)))
            if (textured) (l as? BackgroundLayer)?.setProperties(PropertyFactory.backgroundPattern(image(B.GRASS)))
        }
        fun fill(id: String, b: MapcraftTextures.Block) {
            val l = style.getLayer(id) as? FillLayer ?: return
            l.setProperties(PropertyFactory.fillColor(avg(b)), PropertyFactory.fillOpacity(1f), PropertyFactory.fillAntialias(false))
            if (textured) l.setProperties(PropertyFactory.fillPattern(image(b)))
        }
        // The grass of the background shows through where people live.
        style.getLayer("landuse_residential")?.setProperties(PropertyFactory.visibility(Property.NONE))
        fill("park", B.MEADOW)
        fill("landcover_grass", B.MEADOW)
        fill("landcover_wood", B.LEAVES)
        fill("landcover_sand", B.SAND)
        fill("landcover_ice", B.SNOW)
        fill("vela-wetland", B.MUD)
        fill("water", B.WATER)
        fill("vela-pitch", B.LAWN)
        fill("landuse_pitch", B.LAWN)
        fill("landuse_track", B.TERRACOTTA)
        fill("landuse_cemetery", B.DIRT)
        fill("landuse_hospital", B.QUARTZ)
        fill("landuse_school", B.TERRACOTTA)
        fill("vela-commercial", B.STONE)
        fill("vela-plaza", B.STONE)
        fill("aeroway_fill", B.STONE)

        (style.getLayer("building") as? FillLayer)?.let { l ->
            l.setProperties(PropertyFactory.fillColor(avg(B.PLANKS)), PropertyFactory.fillAntialias(false))
            if (textured) l.setProperties(PropertyFactory.fillPattern(buildingPattern()))
        }
        (style.getLayer("building-3d") as? FillExtrusionLayer)?.let { l ->
            val h = Expression.toNumber(Expression.coalesce(Expression.get("render_height"), Expression.literal(0f)))
            val base = Expression.toNumber(Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0f)))
            fun blocks(v: Expression) = Expression.product(Expression.ceil(Expression.division(v, Expression.literal(STEP_M))), Expression.literal(STEP_M))
            l.setProperties(
                PropertyFactory.fillExtrusionColor(avg(B.BRICKS)),
                PropertyFactory.fillExtrusionVerticalGradient(false),
                PropertyFactory.fillExtrusionHeight(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(17f, Expression.product(blocks(h), Expression.literal(0.5f))),
                        Expression.stop(18f, blocks(h)),
                    ),
                ),
                PropertyFactory.fillExtrusionBase(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(17f, Expression.product(blocks(base), Expression.literal(0.5f))),
                        Expression.stop(18f, blocks(base)),
                    ),
                ),
            )
            if (textured) l.setProperties(PropertyFactory.fillExtrusionPattern(buildingPattern()))
        }

        fun line(ids: List<String>, b: MapcraftTextures.Block) = ids.forEach { id ->
            val l = style.getLayer(id) as? LineLayer ?: return@forEach
            l.setProperties(
                PropertyFactory.lineColor(avg(b)),
                PropertyFactory.lineCap(Property.LINE_CAP_SQUARE),
                PropertyFactory.lineJoin(Property.LINE_JOIN_MITER),
                PropertyFactory.lineBlur(0f),
            )
            if (textured) l.setProperties(PropertyFactory.linePattern(image(b)))
        }
        fun all(vararg base: String) = base.flatMap { listOf("road_$it", "bridge_$it", "tunnel_$it") }
        line(all("motorway", "motorway_link"), B.STONE_BRICKS)
        line(all("trunk_primary"), B.COBBLE)
        line(all("secondary_tertiary"), B.GRAVEL)
        line(listOf("road_minor", "tunnel_minor", "road_link", "tunnel_link", "road_service_track", "tunnel_service_track"), B.PATH)
        line(listOf("bridge_street", "bridge_link", "bridge_service_track"), B.PLANKS)
        line(listOf("waterway_river", "waterway_other"), B.WATER)
        // Every casing is a dark edge with square ends, so roads read as laid blocks.
        val edge = if (dark) "#05070a" else "#3d3a35"
        style.layers.filter { it.id.endsWith("_casing") }.forEach { l ->
            (l as? LineLayer)?.setProperties(
                PropertyFactory.lineColor(edge),
                PropertyFactory.lineCap(Property.LINE_CAP_SQUARE),
                PropertyFactory.lineJoin(Property.LINE_JOIN_MITER),
            )
        }
        listOf("road_major_rail", "bridge_major_rail", "tunnel_major_rail", "road_transit_rail", "bridge_transit_rail", "tunnel_transit_rail").forEach {
            style.getLayer(it)?.setProperties(PropertyFactory.lineColor(if (dark) "#4a4038" else "#6b5a45"))
        }
        // Names in white over a dark edge, the way a block game labels things.
        val labels = listOf(
            "highway-name-path", "highway-name-minor", "highway-name-major",
            "label_other", "label_village", "label_town", "label_state", "label_city", "label_city_capital",
            "label_country_1", "label_country_2", "label_country_3",
            "water_name_point_label", "water_name_line_label", "waterway_line_label", "airport",
        )
        labels.forEach { id ->
            (style.getLayer(id) as? SymbolLayer)?.setProperties(
                PropertyFactory.textColor("#ffffff"),
                PropertyFactory.textHaloColor(if (dark) "#000000" else "#2b2b2b"),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textHaloBlur(0f),
            )
        }
    }
}
