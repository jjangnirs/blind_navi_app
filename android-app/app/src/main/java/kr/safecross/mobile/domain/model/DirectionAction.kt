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

            // 1. facilityType 우선 확인
            if (maneuver.facilityType == "횡단보도" || maneuver.facilityType?.contains("횡단보도") == true) {
                return CROSSWALK
            }
            if (maneuver.facilityType?.contains("육교") == true) return OVERPASS
            if (maneuver.facilityType?.contains("지하보도") == true) return UNDERPASS
            if (maneuver.facilityType?.contains("계단") == true) return STAIRS
            if (maneuver.facilityType?.contains("엘리베이터") == true) return ELEVATOR
            if (maneuver.facilityType?.contains("경사로") == true) return RAMP

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

            // 3. instruction 텍스트 키워드 기반 판정
            val text = maneuver.instruction
            return when {
                text.contains("횡단보도") -> CROSSWALK
                text.contains("유턴") -> UTURN
                text.contains("좌회전") -> {
                    if (text.contains("약간") || text.contains("10시") || text.contains("8시")) SLIGHT_LEFT else LEFT
                }
                text.contains("우회전") -> {
                    if (text.contains("약간") || text.contains("2시") || text.contains("4시")) SLIGHT_RIGHT else RIGHT
                }
                text.contains("직진") -> STRAIGHT
                text.contains("육교") -> OVERPASS
                text.contains("지하보도") -> UNDERPASS
                text.contains("계단") -> STAIRS
                text.contains("엘리베이터") -> ELEVATOR
                text.contains("도착") || text.contains("목적지") -> DESTINATION
                else -> STRAIGHT
            }
        }
    }
}
