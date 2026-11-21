package com.floatingai.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

object ScreenGrab {

    suspend fun grab(ctx: Context, p: Point, code: Int, data: Intent, ui: Handler): Bitmap? {
        val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mgr.getMediaProjection(code, data) ?: return null
        mp.registerCallback(object : MediaProjection.Callback() {}, ui)
        val dpi = ctx.resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(p.x, p.y, PixelFormat.RGBA_8888, 3)
        val ht = HandlerThread("fai-cap").apply { start() }
        val h = Handler(ht.looper)
        val result = CompletableDeferred<Bitmap?>()
        val fallback = AtomicReference<Bitmap?>(null)
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                try {
                    if (!result.isCompleted) {
                        val b = toBitmap(img)
                        if (isBlank(b)) {
                            fallback.getAndSet(b)?.recycle()
                        } else {
                            result.complete(b)
                        }
                    }
                } catch (e: Throwable) {
                } finally {
                    img.close()
                }
            }
        }, h)
        val vd = try {
            mp.createVirtualDisplay("fai", p.x, p.y, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, h)
        } catch (e: Exception) { null }
        var bmp: Bitmap? = null
        if (vd != null) {
            bmp = withTimeoutOrNull(5000) {
                var flip = false
                while (!result.isCompleted) {
                    delay(200)
                    flip = !flip
                    try { vd.resize(p.x, p.y, if (flip) dpi + 1 else dpi) } catch (_: Exception) { }
                }
                result.await()
            }
        }
        if (bmp == null) bmp = fallback.getAndSet(null) else fallback.getAndSet(null)?.recycle()
        try { vd?.release() } catch (_: Exception) { }
        try { reader.close() } catch (_: Exception) { }
        try { mp.stop() } catch (_: Exception) { }
        ht.quitSafely()
        return bmp
    }

    private fun isBlank(b: Bitmap): Boolean {
        val w = b.width
        val h = b.height
        var first = 0
        var set = false
        for (yi in 1..9) {
            for (xi in 1..9) {
                val px = b.getPixel(w * xi / 10, h * yi / 10)
                if (!set) { first = px; set = true } else if (px != first) return false
            }
        }
        return true
    }

    private fun toBitmap(img: Image): Bitmap {
        val pl = img.planes[0]
        val ps = pl.pixelStride
        val rs = pl.rowStride
        val pad = rs - ps * img.width
        val full = Bitmap.createBitmap(img.width + pad / ps, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(pl.buffer)
        if (pad == 0) return full
        val cut = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        full.recycle()
        return cut
    }

    fun scaled(b: Bitmap, maxSide: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= maxSide) return b
        val s = maxSide.toFloat() / m
        val r = Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
        b.recycle()
        return r
    }

    fun jpeg(b: Bitmap): ByteArray {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 80, o)
        return o.toByteArray()
    }
}
