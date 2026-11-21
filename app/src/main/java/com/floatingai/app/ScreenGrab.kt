
package com.floatingai.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.max

object ScreenGrab {

    class Session private constructor(
        private val projection: MediaProjection,
        private val reader: ImageReader,
        private val display: VirtualDisplay,
        private val thread: HandlerThread,
        private val handler: Handler
    ) {
        @Volatile private var stopped = false
        private var waiting: CompletableDeferred<Bitmap?>? = null

        init {
            reader.setOnImageAvailableListener({ source ->
                val image = try {
                    source.acquireLatestImage()
                } catch (_: Exception) {
                    null
                } ?: return@setOnImageAvailableListener

                try {
                    val target = synchronized(this) { waiting }
                    if (target != null && !target.isCompleted) {
                        target.complete(toBitmap(image))
                    }
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }, handler)
        }

        suspend fun frame(): Bitmap? {
            if (stopped) return null

            val deferred = CompletableDeferred<Bitmap?>()
            synchronized(this) {
                if (stopped) return null
                waiting?.cancel()
                waiting = deferred
            }

            val result = withTimeoutOrNull(2500) {
                deferred.await()
            }

            synchronized(this) {
                if (waiting === deferred) waiting = null
            }
            return result
        }

        fun close() {
            if (stopped) return
            stopped = true

            synchronized(this) {
                waiting?.complete(null)
                waiting = null
            }

            try { display.release() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { projection.stop() } catch (_: Exception) {}
            thread.quitSafely()
        }

        companion object {
            fun start(
                ctx: Context,
                p: Point,
                code: Int,
                data: Intent
            ): Session? {
                val manager = ctx.getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

                val projection = manager.getMediaProjection(code, data)
                    ?: return null

                val thread = HandlerThread("wizz-live-capture")
                thread.start()
                val handler = Handler(thread.looper)

                try {
                    val dpi = ctx.resources.displayMetrics.densityDpi
                    val reader = ImageReader.newInstance(
                        p.x, p.y, PixelFormat.RGBA_8888, 3
                    )

                    val session = arrayOfNulls<Session>(1)

                    projection.registerCallback(
                        object : MediaProjection.Callback() {
                            override fun onStop() {
                                session[0]?.close()
                            }
                        }, handler
                    )

                    val display = projection.createVirtualDisplay(
                        "WizzLiveScreen",
                        p.x,
                        p.y,
                        dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.surface,
                        null,
                        handler
                    ) ?: run {
                        reader.close()
                        projection.stop()
                        thread.quitSafely()
                        return null
                    }

                    val result = Session(
                        projection, reader, display, thread, handler
                    )
                    session[0] = result
                    return result
                } catch (e: Exception) {
                    try { projection.stop() } catch (_: Exception) {}
                    thread.quitSafely()
                    return null
                }
            }
        }
    }

    suspend fun grab(
        ctx: Context,
        p: Point,
        code: Int,
        data: Intent,
        ui: Handler
    ): Bitmap? {
        val session = Session.start(ctx, p, code, data) ?: return null
        return try {
            session.frame()
        } finally {
            session.close()
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val padding = rowStride - pixelStride * img.width

        val full = Bitmap.createBitmap(
            img.width + padding / pixelStride,
            img.height,
            Bitmap.Config.ARGB_8888
        )
        full.copyPixelsFromBuffer(plane.buffer)

        if (padding == 0) return full

        val cropped = Bitmap.createBitmap(
            full, 0, 0, img.width, img.height
        )
        full.recycle()
        return cropped
    }

    fun scaled(b: Bitmap, maxSide: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxSide) return b

        val ratio = maxSide.toFloat() / longest
        val result = Bitmap.createScaledBitmap(
            b,
            (b.width * ratio).toInt(),
            (b.height * ratio).toInt(),
            true
        )
        b.recycle()
        return result
    }

    fun jpeg(b: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 75, output)
        return output.toByteArray()
    }
}
