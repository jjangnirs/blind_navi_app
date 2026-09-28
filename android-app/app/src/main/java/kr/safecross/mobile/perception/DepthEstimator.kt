package kr.safecross.mobile.perception

import kr.safecross.mobile.camera.FrameRef

/**
 * 온디바이스 상대 깊이 추정 인터페이스 (TRD 4.6).
 */
interface DepthEstimator {
    suspend fun estimate(frame: FrameRef): DepthObservation
}

/**
 * 테스트/미리보기용 가짜 깊이 추정기
 */
class FakeDepthEstimator(
    var scenario: Scenario = Scenario.NORMAL
) : DepthEstimator {

    enum class Scenario {
        NORMAL,
        UNAVAILABLE
    }

    override suspend fun estimate(frame: FrameRef): DepthObservation {
        return when (scenario) {
            Scenario.NORMAL -> DepthObservation(
                isAvailable = true,
                depthMap = floatArrayOf(0.4f, 0.5f, 0.5f, 0.6f),
                mapWidth = 2,
                mapHeight = 2,
                minDepth = 0.4f,
                maxDepth = 0.6f,
                quality = 0.9f
            )
            Scenario.UNAVAILABLE -> DepthObservation.unavailable()
        }
    }
}
