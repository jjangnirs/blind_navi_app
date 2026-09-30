package kr.safecross.mobile

import kr.safecross.mobile.perception.DepthObservation
import kr.safecross.mobile.perception.DepthRoiAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepthRoiAnalyzerTest {

    private fun observationOf(width: Int, height: Int, map: FloatArray): DepthObservation {
        return DepthObservation(
            isAvailable = true,
            depthMap = map,
            mapWidth = width,
            mapHeight = height,
            minDepth = map.min(),
            maxDepth = map.max(),
            quality = 1.0f
        )
    }

    @Test
    fun uniformDepthMap_ratioIsOne() {
        val map = FloatArray(16) { 5f }
        val ratio = DepthRoiAnalyzer.nearPathProximityRatio(observationOf(4, 4, map))
        assertEquals(1.0f, ratio!!, 0.001f)
    }

    @Test
    fun closeNearPathRegion_ratioAboveOne() {
        // 4x4: 하단 중앙 2x2 영역만 10, 나머지는 2
        val map = FloatArray(16) { 2f }
        val nearRows = 2..3
        val nearCols = 1..2
        for (y in nearRows) {
            for (x in nearCols) {
                map[y * 4 + x] = 10f
            }
        }
        val ratio = DepthRoiAnalyzer.nearPathProximityRatio(observationOf(4, 4, map))
        // frameAvg = (4*10 + 12*2)/16 = 4.0, nearAvg = 10.0 -> ratio = 2.5
        assertEquals(2.5f, ratio!!, 0.001f)
    }

    @Test
    fun unavailableObservation_returnsNull() {
        val ratio = DepthRoiAnalyzer.nearPathProximityRatio(DepthObservation.unavailable())
        assertNull(ratio)
    }

    @Test
    fun allZeroDepthMap_returnsNull() {
        val map = FloatArray(16) { 0f }
        val ratio = DepthRoiAnalyzer.nearPathProximityRatio(observationOf(4, 4, map))
        assertNull(ratio)
    }

    @Test
    fun uniformDepthMap_peakRatioIsOne() {
        val map = FloatArray(16) { 5f }
        val peakRatio = DepthRoiAnalyzer.nearPathPeakRatio(observationOf(4, 4, map))
        assertEquals(1.0f, peakRatio!!, 0.001f)
    }

    @Test
    fun smallSpikeInNearRoi_peakRatioCatchesItEvenWhenDiluted() {
        // 4x4: 배경은 전부 2, 하단 중앙 ROI(행2-3, 열1-2) 중 단 1칸만 50으로 튐(작은 물체 시뮬레이션)
        val map = FloatArray(16) { 2f }
        map[2 * 4 + 1] = 50f // near ROI 내부 좌상단 셀 하나만 스파이크

        val peakRatio = DepthRoiAnalyzer.nearPathPeakRatio(observationOf(4, 4, map))
        // frameAvg = (15*2 + 50)/16 = 5.0, nearPeak = 50 -> peakRatio = 10.0
        assertEquals(10.0f, peakRatio!!, 0.001f)
    }

    @Test
    fun peakRatioUnavailableObservation_returnsNull() {
        val peakRatio = DepthRoiAnalyzer.nearPathPeakRatio(DepthObservation.unavailable())
        assertNull(peakRatio)
    }
}
