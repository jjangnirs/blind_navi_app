package kr.safecross.mobile.domain.model

enum class WalkingMode(
    val label: String,
    val description: String,
    val safetyGuidance: String
) {
    IDLE(
        label = "대기 상태",
        description = "보행 안내 대기 중입니다.",
        safetyGuidance = "목적지를 선택하고 경로 주의사항을 확인하세요."
    ),
    WALKING(
        label = "일반 보행 중",
        description = "안전한 보행로를 따라 이동 중입니다.",
        safetyGuidance = "점자블록과 주변 소리에 주의하며 직진하세요."
    ),
    APPROACHING_CROSSING(
        label = "횡단보도 접근 중",
        description = "약 30미터 전방에 횡단보도가 있습니다.",
        safetyGuidance = "걸음을 늦추고 볼라드 및 정지 점자블록을 감지하세요. 음향신호기 유무를 확인하세요."
    ),
    CROSSING(
        label = "횡단보도 건너는 중",
        description = "현재 횡단보도를 건너고 있습니다.",
        safetyGuidance = "좌우 차량 소리를 청취하며 횡단보도를 빠르게 건너세요. 턱낮춤 단차에 주의하세요."
    )
}
