package com.xs.chat.wireless

import android.graphics.Bitmap
import com.wirelessdebug.service.PairCaptureService

/**
 * MediaProjection 截屏：无线调试配对码 OCR 用，
 * 不依赖 Shizuku/root，直接复用 PairCaptureService 持有的投屏会话。
 * Android 16 限制同一 projection 只能创建一次 VirtualDisplay，因此
 * VirtualDisplay/ImageReader 由 Service 一次性创建，这里只负责取帧。
 */
object MediaProjectionCapture {
    fun capture(): Bitmap? {
        val reader = PairCaptureService.getImageReader() ?: return null
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
                val width = img.width
                val height = img.height
                val rowPadding = rowStride - pixelStride * width
                val full = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                full.copyPixelsFromBuffer(buffer)
                if (rowPadding > 0) Bitmap.createBitmap(full, 0, 0, width, height) else full
            }
        } catch (t: Throwable) {
            null
        }
    }
}
