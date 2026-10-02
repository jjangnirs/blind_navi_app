package kr.safecross.mobile.sensor

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * ARCore Geospatial 방향 계산 (순수 함수, 단위 테스트 대상).
 */
object GeospatialHeadingMath {

    data class NavigationHeading(
        val headingDegrees: Double,
        // true: 휴대폰 윗변 방향(화면을 위로 눕혀 든 자세), false: 후면 카메라 정면 방향(세워 든 자세)
        val isTopAxis: Boolean
    )

    /**
     * 화면 방향 기준(display-oriented) 카메라 GeospatialPose의 East-Up-South 쿼터니언(x, y, z, w)으로
     * 보행 안내에 쓸 방위각을 구한다. 휴대폰 윗변(+Y)과 카메라 정면(-Z) 중 수평 성분이 큰 축을 사용하여
     * 눕혀 들었을 때는 나침반과 같은 "윗변 방향", 세워 들었을 때는 "카메라가 보는 방향"이 된다.
     */
    fun navigationHeading(qx: Float, qy: Float, qz: Float, qw: Float): NavigationHeading {
        val x = qx.toDouble()
        val y = qy.toDouble()
        val z = qz.toDouble()
        val w = qw.toDouble()

        // +Y(윗변) 축의 EUS 성분 (회전행렬 두 번째 열)
        val topEast = 2.0 * (x * y - w * z)
        val topSouth = 2.0 * (y * z + w * x)
        // -Z(카메라 정면) 축의 EUS 성분 (회전행렬 세 번째 열의 부호 반전)
        val fwdEast = -2.0 * (x * z + w * y)
        val fwdSouth = -(1.0 - 2.0 * (x * x + y * y))

        val topHorizontal = hypot(topEast, topSouth)
        val fwdHorizontal = hypot(fwdEast, fwdSouth)
        val useTop = topHorizontal >= fwdHorizontal
        val east = if (useTop) topEast else fwdEast
        val north = -(if (useTop) topSouth else fwdSouth)
        val deg = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
        return NavigationHeading(deg, useTop)
    }
}
