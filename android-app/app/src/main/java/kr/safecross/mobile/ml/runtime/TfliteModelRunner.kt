package kr.safecross.mobile.ml.runtime

import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TensorFlow Lite / LiteRT 실기기 하드웨어 가속 추론 러너 (SR-F-047, TRD 4.5).
 *
 * - 멀티스레드 CPU 및 NNAPI 하드웨어 가속 지원
 * - 다중 입력/출력 텐서(runForMultipleInputsOutputs) 매핑
 * - 실시간 안전 검증 및 리소스 안전 해제
 */
class TfliteModelRunner(
    private val interpreter: Interpreter,
    override val backend: ExecutionBackend = ExecutionBackend.CPU
) : ModelRunner {

    private var isClosed = false

    override fun runInference(inputBuffer: ByteBuffer, outputBuffers: Map<Int, Any>) {
        check(!isClosed) { "TfliteModelRunner is already closed" }
        require(inputBuffer.isDirect) { "Input buffer must be direct ByteBuffer for hardware acceleration" }

        val inputs = arrayOf<Any>(inputBuffer)
        interpreter.runForMultipleInputsOutputs(inputs, outputBuffers)
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            interpreter.close()
        }
    }

    companion object {
        /**
         * Direct ByteBuffer 모델 데이터로부터 러너 인스턴스를 생성합니다.
         */
        fun createFromBuffer(
            modelBuffer: ByteBuffer,
            backend: ExecutionBackend = ExecutionBackend.CPU,
            numThreads: Int = 4
        ): TfliteModelRunner {
            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
                if (backend == ExecutionBackend.NPU) {
                    setUseNNAPI(true)
                }
            }
            val interpreter = Interpreter(modelBuffer, options)
            return TfliteModelRunner(interpreter, backend)
        }

        /**
         * 모델 ByteArray로부터 직접 Direct ByteBuffer를 할당하여 러너를 생성합니다.
         */
        fun createFromBytes(
            modelBytes: ByteArray,
            backend: ExecutionBackend = ExecutionBackend.CPU,
            numThreads: Int = 4
        ): TfliteModelRunner {
            val directBuffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(modelBytes)
                rewind()
            }
            return createFromBuffer(directBuffer, backend, numThreads)
        }

        /**
         * 모델 파일로부터 직접 러너를 생성합니다.
         */
        fun createFromFile(
            modelFile: File,
            backend: ExecutionBackend = ExecutionBackend.CPU,
            numThreads: Int = 4
        ): TfliteModelRunner {
            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
                if (backend == ExecutionBackend.NPU) {
                    setUseNNAPI(true)
                }
            }
            val interpreter = Interpreter(modelFile, options)
            return TfliteModelRunner(interpreter, backend)
        }
    }
}
