package kr.safecross.mobile

import kr.safecross.mobile.ml.contract.ModelContractValidator
import kr.safecross.mobile.ml.contract.ModelManifest
import kr.safecross.mobile.ml.contract.TensorSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 모델 무결성, SHA-256 해시, 라벨 순서, 텐서 계약 검증기 단위 테스트 (SR-F-050, SR-F-051).
 */
class ModelContractValidatorTest {

    private lateinit var validModelBytes: ByteArray
    private lateinit var validManifest: ModelManifest
    private lateinit var validLabels: List<String>

    @Before
    fun setUp() {
        val modelFile = File("src/main/assets/models/ped_signal_v1.tflite")
        validModelBytes = if (modelFile.exists()) {
            modelFile.readBytes()
        } else {
            // JVM 테스트 fallback 바이트
            "SafeCrossKR_FrozenModel_PedestrianSignal_v1.0.0_Deterministic".toByteArray()
        }

        val expectedSha256 = ModelContractValidator.computeSha256(validModelBytes)
        validLabels = listOf(
            "PEDESTRIAN_SIGNAL_RED",
            "PEDESTRIAN_SIGNAL_GREEN",
            "UNKNOWN"
        )
        validManifest = ModelManifest(
            modelName = "ped_signal",
            modelVersion = "1.0.0",
            sha256 = expectedSha256,
            minAppVersion = "0.1.0",
            disabled = false,
            inputTensor = TensorSpec("input_image", listOf(1, 320, 320, 3), "FLOAT32"),
            outputTensors = listOf(
                TensorSpec("detection_boxes", listOf(1, 10, 4), "FLOAT32")
            ),
            labelsOrder = validLabels
        )
    }

    @Test
    fun testValidModelPassesValidation() {
        val result = ModelContractValidator.validate(validModelBytes, validManifest, validLabels)
        assertTrue("정상 모델은 검증을 통과해야 합니다", result.isValid)
        assertTrue(result.sha256Matched)
        assertTrue(result.labelsOrderMatched)
        assertFalse(result.killSwitchActive)
        assertEquals("MODEL_CONTRACT_VERIFIED", result.reason)
    }

    @Test
    fun testTamperedModelRejected() {
        // 임의 1바이트 변조
        val tamperedBytes = validModelBytes.clone()
        tamperedBytes[0] = (tamperedBytes[0].toInt() xor 0xFF).toByte()

        val result = ModelContractValidator.validate(tamperedBytes, validManifest, validLabels)
        assertFalse("변조된 모델은 로드가 거부되어야 합니다 (수용기준 1)", result.isValid)
        assertFalse(result.sha256Matched)
        assertTrue(result.reason.contains("SHA256_HASH_MISMATCH"))
    }

    @Test
    fun testLabelsOrderPermutationDetectedAndRejected() {
        // 라벨 순서가 뒤바뀐 경우 (RED/GREEN 순서가 역전된 위험 상황 탐지)
        val invertedLabels = listOf(
            "PEDESTRIAN_SIGNAL_GREEN", // 순서 오류
            "PEDESTRIAN_SIGNAL_RED",
            "UNKNOWN"
        )

        val result = ModelContractValidator.validate(validModelBytes, validManifest, invertedLabels)
        assertFalse("라벨 순서가 불일치하면 로드가 거부되어야 합니다 (수용기준 2)", result.isValid)
        assertFalse(result.labelsOrderMatched)
        assertTrue(result.reason.contains("LABELS_ORDER_MISMATCH"))
    }

    @Test
    fun testKillSwitchRejectsModelLoad() {
        // 원격 킬스위치 활성화 (disabled: true, SR-F-051)
        val disabledManifest = validManifest.copy(disabled = true)

        val result = ModelContractValidator.validate(validModelBytes, disabledManifest, validLabels)
        assertFalse("킬스위치 활성화 모델은 로드가 거부되어야 합니다", result.isValid)
        assertTrue(result.killSwitchActive)
        assertEquals("MODEL_DISABLED_BY_KILL_SWITCH", result.reason)
    }

    @Test
    fun testInvalidTensorShapeRejected() {
        // 비정상적인 입력 텐서 shape
        val badTensorManifest = validManifest.copy(
            inputTensor = TensorSpec("input_image", listOf(1, -1, 320, 3), "FLOAT32")
        )

        val result = ModelContractValidator.validate(validModelBytes, badTensorManifest, validLabels)
        assertFalse("비정상적인 텐서 규격은 거부되어야 합니다", result.isValid)
        assertFalse(result.tensorContractMatched)
    }
}
