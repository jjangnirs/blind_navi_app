package kr.safecross.mobile

import kr.safecross.mobile.ml.vision.RoiCropHelper
import kr.safecross.mobile.ml.vision.VisionTransforms
import kr.safecross.mobile.perception.CrosswalkObservation
import kr.safecross.mobile.perception.NormalizedBox
import kr.safecross.mobile.perception.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 컴퓨터 비전 전처리/후처리 및 동일 좌표계 공유 골든 테스트 (요구 3, 10, TRD 4.4).
 */
class VisionTransformsGoldenTest {

    private val floatEpsilon = 1e-5f

    @Test
    fun testLetterboxTransformCalculations() {
        // 640x480(4:3) -> 320x320(1:1) 변환
        val transform = VisionTransforms.computeLetterboxTransform(
            srcWidth = 640,
            srcHeight = 480,
            dstWidth = 320,
            dstHeight = 320
        )

        // 가로가 꽉 차므로 scale = 320 / 640 = 0.5
        assertEquals(0.5f, transform.scale, floatEpsilon)
        // 가로 패딩 = 0
        assertEquals(0.0f, transform.padX, floatEpsilon)
        // 세로 크기 = 480 * 0.5 = 240px, 세로 패딩 = (320 - 240) / 2 = 40px
        assertEquals(40.0f, transform.padY, floatEpsilon)
    }

    @Test
    fun testUnletterboxCoordinatesRestoresToOriginalFrameSpace() {
        val transform = VisionTransforms.computeLetterboxTransform(640, 480, 320, 320)

        // 모델 출력 정규화 좌표계에서 실제 화상 영역의 최상단(y=40px/320px=0.125)과 최하단(y=280px/320px=0.875)
        val modelSpaceBox = NormalizedBox(
            left = 0.0f,
            top = 40f / 320f,
            right = 1.0f,
            bottom = 280f / 320f
        )

        val unletterboxed = VisionTransforms.unletterboxBox(modelSpaceBox, transform)

        // 원본 프레임 좌표계 [0.0, 1.0]으로 완벽하게 복원되어야 함
        assertEquals(0.0f, unletterboxed.left, floatEpsilon)
        assertEquals(0.0f, unletterboxed.top, floatEpsilon)
        assertEquals(1.0f, unletterboxed.right, floatEpsilon)
        assertEquals(1.0f, unletterboxed.bottom, floatEpsilon)
    }

    @Test
    fun testBothModelsShareIdenticalOriginalFrameCoordinatesGoldenTest() {
        // 요구 10: 두 모델(신호 박스와 횡단보도 폴리곤)의 출력이 동일한 원본 프레임 좌표계를 사용함을 골든 테스트한다.
        val transform = VisionTransforms.computeLetterboxTransform(640, 480, 320, 320)

        // 모델 1: 보행신호 박스 (모델 320x320 상단 40~100px)
        val rawSignalBox = NormalizedBox(
            left = 140f / 320f,
            top = 60f / 320f,
            right = 180f / 320f,
            bottom = 120f / 320f
        )
        val restoredSignalBox = VisionTransforms.unletterboxBox(rawSignalBox, transform)

        // 모델 2: 횡단보도 폴리곤 (모델 320x320 하단 140~270px)
        val rawCrosswalkPolygon = listOf(
            PointF(100f / 320f, 260f / 320f),
            PointF(220f / 320f, 260f / 320f),
            PointF(190f / 320f, 150f / 320f),
            PointF(130f / 320f, 150f / 320f)
        )
        val restoredPolygon = VisionTransforms.unletterboxPolygon(rawCrosswalkPolygon, transform)

        // 1. 모든 복원 좌표가 [0.0, 1.0] 범위 내에 온전히 존재함 검증
        assertTrue(restoredSignalBox.left in 0f..1f)
        assertTrue(restoredSignalBox.top in 0f..1f)
        assertTrue(restoredSignalBox.right in 0f..1f)
        assertTrue(restoredSignalBox.bottom in 0f..1f)

        for (pt in restoredPolygon) {
            assertTrue("Polygon point x in range: ${pt.x}", pt.x in 0f..1f)
            assertTrue("Polygon point y in range: ${pt.y}", pt.y in 0f..1f)
        }

        // 2. 기하적 정합성 검증: 보행 신호기는 횡단보도 상단(더 작은 Y)에 위치해야 함
        val crosswalkTopY = restoredPolygon.minOf { it.y }
        assertTrue(
            "보행 신호기(Y=${restoredSignalBox.bottom})는 횡단보도 상단(Y=$crosswalkTopY)보다 위에 위치해야 함",
            restoredSignalBox.bottom < crosswalkTopY
        )

        // 3. 중심선 수평 정합 검증 (동일 X 중심축 공유)
        val signalCenterX = restoredSignalBox.centerX
        val polygonCenterX = (restoredPolygon[0].x + restoredPolygon[1].x) / 2f
        assertTrue(
            "신호기와 횡단보도가 수평 중심선(오차 10% 이내)에 정합해야 함",
            abs(signalCenterX - polygonCenterX) < 0.10f
        )
    }

    @Test
    fun testRoiCropRegionCalculationAndSelection() {
        val crosswalk = CrosswalkObservation(
            hasCrosswalk = true,
            polygon = null,
            entrancePoint = PointF(0.5f, 0.8f),
            directionDegrees = 0f,
            quality = 0.9f,
            confidence = 0.95f
        )

        val roi = RoiCropHelper.computePedestrianSignalRoi(crosswalk, cropWidthRatio = 0.4f, cropHeightRatio = 0.35f)
        assertEquals(0.3f, roi.left, floatEpsilon)
        assertEquals(0.7f, roi.right, floatEpsilon)
        assertEquals(0.05f, roi.top, floatEpsilon)
        assertEquals(0.40f, roi.bottom, floatEpsilon)

        val fullDetections = listOf(NormalizedBox(0.45f, 0.15f, 0.55f, 0.30f))
        val roiDetections = listOf(NormalizedBox(0.375f, 0.285f, 0.625f, 0.714f))

        val bestDetections = RoiCropHelper.selectBestSignalDetections(fullDetections, roiDetections, roi)
        assertEquals(1, bestDetections.size)
    }
}
