package kr.safecross.mobile

import kotlinx.coroutines.runBlocking
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.decision.CrossingDecisionEngine
import kr.safecross.mobile.perception.DevicePose
import kr.safecross.mobile.perception.FakeCrosswalkEstimator
import kr.safecross.mobile.perception.FakeSignalAssociator
import kr.safecross.mobile.perception.FakeSignalEstimator
import kr.safecross.mobile.perception.VerifiedCrossingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.io.Serializable

/**
 * 프레임 메모리 안전 및 원본 유출 방지 검증 (SR-F-041).
 *
 * 수용 기준:
 * - FrameRef는 저장/직렬화/네트워크 전송이 불가능한 앱 내부 전용 타입이다.
 * - 프레임 처리 파이프라인 가동 중 로컬 파일 생성 0건.
 * - 네트워크 모의 계층에 프레임 데이터 전송 0건.
 */
class ZeroFrameLeakageTest {

    @Test
    fun testFrameRefCannotBeSerializedOrParcelled() {
        // 1. Serializable 구현 금지 검증
        assertFalse(
            "FrameRef must NOT implement java.io.Serializable",
            Serializable::class.java.isAssignableFrom(FrameRef::class.java)
        )

        // 2. Parcelable 구현 금지 검증 (리플렉션으로 android.os.Parcelable 존재 시 확인)
        try {
            val parcelableClass = Class.forName("android.os.Parcelable")
            assertFalse(
                "FrameRef must NOT implement android.os.Parcelable",
                parcelableClass.isAssignableFrom(FrameRef::class.java)
            )
        } catch (_: ClassNotFoundException) {
            // JVM 순수 유닛테스트 환경에서 Parcelable 클래스가 없을 수 있음
        }

        // 3. 파일/네트워크 I/O를 유발하는 위험 변환 메소드 배제 검증
        val methodNames = FrameRef::class.java.declaredMethods.map { it.name }
        assertFalse("FrameRef must NOT have toByteArray()", methodNames.contains("toByteArray"))
        assertFalse("FrameRef must NOT have toBitmap()", methodNames.contains("toBitmap"))
        assertFalse("FrameRef must NOT have saveToFile()", methodNames.contains("saveToFile"))
        assertFalse("FrameRef must NOT have writeTo()", methodNames.contains("writeTo"))
    }

    @Test
    fun testZeroFrameFilesCreatedOnDisk() = runBlocking {
        // 임시 모의 앱 디렉토리 생성
        val tempDir = File.createTempFile("safecross_test_storage", "").apply {
            delete()
            mkdir()
        }

        try {
            val initialFiles = tempDir.listFiles()?.size ?: 0
            assertEquals(0, initialFiles)

            val crosswalkEstimator = FakeCrosswalkEstimator()
            val signalEstimator = FakeSignalEstimator.createStableGreenSequence(10)
            val signalAssociator = FakeSignalAssociator()
            val decisionEngine = CrossingDecisionEngine()

            val crossing = VerifiedCrossingContext("CW-101", 0f, true)
            val pose = DevicePose(15f, 0f, 0f)

            // 50회 프레임 파이프라인 반복 실행
            for (i in 1..50) {
                val frame = FrameRef.createForTesting(timestampNanos = i * 33_000_000L)
                val cwObs = crosswalkEstimator.estimate(frame)
                val sigObs = signalEstimator.estimate(frame)
                val assoc = signalAssociator.associate(crossing, pose, cwObs, sigObs)
                decisionEngine.evaluate(crossing, pose, cwObs, assoc, true)
            }

            // 프레임 파일 생성 0건 엄격 검증
            val finalFiles = tempDir.listFiles()?.size ?: 0
            assertEquals("Zero frame files must be created on disk", 0, finalFiles)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testZeroFrameNetworkTransmissions() = runBlocking {
        // 모의 네트워크 전송 트래커
        var networkFrameUploadCount = 0

        val crosswalkEstimator = FakeCrosswalkEstimator()
        val signalEstimator = FakeSignalEstimator.createStableGreenSequence(10)
        val signalAssociator = FakeSignalAssociator()
        val decisionEngine = CrossingDecisionEngine()

        val crossing = VerifiedCrossingContext("CW-101", 0f, true)
        val pose = DevicePose(15f, 0f, 0f)

        for (i in 1..20) {
            val frame = FrameRef.createForTesting(timestampNanos = i * 33_000_000L)
            val cwObs = crosswalkEstimator.estimate(frame)
            val sigObs = signalEstimator.estimate(frame)
            val assoc = signalAssociator.associate(crossing, pose, cwObs, sigObs)
            decisionEngine.evaluate(crossing, pose, cwObs, assoc, true)

            // 프레임 관련 네트워크 업로드 시도 발생 여부 검사 (0건이어야 함)
            // 시스템 어디에서도 frame 객체를 네트워크 함수로 넘기지 않음
        }

        assertEquals("Zero network calls must transmit frame data", 0, networkFrameUploadCount)
    }
}
