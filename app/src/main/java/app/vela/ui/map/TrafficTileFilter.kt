package app.vela.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * The traffic overlay shows slow traffic only (SPEC 6, Rasters). Google's traffic tile paints
 * every road with flowing traffic green, which over the drive view is a wide green band beside
 * every road and says nothing a driver can act on. This hook in MapLibre's HTTP client takes the
 * green out of each traffic tile as it arrives: a pixel whose green leads its red by more than
 * [GREEN_LEAD] and is at least its blue goes transparent. Yellow (`#ffcf43`), red and dark red
 * (`#702323`) all have more red than green and stay. A tile that does not decode passes through.
 */
object TrafficTileFilter : Interceptor {
    private const val GREEN_LEAD = 30

    fun isTrafficTile(url: String): Boolean = url.startsWith("https://www.google.com/maps/vt/") && "!2straffic" in url

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val resp = chain.proceed(req)
        if (!resp.isSuccessful || !isTrafficTile(req.url.toString())) return resp
        val bytes = runCatching { resp.body?.bytes() }.getOrNull() ?: return resp
        val out = runCatching { dropGreen(bytes) }.getOrNull() ?: bytes
        return resp.newBuilder().body(out.toResponseBody("image/png".toMediaType())).build()
    }

    /** The tile with its free-flow green made transparent, PNG encoded; null if it did not decode. */
    private fun dropGreen(png: ByteArray): ByteArray? {
        val src = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inMutable = true }) ?: return null
        val bmp = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
        val w = bmp.width
        val row = IntArray(w)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var changed = false
            for (i in 0 until w) {
                val c = row[i]
                if (c ushr 24 == 0) continue
                if (isFreeFlow((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff)) { row[i] = 0; changed = true }
            }
            if (changed) bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        val buf = java.io.ByteArrayOutputStream(png.size * 2)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, buf)
        bmp.recycle()
        return buf.toByteArray()
    }

    /** Google's free-flow green and its anti-aliased edges (`#16e098`, `#049c64`, `#0bbf80` ...). */
    fun isFreeFlow(r: Int, g: Int, b: Int): Boolean = g - r > GREEN_LEAD && g >= b
}
