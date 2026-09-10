package kr.safecross.mobile.camera

/**
 * 카메라 프레임의 앱 내부 전용 메모리 참조 타입 (SR-F-041).
 *
 * [보안 및 개인정보 보호 불변식]
 * 1. Serializable, Parcelable을 절대 구현하지 않아 파일/디스크 영속화 및 프로세스 간 전송을 원천 차단한다.
 * 2. toByteArray(), toBitmap(), Base64 인코딩 등 파일 저장 및 네트워크 전송을 유발하는 변환 메소드를 제공하지 않는다.
 * 3. 온디바이스 perception 파이프라인 내부 메모리에서만 사용된 후 즉시 GC된다.
 * 4. toString()에 화상 픽셀 원문이 포함되지 않도록 메타데이터만 안전하게 출력한다.
 */
class FrameRef internal constructor(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampNanos: Long,
    val sensorTimestampNanos: Long,
    val rgbaBuffer: java.nio.ByteBuffer? = null
) {
    override fun toString(): String {
        return "FrameRef(dim=${width}x${height}, rot=${rotationDegrees}, tsNanos=${timestampNanos})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameRef) return false
        return width == other.width &&
                height == other.height &&
                rotationDegrees == other.rotationDegrees &&
                timestampNanos == other.timestampNanos &&
                sensorTimestampNanos == other.sensorTimestampNanos
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + rotationDegrees
        result = 31 * result + timestampNanos.hashCode()
        result = 31 * result + sensorTimestampNanos.hashCode()
        return result
    }

    companion object {
        /**
         * 테스트 및 모의 추론용 팩토리 메소드
         */
        fun createForTesting(
            width: Int = 640,
            height: Int = 480,
            rotationDegrees: Int = 0,
            timestampNanos: Long = System.nanoTime(),
            sensorTimestampNanos: Long = System.nanoTime(),
            rgbaBuffer: java.nio.ByteBuffer? = null
        ): FrameRef {
            return FrameRef(width, height, rotationDegrees, timestampNanos, sensorTimestampNanos, rgbaBuffer)
        }
    }
}
