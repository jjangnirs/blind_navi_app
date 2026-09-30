package kr.safecross.mobile.perception

/**
 * DepthObservation의 원시 상대 깊이맵을 진단용 근접도 지표로 요약하는 순수 함수 모음.
 *
 * best_depth_model_fp16.tflite(MiDaS 계열)의 출력은 프레임 내 상대값일 뿐 실제 미터 단위
 * 거리가 아니며, 프레임마다 스케일이 다를 수 있다. 따라서 절대 임계치로 "위험"을 판정할 수 없고,
 * 같은 프레임 안에서 "보행 경로 전방 하단 영역이 나머지 배경보다 상대적으로 얼마나 가까운가"만
 * 비교할 수 있다. 이 비율은 현재 발화/진동 경고를 트리거하지 않고 진단 HUD 노출 용도로만 쓴다.
 */
object DepthRoiAnalyzer {

    /**
     * 프레임 하단 중앙(보행 경로 전방) ROI의 평균 깊이값을 전체 프레임 평균 깊이값으로 나눈 비율.
     * 값이 클수록(>1) 전방 하단이 배경보다 상대적으로 가깝다는 의미(MiDaS 관례: 클수록 가까움).
     * depthMap이 없거나 전체 프레임이 0에 가까운 빈 장면이면 null.
     */
    fun nearPathProximityRatio(
        observation: DepthObservation,
        nearBandTopFraction: Float = 0.70f,
        nearBandCenterFraction: Float = 0.5f
    ): Float? {
        val map = observation.depthMap
        val width = observation.mapWidth
        val height = observation.mapHeight
        if (!observation.isAvailable || map == null || width <= 0 || height <= 0) return null

        val rowStart = (height * nearBandTopFraction).toInt().coerceIn(0, height - 1)
        val colMargin = ((width * (1f - nearBandCenterFraction)) / 2f).toInt()
        val colStart = colMargin.coerceIn(0, width - 1)
        val colEnd = (width - colMargin).coerceIn(colStart + 1, width)

        var nearSum = 0f
        var nearCount = 0
        for (y in rowStart until height) {
            val rowOffset = y * width
            for (x in colStart until colEnd) {
                nearSum += map[rowOffset + x]
                nearCount++
            }
        }
        if (nearCount == 0) return null
        val nearAvg = nearSum / nearCount

        var frameSum = 0f
        for (v in map) frameSum += v
        val frameAvg = frameSum / map.size

        if (frameAvg <= 1e-6f) return null
        return nearAvg / frameAvg
    }

    /**
     * 프레임 하단 중앙(보행 경로 전방) ROI 안에서 가장 가까운 단일 지점(최댓값, MiDaS 관례상
     * 클수록 가까움)을 전체 프레임 평균으로 나눈 비율.
     *
     * nearPathProximityRatio는 ROI 전체를 "평균"내기 때문에, ROI의 일부만 차지하는 작거나
     * 중간 크기의 물체(의자 등)의 신호가 주변 배경(바닥/벽)에 희석되어 거리 변화가 잘 안 잡힐
     * 수 있다. 이 함수는 ROI 안의 단일 최댓값만 보므로, 물체가 ROI의 일부만 차지해도 그 지점이
     * 만드는 "튀는 값"을 훨씬 민감하게 포착한다.
     */
    fun nearPathPeakRatio(
        observation: DepthObservation,
        nearBandTopFraction: Float = 0.70f,
        nearBandCenterFraction: Float = 0.5f
    ): Float? {
        val map = observation.depthMap
        val width = observation.mapWidth
        val height = observation.mapHeight
        if (!observation.isAvailable || map == null || width <= 0 || height <= 0) return null

        val rowStart = (height * nearBandTopFraction).toInt().coerceIn(0, height - 1)
        val colMargin = ((width * (1f - nearBandCenterFraction)) / 2f).toInt()
        val colStart = colMargin.coerceIn(0, width - 1)
        val colEnd = (width - colMargin).coerceIn(colStart + 1, width)

        var nearPeak = Float.NEGATIVE_INFINITY
        var nearCount = 0
        for (y in rowStart until height) {
            val rowOffset = y * width
            for (x in colStart until colEnd) {
                val v = map[rowOffset + x]
                if (v > nearPeak) nearPeak = v
                nearCount++
            }
        }
        if (nearCount == 0) return null

        var frameSum = 0f
        for (v in map) frameSum += v
        val frameAvg = frameSum / map.size

        if (frameAvg <= 1e-6f) return null
        return nearPeak / frameAvg
    }
}
