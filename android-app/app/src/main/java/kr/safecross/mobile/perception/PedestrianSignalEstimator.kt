package kr.safecross.mobile.perception

import kr.safecross.mobile.camera.FrameRef
import java.util.concurrent.atomic.AtomicInteger

/**
 * 보행자 신호등 검출 및 상태 분류 추정기 인터페이스 (SR-F-042, TRD 4.6).
 */
interface PedestrianSignalEstimator {
    suspend fun estimate(frame: FrameRef): List<SignalObservation>
}

/**
 * 복수 신호 Bounding Box와 스크립트화된 시퀀스를 반환하는 가짜 신호 추정기
 */
class FakeSignalEstimator(
    var sequence: List<ObservedSignalState> = listOf(ObservedSignalState.RED),
    var returnMultipleBoxes: Boolean = false,
    var ephemeralTrackId: String = "track-sig-101"
) : PedestrianSignalEstimator {

    private val sequenceIndex = AtomicInteger(0)

    fun resetSequence() {
        sequenceIndex.set(0)
    }

    override suspend fun estimate(frame: FrameRef): List<SignalObservation> {
        val idx = sequenceIndex.getAndIncrement()
        val currentState = if (sequence.isNotEmpty()) {
            sequence[idx % sequence.size]
        } else {
            ObservedSignalState.UNKNOWN
        }

        val primaryObservation = SignalObservation(
            ephemeralTrackId = ephemeralTrackId,
            state = currentState,
            score = if (currentState == ObservedSignalState.UNKNOWN) 0.3f else 0.94f,
            box = NormalizedBox(left = 0.45f, top = 0.20f, right = 0.55f, bottom = 0.40f),
            frameTimestampNanos = frame.timestampNanos,
            quality = FrameQuality(lighting = 0.85f, blur = 0.90f, isUsable = true),
            modelVersion = "fake-sig-model-1.0.0"
        )

        return if (returnMultipleBoxes) {
            // 보행자 신호 외에 측면/차량 신호등 박스가 추가 검출되는 모의 상황
            val secondaryObservation = SignalObservation(
                ephemeralTrackId = "track-veh-202",
                state = ObservedSignalState.RED,
                score = 0.82f,
                box = NormalizedBox(left = 0.75f, top = 0.15f, right = 0.90f, bottom = 0.35f),
                frameTimestampNanos = frame.timestampNanos,
                quality = FrameQuality(lighting = 0.85f, blur = 0.90f, isUsable = true),
                modelVersion = "fake-sig-model-1.0.0"
            )
            listOf(primaryObservation, secondaryObservation)
        } else {
            listOf(primaryObservation)
        }
    }

    companion object {
        /**
         * 1회만 GREEN이 나타나고 다시 RED로 돌아가는 단일 녹색 위험 테스트 시퀀스
         */
        fun createSingleGreenSequence(): FakeSignalEstimator {
            return FakeSignalEstimator(
                sequence = listOf(
                    ObservedSignalState.RED,
                    ObservedSignalState.RED,
                    ObservedSignalState.GREEN, // 단 1회 프레임 GREEN
                    ObservedSignalState.RED,
                    ObservedSignalState.RED
                )
            )
        }

        /**
         * 지속적으로 안정된 GREEN이 유지되는 안전 승인용 시퀀스
         */
        fun createStableGreenSequence(greenCount: Int = 10): FakeSignalEstimator {
            val list = mutableListOf(ObservedSignalState.RED, ObservedSignalState.RED)
            repeat(greenCount) { list.add(ObservedSignalState.GREEN) }
            return FakeSignalEstimator(sequence = list)
        }
    }
}
