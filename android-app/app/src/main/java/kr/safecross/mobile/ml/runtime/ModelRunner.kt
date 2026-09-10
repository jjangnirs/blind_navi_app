package kr.safecross.mobile.ml.runtime

import java.nio.ByteBuffer

/**
 * 온디바이스 모델 실행기 추상화 인터페이스 (SR-F-047, TRD 4.5).
 */
interface ModelRunner : AutoCloseable {
    val backend: ExecutionBackend

    /**
     * 입력 버퍼를 주입하고 출력 버퍼 맵에 결과를 채웁니다.
     * @throws Exception 가속기 실패 또는 추론 오류 시 발생
     */
    fun runInference(inputBuffer: ByteBuffer, outputBuffers: Map<Int, Any>)

    override fun close()
}

/**
 * 데스크톱 JVM 유닛 테스트, CI 및 오프라인 골든셋 검증용 결정론적 러너
 */
class DeterministicTestModelRunner(
    override val backend: ExecutionBackend = ExecutionBackend.CPU,
    var simulateFailure: Boolean = false,
    var failureBackend: ExecutionBackend? = null,
    var onInference: ((ByteBuffer, Map<Int, Any>) -> Unit)? = null
) : ModelRunner {

    var closed: Boolean = false
        private set

    var inferenceCount: Int = 0
        private set

    override fun runInference(inputBuffer: ByteBuffer, outputBuffers: Map<Int, Any>) {
        check(!closed) { "ModelRunner is already closed" }

        // 가속기 오류 시뮬레이션 (SR-F-047 검증용)
        if (simulateFailure && (failureBackend == null || failureBackend == backend)) {
            throw RuntimeException("ACCELERATOR_INFERENCE_FAILURE: Hardware accelerator fault on $backend")
        }

        inferenceCount++
        onInference?.invoke(inputBuffer, outputBuffers)
    }

    override fun close() {
        closed = true
    }
}
