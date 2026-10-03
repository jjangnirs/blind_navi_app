package kr.safecross.mobile.camera

import kotlin.math.sqrt

/**
 * ARCore 프레임과 함께 전달되는 공간 정보 (ADR-0041). 영상 픽셀은 포함하지 않는다.
 *
 * 좌표 약속
 * - 화면 좌표(u, v): 세로 화면 기준 정규화 좌표, 왼쪽 위 (0, 0), 오른쪽 아래 (1, 1). [FrameRef] 버퍼와 같은 방향.
 * - 카메라 좌표: 화면 방향 기준(display-oriented) X 오른쪽, Y 위, -Z 카메라 정면.
 * - [rotation]: 카메라 → 월드 회전행렬 (행 우선 3x3).
 */
class ArFrameContext(
    val rotation: FloatArray,
    val focalLengthPx: Float,
    val portraitWidth: Int,
    val portraitHeight: Int,
    val geo: GeoCamera? = null,
    // 센서 방향 깊이(mm)·장면 라벨 영상: 기록(섀도 모드)용, 없으면 null
    private val depthMm: ShortArray? = null,
    private val depthWidth: Int = 0,
    private val depthHeight: Int = 0,
    private val semanticLabels: ByteArray? = null,
    private val semanticWidth: Int = 0,
    private val semanticHeight: Int = 0,
    // 센서 영상 → 세로 화면 회전 각도 (ImageBufferRotator와 동일 규칙)
    private val sensorRotationDegrees: Int = 90
) {
    /** ARCore Geospatial 카메라 자세 (VPS) */
    data class GeoCamera(
        val latitude: Double,
        val longitude: Double,
        val horizontalAccuracyM: Double,
        val headingDeg: Double,
        val pitchDeg: Double,
        val yawAccuracyDeg: Double
    ) {
        val isPrecise: Boolean get() = yawAccuracyDeg <= 10.0 && horizontalAccuracyM <= 10.0
    }

    /** 화면 좌표의 시선 방향을 월드 좌표 단위 벡터로 */
    fun worldDirection(u: Float, v: Float): FloatArray {
        val cx = (u - 0.5f) * portraitWidth / focalLengthPx
        val cy = -(v - 0.5f) * portraitHeight / focalLengthPx
        val cam = normalize(floatArrayOf(cx, cy, -1f))
        val r = rotation
        return floatArrayOf(
            r[0] * cam[0] + r[1] * cam[1] + r[2] * cam[2],
            r[3] * cam[0] + r[4] * cam[1] + r[5] * cam[2],
            r[6] * cam[0] + r[7] * cam[1] + r[8] * cam[2]
        )
    }

    /** 월드 방향(먼 물체)을 현재 화면 좌표로 투영. 카메라 뒤쪽이면 null */
    fun project(worldDir: FloatArray): Pair<Float, Float>? {
        val r = rotation
        // 카메라 좌표 = R^T * w
        val x = r[0] * worldDir[0] + r[3] * worldDir[1] + r[6] * worldDir[2]
        val y = r[1] * worldDir[0] + r[4] * worldDir[1] + r[7] * worldDir[2]
        val z = r[2] * worldDir[0] + r[5] * worldDir[1] + r[8] * worldDir[2]
        if (z >= -1e-3f) return null
        val u = 0.5f + (x / -z) * focalLengthPx / portraitWidth
        val v = 0.5f - (y / -z) * focalLengthPx / portraitHeight
        return u to v
    }

    /** 세로 화면 정규화 좌표 → 센서 방향 영상 정규화 좌표 */
    fun toSensorNormalized(u: Float, v: Float): Pair<Float, Float> = when ((sensorRotationDegrees % 360 + 360) % 360) {
        90 -> v to (1f - u)
        180 -> (1f - u) to (1f - v)
        270 -> (1f - v) to u
        else -> u to v
    }

    /** 화면 좌표의 깊이(m). 깊이 영상이 없거나 값이 없으면 null */
    fun depthMetersAt(u: Float, v: Float): Float? {
        val d = depthMm ?: return null
        if (depthWidth <= 0 || depthHeight <= 0) return null
        val (sx, sy) = toSensorNormalized(u, v)
        val x = (sx * depthWidth).toInt().coerceIn(0, depthWidth - 1)
        val y = (sy * depthHeight).toInt().coerceIn(0, depthHeight - 1)
        val mm = d[y * depthWidth + x].toInt() and 0xFFFF
        return if (mm == 0) null else mm / 1000f
    }

    /** 화면 영역(정규화) 안 장면 라벨 비율. 라벨 영상이 없으면 빈 맵 */
    fun semanticHistogram(left: Float, top: Float, right: Float, bottom: Float): Map<Int, Float> {
        val labels = semanticLabels ?: return emptyMap()
        if (semanticWidth <= 0 || semanticHeight <= 0) return emptyMap()
        val counts = HashMap<Int, Int>()
        var total = 0
        val steps = 6
        for (i in 0..steps) for (j in 0..steps) {
            val u = left + (right - left) * i / steps
            val v = top + (bottom - top) * j / steps
            val (sx, sy) = toSensorNormalized(u, v)
            val x = (sx * semanticWidth).toInt().coerceIn(0, semanticWidth - 1)
            val y = (sy * semanticHeight).toInt().coerceIn(0, semanticHeight - 1)
            val label = labels[y * semanticWidth + x].toInt() and 0xFF
            counts[label] = (counts[label] ?: 0) + 1
            total++
        }
        return counts.mapValues { it.value.toFloat() / total }
    }

    companion object {
        /** ARCore SemanticLabel 순서 (UNLABELED=0 … WATER=11) */
        val SEMANTIC_LABEL_NAMES = listOf(
            "UNLABELED", "SKY", "BUILDING", "TREE", "ROAD", "SIDEWALK",
            "TERRAIN", "STRUCTURE", "OBJECT", "VEHICLE", "PERSON", "WATER"
        )

        /** 쿼터니언(x, y, z, w) → 행 우선 3x3 회전행렬 */
        fun rotationFromQuaternion(qx: Float, qy: Float, qz: Float, qw: Float): FloatArray = floatArrayOf(
            1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
            2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
            2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy)
        )

        private fun normalize(v: FloatArray): FloatArray {
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }
    }
}
