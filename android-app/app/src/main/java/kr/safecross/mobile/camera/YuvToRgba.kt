package kr.safecross.mobile.camera

import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YUV_420_888 카메라 영상 → RGBA_8888 (ARCore CPU 이미지용, ADR-0041).
 * 결과는 센서 방향 그대로이며, 세로 화면 회전은 [ImageBufferRotator]로 한다.
 */
object YuvToRgba {

    fun convert(image: Image): ByteBuffer {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val out = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        val row = ByteArray(width * 4)
        for (y in 0 until height) {
            val yRow = y * yRowStride
            val uvRow = (y shr 1) * uvRowStride
            var o = 0
            for (x in 0 until width) {
                val yy = (yBuf.get(yRow + x).toInt() and 0xFF) - 16
                val uvIndex = uvRow + (x shr 1) * uvPixelStride
                val u = (uBuf.get(uvIndex).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvIndex).toInt() and 0xFF) - 128
                // BT.601 정수 근사
                val c = if (yy < 0) 0 else yy * 1192
                val r = (c + 1634 * v) shr 10
                val g = (c - 833 * v - 400 * u) shr 10
                val b = (c + 2066 * u) shr 10
                row[o] = r.coerceIn(0, 255).toByte()
                row[o + 1] = g.coerceIn(0, 255).toByte()
                row[o + 2] = b.coerceIn(0, 255).toByte()
                row[o + 3] = 0xFF.toByte()
                o += 4
            }
            out.put(row)
        }
        out.rewind()
        return out
    }
}
