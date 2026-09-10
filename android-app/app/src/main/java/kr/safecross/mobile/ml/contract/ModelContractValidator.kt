package kr.safecross.mobile.ml.contract

import java.security.MessageDigest

/**
 * 모델 검증 결과
 */
data class ModelValidationResult(
    val isValid: Boolean,
    val reason: String,
    val sha256Matched: Boolean = false,
    val labelsOrderMatched: Boolean = false,
    val killSwitchActive: Boolean = false,
    val tensorContractMatched: Boolean = false
)

/**
 * 모델 무결성, SHA-256 해시, 라벨 순서, 텐서 규약 및 킬스위치 검증기 (SR-F-050, SR-F-051).
 */
object ModelContractValidator {

    /**
     * 모델 바이트의 SHA-256 해시를 계산합니다.
     */
    fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * 모델 바이너리와 매니페스트, 실제 라벨 목록을 종합 검증합니다.
     */
    fun validate(
        modelBytes: ByteArray,
        manifest: ModelManifest,
        actualLabels: List<String>
    ): ModelValidationResult {
        // 1. 킬스위치(원격 비활성화) 검사 (SR-F-051)
        if (manifest.disabled) {
            return ModelValidationResult(
                isValid = false,
                reason = "MODEL_DISABLED_BY_KILL_SWITCH",
                killSwitchActive = true
            )
        }

        // 2. SHA-256 해시 위조/변조 검사 (SR-F-050)
        val actualSha256 = computeSha256(modelBytes)
        if (!actualSha256.equals(manifest.sha256, ignoreCase = true)) {
            return ModelValidationResult(
                isValid = false,
                reason = "SHA256_HASH_MISMATCH: expected=${manifest.sha256}, actual=$actualSha256",
                sha256Matched = false
            )
        }

        // 3. 라벨 순서 무결성 검사 (Label permutation attack 방어)
        if (actualLabels != manifest.labelsOrder) {
            return ModelValidationResult(
                isValid = false,
                reason = "LABELS_ORDER_MISMATCH: expected=${manifest.labelsOrder}, actual=$actualLabels",
                sha256Matched = true,
                labelsOrderMatched = false
            )
        }

        // 4. 입력 텐서 형상 검사 (최소 4차원 [1, 320, 320, 3] 등 검증)
        if (manifest.inputTensor.shape.size < 4 || manifest.inputTensor.shape[1] <= 0 || manifest.inputTensor.shape[2] <= 0) {
            return ModelValidationResult(
                isValid = false,
                reason = "INVALID_INPUT_TENSOR_SHAPE: ${manifest.inputTensor.shape}",
                sha256Matched = true,
                labelsOrderMatched = true,
                tensorContractMatched = false
            )
        }

        // 5. 출력 텐서 규격 검사
        if (manifest.outputTensors.isEmpty()) {
            return ModelValidationResult(
                isValid = false,
                reason = "EMPTY_OUTPUT_TENSORS",
                sha256Matched = true,
                labelsOrderMatched = true,
                tensorContractMatched = false
            )
        }

        return ModelValidationResult(
            isValid = true,
            reason = "MODEL_CONTRACT_VERIFIED",
            sha256Matched = true,
            labelsOrderMatched = true,
            killSwitchActive = false,
            tensorContractMatched = true
        )
    }
}
