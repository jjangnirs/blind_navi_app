package kr.safecross.mobile.ml.contract

/**
 * 텐서 규격 정의
 */
data class TensorSpec(
    val name: String,
    val shape: List<Int>,
    val dataType: String
)

/**
 * 온디바이스 모델 무결성 및 계약 매니페스트 (SR-F-050, TRD 4.5).
 */
data class ModelManifest(
    val modelName: String,
    val modelVersion: String,
    val sha256: String,
    val minAppVersion: String,
    val disabled: Boolean,
    val inputTensor: TensorSpec,
    val outputTensors: List<TensorSpec>,
    val labelsOrder: List<String>
)
