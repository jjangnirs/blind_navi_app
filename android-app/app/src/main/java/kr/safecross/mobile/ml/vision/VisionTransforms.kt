package kr.safecross.mobile.ml.vision

import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.PointF
import kotlin.math.max
import kotlin.math.min

/**
 * 원본 프레임(예: 640x480)에서 모델 정사각 입력(예: 320x320)으로의 종횡비 보존 Letterbox 변환 정보
 */
data class LetterboxTransform(
    val srcWidth: Int,
    val srcHeight: Int,
    val dstWidth: Int,
    val dstHeight: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float
)

object VisionTransforms {

    /**
     * 종횡비를 보존하며 정사각 텐서에 맞추기 위한 Letterbox 변환 계수를 계산합니다.
     */
    fun computeLetterboxTransform(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int = 320,
        dstHeight: Int = 320
    ): LetterboxTransform {
        require(srcWidth > 0 && srcHeight > 0 && dstWidth > 0 && dstHeight > 0) {
            "Dimensions must be positive"
        }
        val scale = min(dstWidth.toFloat() / srcWidth, dstHeight.toFloat() / srcHeight)
        val scaledW = srcWidth * scale
        val scaledH = srcHeight * scale
        val padX = (dstWidth - scaledW) / 2f
        val padY = (dstHeight - scaledH) / 2f

        return LetterboxTransform(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = scale,
            padX = padX,
            padY = padY
        )
    }

    /**
     * 모델 출력 정규화 좌표(0.0~1.0, 패딩 포함)를 원본 프레임 정규화 좌표(0.0~1.0)로 역변환합니다.
     */
    fun unletterboxBox(
        box: NormalizedBox,
        transform: LetterboxTransform
    ): NormalizedBox {
        val leftPx = box.left * transform.dstWidth
        val topPx = box.top * transform.dstHeight
        val rightPx = box.right * transform.dstWidth
        val bottomPx = box.bottom * transform.dstHeight

        val origW = transform.srcWidth * transform.scale
        val origH = transform.srcHeight * transform.scale

        val unletterLeft = ((leftPx - transform.padX) / origW).coerceIn(0f, 1f)
        val unletterTop = ((topPx - transform.padY) / origH).coerceIn(0f, 1f)
        val unletterRight = ((rightPx - transform.padX) / origW).coerceIn(0f, 1f)
        val unletterBottom = ((bottomPx - transform.padY) / origH).coerceIn(0f, 1f)

        return NormalizedBox(
            left = min(unletterLeft, unletterRight),
            top = min(unletterTop, unletterBottom),
            right = max(unletterLeft, unletterRight),
            bottom = max(unletterTop, unletterBottom)
        )
    }

    /**
     * 단일 좌표점을 원본 프레임 정규화 좌표계로 역변환합니다.
     */
    fun unletterboxPoint(
        point: PointF,
        transform: LetterboxTransform
    ): PointF {
        val px = point.x * transform.dstWidth
        val py = point.y * transform.dstHeight

        val origW = transform.srcWidth * transform.scale
        val origH = transform.srcHeight * transform.scale

        val unletterX = ((px - transform.padX) / origW).coerceIn(0f, 1f)
        val unletterY = ((py - transform.padY) / origH).coerceIn(0f, 1f)

        return PointF(unletterX, unletterY)
    }

    /**
     * 횡단보도 폴리곤 정점 목록을 원본 프레임 좌표계로 일괄 역변환합니다.
     */
    fun unletterboxPolygon(
        polygon: List<PointF>,
        transform: LetterboxTransform
    ): List<PointF> {
        return polygon.map { unletterboxPoint(it, transform) }
    }
}
