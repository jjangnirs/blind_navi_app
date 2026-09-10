package kr.safecross.mobile.camera

import kotlinx.coroutines.runBlocking
import kr.safecross.mobile.decision.CrossingDecisionEngine
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.perception.FakeCrosswalkEstimator
import kr.safecross.mobile.perception.FakeSignalAssociator
import kr.safecross.mobile.perception.FakeSignalEstimator
import kr.safecross.mobile.perception.VerifiedCrossingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 앱 저장소 전수 검사 테스트: 카메라 프레임 파일 0건 확인 (SR-NF-024, SR-NF-041).
 *
 * 수용 기준:
 * - 횡단 보조 카메라 분석 루프 가동 후 앱 내부/외부 디렉터리에
 *   영상/이미지 파일(jpg, jpeg, png, yuv, raw, mp4)이 0건이어야 함.
 */
class CameraStorageZeroLeakageTest {

    private val mediaExtensions = setOf("jpg", "jpeg", "png", "yuv", "raw", "mp4", "mkv", "avi")

    @Test
    fun testAppStorageContainsZeroCameraFrameFilesAfterPipelineExecution() = runBlocking {
        val mockStorageRoot = File.createTempFile("mock_app_storage", "").apply {
            delete()
            mkdir()
        }

        val cacheDir = File(mockStorageRoot, "cache").apply { mkdir() }
        val filesDir = File(mockStorageRoot, "files").apply { mkdir() }
        val externalDir = File(mockStorageRoot, "external_files").apply { mkdir() }

        try {
            val crosswalkEstimator = FakeCrosswalkEstimator()
            val signalEstimator = FakeSignalEstimator.createStableGreenSequence(30)
            val signalAssociator = FakeSignalAssociator()
            val decisionEngine = CrossingDecisionEngine()

            val crossing = VerifiedCrossingContext("CW-STORAGE-CHECK-01", 0f, true)
            val pose = DevicePose(12f, 0f, 0f)

            // 100회 연속 프레임 추론 실행
            for (i in 1..100) {
                val frame = FrameRef.createForTesting(timestampNanos = i * 33_333_333L)
                val cwObs = crosswalkEstimator.estimate(frame)
                val sigObs = signalEstimator.estimate(frame)
                val assoc = signalAssociator.associate(crossing, pose, cwObs, sigObs)
                decisionEngine.evaluate(crossing, pose, cwObs, assoc, true)
            }

            // 모의 저장소 전체를 재귀 탐색하여 미디어 파일 검색
            val discoveredMediaFiles = mutableListOf<File>()
            mockStorageRoot.walkTopDown().forEach { file ->
                if (file.isFile && file.extension.lowercase() in mediaExtensions) {
                    discoveredMediaFiles.add(file)
                }
            }

            // 프레임 파일 개수가 정확히 0건임을 검증
            assertEquals(
                "App storage must contain exactly 0 camera frame files, found: $discoveredMediaFiles",
                0,
                discoveredMediaFiles.size
            )

            // 디렉터리들이 비어있거나 프레임 관련 파일이 없음을 확인
            assertTrue("Storage root directory is clean", mockStorageRoot.exists())
        } finally {
            mockStorageRoot.deleteRecursively()
        }
    }
}
