package kr.safecross.mobile.perception

import kr.safecross.mobile.camera.FrameRef

/**
 * 횡단보도 형상 및 진입점 추정 인터페이스 (SR-F-052, TRD 4.6).
 */
interface CrosswalkSceneEstimator {
    suspend fun estimate(frame: FrameRef): CrosswalkObservation
}

/**
 * 횡단보도 가짜 추정기 (시나리오별 다양한 형상 및 품질 반환)
 */
class FakeCrosswalkEstimator(
    var scenario: Scenario = Scenario.DETECTED_NORMAL
) : CrosswalkSceneEstimator {

    enum class Scenario {
        DETECTED_NORMAL,
        NOT_DETECTED,
        POOR_QUALITY,
        WRONG_DIRECTION
    }

    override suspend fun estimate(frame: FrameRef): CrosswalkObservation {
        return when (scenario) {
            Scenario.DETECTED_NORMAL -> CrosswalkObservation(
                hasCrosswalk = true,
                polygon = listOf(
                    PointF(0.2f, 0.9f),
                    PointF(0.8f, 0.9f),
                    PointF(0.65f, 0.4f),
                    PointF(0.35f, 0.4f)
                ),
                entrancePoint = PointF(0.5f, 0.85f),
                directionDegrees = 0.0f, // 정면 진행
                quality = 0.92f,
                confidence = 0.95f
            )
            Scenario.NOT_DETECTED -> CrosswalkObservation(
                hasCrosswalk = false,
                polygon = null,
                entrancePoint = null,
                directionDegrees = null,
                quality = 0.80f,
                confidence = 0.10f
            )
            Scenario.POOR_QUALITY -> CrosswalkObservation(
                hasCrosswalk = true,
                polygon = listOf(
                    PointF(0.3f, 0.8f),
                    PointF(0.7f, 0.8f)
                ),
                entrancePoint = PointF(0.5f, 0.8f),
                directionDegrees = 0.0f,
                quality = 0.35f, // 품질 불량
                confidence = 0.45f
            )
            Scenario.WRONG_DIRECTION -> CrosswalkObservation(
                hasCrosswalk = true,
                polygon = listOf(
                    PointF(0.1f, 0.5f),
                    PointF(0.5f, 0.1f)
                ),
                entrancePoint = PointF(0.2f, 0.4f),
                directionDegrees = 85.0f, // 85도 빗겨감 (진행 방향과 심각한 불일치)
                quality = 0.88f,
                confidence = 0.90f
            )
        }
    }
}
