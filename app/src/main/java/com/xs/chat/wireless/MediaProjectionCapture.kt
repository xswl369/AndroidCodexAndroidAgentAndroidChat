package com.xs.chat.wireless

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import com.wirelessdebug.WdbContext
import com.wirelessdebug.service.PairCaptureService

/**
 * MediaProjection 截屏：无线调试配对码 OCR 用，
 * 不依赖 Shizuku/root，直接复用 PairCaptureService 持有的投屏会话。
 */
object MediaProjectionCapture {
    fun capture(): Bitmap? {
        val projection: MediaProjection = PairCaptureService.getProjection() ?: return null
        val ctx = WdbContext.get() ?: return null
        val metrics = ctx.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val vd = projection.createVirtualDisplay(
            "xs-chat-capture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        ) ?: run {
            reader.close()
            return null
        }
        return try {
            var image = reader.acquireLatestImage()
            var tries = 0
            while (image == null && tries < 20) {
                Thread.sleep(80)
                image = reader.acquireLatestImage()
                tries++
            }
            image?.use { img ->
                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val full = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                full.copyPixelsFromBuffer(buffer)
                if (rowPadding > 0) Bitmap.createBitmap(full, 0, 0, width, height) else full
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { vd.release() }
            runCatching { reader.close() }
        }
    }
}
