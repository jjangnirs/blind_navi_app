package kr.safecross.mobile.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CameraX ImageAnalysis에서 전달되는 센서 방향 버퍼를 화면 표시 기준 정립(Upright) 방향으로 회전 정규화하는 유틸리티.
 */
object ImageBufferRotator {

    /**
     * RGBA ByteBuffer를 지정된 각도(0, 90, 180, 270도)에 맞게 회전합니다.
     * 회전된 Direct ByteBuffer와 정규화된 가로/세로 크기를 반환합니다.
     */
    fun rotateOrCopyRgbaBuffer(
        src: ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        rotationDegrees: Int
    ): Triple<ByteBuffer, Int, Int> {
        val normRot = (rotationDegrees % 360 + 360) % 360
        if (normRot == 0) {
            val cloned = ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.nativeOrder())
            src.rewind()
            cloned.put(src)
            cloned.flip()
            src.rewind()
            return Triple(cloned, srcWidth, srcHeight)
        }

        val isSwapped = (normRot == 90 || normRot == 270)
        val dstWidth = if (isSwapped) srcHeight else srcWidth
        val dstHeight = if (isSwapped) srcWidth else srcHeight

        val pixelCount = srcWidth * srcHeight
        val dstBuffer = ByteBuffer.allocateDirect(pixelCount * 4).order(ByteOrder.nativeOrder())

        src.rewind()
        src.order(ByteOrder.nativeOrder())
        val srcInts = src.asIntBuffer()
        val dstInts = dstBuffer.asIntBuffer()

        if (srcInts.remaining() < pixelCount) {
            src.rewind()
            val cloned = ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.nativeOrder())
            cloned.put(src)
            cloned.flip()
            src.rewind()
            return Triple(cloned, srcWidth, srcHeight)
        }

        val srcArray = IntArray(pixelCount)
        srcInts.get(srcArray)
        val dstArray = IntArray(pixelCount)

        when (normRot) {
            90 -> {
                for (y in 0 until srcHeight) {
                    val rowOff = y * srcWidth
                    for (x in 0 until srcWidth) {
                        val dx = srcHeight - 1 - y
                        val dy = x
                        dstArray[dy * dstWidth + dx] = srcArray[rowOff + x]
                    }
                }
            }
            180 -> {
                for (i in 0 until pixelCount) {
                    dstArray[pixelCount - 1 - i] = srcArray[i]
                }
            }
            270 -> {
                for (y in 0 until srcHeight) {
                    val rowOff = y * srcWidth
                    for (x in 0 until srcWidth) {
                        val dx = y
                        val dy = srcWidth - 1 - x
                        dstArray[dy * dstWidth + dx] = srcArray[rowOff + x]
                    }
                }
            }
        }

        dstInts.put(dstArray)
        dstBuffer.rewind()
        src.rewind()
        return Triple(dstBuffer, dstWidth, dstHeight)
    }
}
