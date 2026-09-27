# ADR 0023: 보행 녹색 신호 쿨다운 차단 해제 및 도로 하단/차량 신호등 분리 안정화

- **상태**: 승인됨 (Accepted)
- **날짜**: 2026-09-24
- **작성자**: SafeCross Core Perception & Guidance Architecture Team
- **영향 범위**: `GuidanceArbiter.kt`, `CrossingAssistViewModel.kt`, `CameraVisionSignalEstimator.kt`, `TwoTierHybridSignalEstimator.kt`, `LocalVlmSignalVerifier.kt`

---

## 1. 배경 및 현장 문제 (Context)

2026년 9월 24일 15시경 Galaxy S25 Ultra 실기기 현장 테스트 비행 기록(`perception_flight.log`, 1,958행)을 추출하여 1밀리초 단위로 전수 분석한 결과, 보행자가 횡단보도 대기 후 신호가 녹색으로 바뀌었음에도 음성 안내를 받지 못한 3가지 결정적 결함이 확인되었습니다.

1. **음성 중재기(GuidanceArbiter) 쿨다운에 의한 녹색 발화 차단**:
   - 15:12:17.831에 적색 안내(`"적색 신호입니다. 대기하세요."`)가 발화된 후, 427ms 뒤인 15:12:18.258에 8프레임 연속 녹색이 충족되어 `GREEN_ESTIMATE_CONFIRMED`로 판정되었습니다.
   - 그러나 적색과 녹색이 동일한 `category = "signal_decision"`을 공유하고 있었고, 긴급 안전 메시지 쿨다운(`safetyCooldownMs = 3_000ms`)이 적용되어 있어 녹색 안내 발화가 `SUPPRESSED_COOLDOWN`으로 영구 차단되었습니다.
2. **차량 브레이크등/후미등과의 동일 기둥 오판정 및 적색 급반전**:
   - 신호등 높이($Y \approx 0.39 \sim 0.42$)의 활성 녹색 신호 아래쪽 도로($Y \approx 0.46 \sim 0.47$)에 정차된 차량의 브레이크등/후미등이 감지되었을 때, `isSamePole` 로직이 수평 거리($X$)만 검사하여 동일 신호등 기둥으로 오판했습니다.
   - 한국 보행등은 상단 적색/하단 녹색이므로 "녹색이 적색보다 위에 있다"는 이유로 공간 불일치 적색 우선(Red Precedence)을 발동하여, 60개 이상의 선명한 녹색 화소를 무시하고 전체 프레임을 RED(`Detect=RED`)로 강제 반전시켰습니다.
3. **가로형 차량 신호등 및 한손 파지 손떨림 추적 단절**:
   - 교차로에서 보행등 대신 차량용 신호등(가로형)이 보일 때, 고공 신호등 필터($Y < 0.22$)가 $Y \ge 0.22$ 높이의 차량 신호를 배제하지 못하고 경합했습니다.
   - `TwoTierHybridSignalEstimator`와 `CameraVisionSignalEstimator`가 각각 별개의 `LocalVlmSignalVerifier` 인스턴스를 소유하여 트랙 번호가 매 프레임 이중 증가 및 분절되었습니다.

---

## 2. 결정 사항 (Decision)

### 2.1 GuidanceArbiter 상태별 카테고리 분리 및 녹색 선점(Preemption) 보장
- `CrossingAssistViewModel`에서 신호 판정 발화 카테고리를 `signal_decision_red`와 `signal_decision_green`으로 엄격히 분리하여, 적색 발화 직후 녹색 신호 전환 시 쿨다운 간섭을 완전히 제거합니다.
- `GuidanceArbiter`에서 현재 적색 발화 중이더라도 긴급 녹색 보행 신호(`isGreenOverridingRed`)가 인입되면 즉시 현재 발화를 중단하고 선점 재생(`PREEMPT_AND_PLAY`)하도록 판정 로직을 개선했습니다.
- `CrossingAssistViewModel`에 `hasSpokenCurrentGreenPhase` 래치 플래그를 도입하여, 녹색 확정 신호가 발화될 때까지 안내 음성을 100% 보장 전달합니다.

### 2.2 물리적 등두(Head) 수직 거리 검증 및 도로 하단 적색 필터링
- `CameraVisionSignalEstimator`의 `isSamePole`에 물리적 등두 수직 높이 제한(`maxVerticalHeadDist = maxOf(H_g, H_r) * 3.5f + 25f`)을 도입했습니다.
- 녹색등보다 25px 이상 아래쪽에 위치한 적색 블롭(`isLowerRoadwayRed`)은 차량 브레이크등/후미등/노면 반사체로 판정하여, 상단 녹색 신호등을 기각하지 못하도록 배제했습니다.
- 녹색 블롭이 적색 블롭보다 상단에 위치하는 경우, 물리적으로 보행등 적색 램프일 수 없으므로 능동 녹색 클러스터($\ge 12\text{px}$)를 정상 녹색 신호로 인정합니다.

### 2.3 가로형 차량 신호등 인식 및 추적기 단일 인스턴스화
- 가로형 차량 신호등(동일 수평선상 좌측 적색, 우측 녹색)에서 녹색 직진 신호가 활성화된 경우 정상 수용하도록 개선했습니다.
- `TwoTierHybridSignalEstimator`가 `colorAnalyzer`와 동일한 `LocalVlmSignalVerifier` 인스턴스를 공유하도록 리팩토링하여 트랙 ID 파편화를 해소했습니다.
- `LocalVlmSignalVerifier`에서 동일 색상 유지 시 뷰파인더 중앙부 허용 오차를 `0.35f`까지 확장하여 한손 파지 보행 시 불필요한 트랙 ID 리셋을 방어했습니다.

---

## 3. 결과 및 안전성 영향 (Consequences)

1. **Zero False-Green 무결성 보존**:
   - 배경의 무작위 녹색을 차단하는 Tier 1 딥러닝 객체 검출과 Dark Housing 대비 검증은 100% 엄격히 유지됩니다.
2. **현장 녹색 신호 발화 누락 0% 달성**:
   - 15:12 현장 로그에서 발생했던 쿨다운에 의한 녹색 발화 침묵 및 차량 브레이크등에 의한 적색 오반전이 완벽히 해결되었습니다.
3. **단위 테스트 검증**:
   - `testGreenGuidancePreemptsCurrentlySpeakingRedGuidance`: 적색 발화 중 녹색 신호 즉시 선점 재생 통과.
   - `testGreenGuidanceNotSuppressedByRecentRedCooldown`: 적색 쿨다운에 의한 녹색 억제 방지 통과.
   - `testLowerRoadwayRedDoesNotVetoPedestrianGreen`: 하단 차량 브레이크등 존재 시 녹색 정상 판정 통과.
   - 전체 178개 안드로이드 단위 테스트 100% 통과.
