package kr.safecross.mobile.domain.model

/**
 * 보행 내비게이션 방향 분기점 동작 유형.
 * 저시력자를 위한 시각적 표시기 및 음성 안내에서 공통으로 사용됩니다.
 */
enum class DirectionAction(
    val label: String,
    val shortLabel: String,
    val symbolChar: String
) {
    STRAIGHT(label = "직진", shortLabel = "직진", symbolChar = "↑"),
    LEFT(label = "좌회전", shortLabel = "좌회전", symbolChar = "↰"),
    RIGHT(label = "우회전", shortLabel = "우회전", symbolChar = "↱"),
    SLIGHT_LEFT(label = "약간 좌회전", shortLabel = "10시/8시 방향", symbolChar = "↖"),
    SLIGHT_RIGHT(label = "약간 우회전", shortLabel = "2시/4시 방향", symbolChar = "↗"),
    CROSSWALK(label = "횡단보도 건너기", shortLabel = "횡단보도", symbolChar = "🚸"),
    UTURN(label = "유턴", shortLabel = "유턴", symbolChar = "↶"),
    OVERPASS(label = "육교 건너기", shortLabel = "육교", symbolChar = "⛩"),
    UNDERPASS(label = "지하보도 이용", shortLabel = "지하보도", symbolChar = "🚇"),
    STAIRS(label = "계단 이용", shortLabel = "계단", symbolChar = "🪜"),
    ELEVATOR(label = "엘리베이터 이용", shortLabel = "엘리베이터", symbolChar = "🛗"),
    RAMP(label = "경사로 이용", shortLabel = "경사로", symbolChar = "♿"),
    DESTINATION(label = "목적지 도착", shortLabel = "도착", symbolChar = "🏁");

    companion object {
        /**
         * Maneuver의 turnType, facilityType, instruction을 종합 분석하여 DirectionAction을 추출합니다.
         */
        fun fromManeuver(maneuver: Maneuver?): DirectionAction {
            if (maneuver == null) return DESTINATION

            // 1. facilityType 우선 확인 (정확한 시설물 일치)
            val ft = maneuver.facilityType?.trim() ?: ""
            if (ft == "횡단보도" || ft.contains("횡단보도")) {
                return CROSSWALK
            }
            if (ft == "육교" || ft == "보도육교") return OVERPASS
            if (ft == "지하보도") return UNDERPASS
            if (ft == "계단") return STAIRS
            if (ft == "엘리베이터") return ELEVATOR
            if (ft == "경사로") return RAMP

            // 2. TMAP turnType 코드 확인
            when (maneuver.turnType) {
                11 -> return STRAIGHT
                12 -> return LEFT
                13 -> return RIGHT
                14 -> return UTURN
                16, 17 -> return SLIGHT_LEFT
                18, 19 -> return SLIGHT_RIGHT
                125 -> return OVERPASS
                126 -> return UNDERPASS
                127 -> return STAIRS
                128 -> return RAMP
                129 -> return ELEVATOR
                201 -> return DESTINATION
                in 211..217 -> return CROSSWALK
            }

            // 3. instruction 텍스트 분석 (랜드마크 "OO육교 방면" 오탐 방지)
            val text = maneuver.instruction.trim()

            // 3-1. 횡단보도/목적지 우선 판정
            if (text.contains("횡단보도")) return CROSSWALK
            if (text.contains("목적지") || text.contains("도착")) return DESTINATION

            // 3-2. 회전 동작 키워드가 있으면 지명/랜드마크("육교 방면")보다 회전 동작을 우선
            if (text.contains("좌회전")) {
                return if (text.contains("약간") || text.contains("10시") || text.contains("8시")) SLIGHT_LEFT else LEFT
            }
            if (text.contains("우회전")) {
                return if (text.contains("약간") || text.contains("2시") || text.contains("4시")) SLIGHT_RIGHT else RIGHT
            }
            if (text.contains("유턴")) return UTURN

            // 3-3. 시설 이용 동작(진입, 이용, 건너기)이 명시된 경우만 시설 액션으로 판정
            val isFacilityAction = text.contains("이용") || text.contains("진입") ||
                    text.contains("건너") || text.contains("올라") || text.contains("내려")
            if (text.contains("육교") && isFacilityAction) return OVERPASS
            if (text.contains("지하보도") && isFacilityAction) return UNDERPASS
            if (text.contains("계단") && isFacilityAction) return STAIRS
            if (text.contains("엘리베이터") && isFacilityAction) return ELEVATOR

            // 3-4. 단순 직진 또는 기본 진행
            return STRAIGHT
        }
    }
}
