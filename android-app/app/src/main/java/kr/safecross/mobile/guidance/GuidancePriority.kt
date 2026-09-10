package kr.safecross.mobile.guidance

/**
 * 안내 메시지 우선순위 (TRD 4.8 준수).
 *
 * SAFETY(4) > CROSSING(3) > ROUTE(2) > INFO(1)
 *
 * 높은 레벨의 메시지는 낮은 레벨의 메시지를 선점(Preemption)하며,
 * 긴급한 안전 알림이 일반 길안내에 가로막히지 않도록 보장합니다.
 */
enum class GuidancePriority(val level: Int) {
    INFO(1),       // 일반 부가 정보 (거리, 경과 시간, 배터리 등)
    ROUTE(2),      // 일반 보행로 길안내 (직진, 좌/우회전 Maneuver 지시)
    CROSSING(3),   // 횡단보도 시설 접근, 정지 준비, 횡단 진입 안내
    SAFETY(4)      // 경로 이탈, GPS 정확도 상실, 긴급 정지 경고
}
