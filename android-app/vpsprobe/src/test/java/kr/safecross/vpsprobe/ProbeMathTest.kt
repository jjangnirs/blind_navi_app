package kr.safecross.vpsprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ProbeMathTest {

    /** Up(y)축 기준 회전 쿼터니언. 카메라 정면(-Z)이 북쪽에서 시계 방향으로 headingDeg만큼 돌도록 생성 */
    private fun yawQuaternion(headingDeg: Double): FloatArray {
        val theta = Math.toRadians(-headingDeg) // y축 반시계(+) 회전이 서쪽이므로 부호 반전
        return floatArrayOf(0f, sin(theta / 2).toFloat(), 0f, cos(theta / 2).toFloat())
    }

    @Test
    fun headingFromEusQuaternion() {
        for (h in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 315.0)) {
            val q = yawQuaternion(h)
            assertEquals("heading $h", h, ProbeMath.cameraHeadingFromEusQuaternion(q[0], q[1], q[2], q[3]), 0.01)
        }
    }

    @Test
    fun headingDiffWrapsAround() {
        assertEquals(10.0, ProbeMath.headingDiff(5.0, 355.0), 1e-9)
        assertEquals(-10.0, ProbeMath.headingDiff(355.0, 5.0), 1e-9)
    }

    @Test
    fun distanceAndStats() {
        assertEquals(111.2, ProbeMath.distanceMeters(35.0, 127.0, 35.001, 127.0), 0.5)
        val s = ProbeMath.stats(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0))!!
        assertEquals(5.0, s.median, 1e-9)
        assertEquals(9.0, s.p90, 1e-9)
        assertNull(ProbeMath.stats(emptyList()))
    }
}
