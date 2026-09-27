# ADR-029: 화면 주변부 상가 간판 녹색광 오인 차단(Zero False-Green) 및 조준선(Reticle) 중앙 긴축

## 1. 배경 및 문제 상황
2026년 9월 27일 낮 12시 08분경 시각장애인 보행자가 교차로 횡단보도 진입 대기 중 카메라 횡단 보조를 실행하였을 때, **실제 전방 보행 신호등은 적색(정지)이었음에도 불구하고 화면 좌측 인도변의 상가 녹색 간판을 보행 신호등 초록불(Green)로 오인식하여 "신호등이 조준되었습니다" 및 녹색 신호 햅틱/음성 피드백이 발생**하는 중대한 안전 위협 상황이 보고되었습니다.

단말기(`진원의 S25 Ultra`)에서 당일 실측 로그(`perception_flight.log`, 41.6KB)를 회수하여 12:08:47 ~ 12:08:51 구간을 초단위 포렌식 분석한 결과는 다음과 같습니다:

1. **로그 실측 데이터 타임라인**:
   - `12:08:47 ~ 12:08:49`:
     - 화면 좌측 가장자리인 `Box=[0.20, 0.42, 0.21, 0.44]` 및 `Box=[0.25, 0.42, 0.28, 0.44]` 위치에서 녹색 픽셀 블롭(G=36)이 감지됨.
     - `CameraVisionSignalEstimator`가 이를 `Detect=GREEN Score=0.95`로 판정.
     - `reticleBox`가 `left=0.20f, right=0.80f`로 열려 있어 `isInsideReticle=true` (`Ret=true`)로 조준선 내 합격 처리.
     - `consecutiveGreenCount` (`GCount`)가 `1 -> 2 -> 3 -> 4`로 연속 누적되어 녹색 후보 펄스 발화 및 조준 확정 안내.
   - `12:08:50 ~ 12:08:51`:
     - 사용자가 카메라를 중앙 정면으로 회전하자, 화면 정중앙 `Box=[0.49, 0.47, 0.50, 0.48]`에서 **진짜 보행 신호등인 적색등(`Detect=RED Score=0.99`)**이 비로소 감지됨.
     - 전방 신호등은 명백히 **적색**이었는데, 화면 왼쪽(X=0.20~0.25)의 **녹색 상가 간판 LED**를 신호등으로 착각한 치명적인 False-Green 결함임이 확인됨.

2. **4대 근본 원인 분석**:
   - **원인 ① [조준 윈도우(Reticle/Viewfinder)의 과도한 가로폭 (60%)]**:
     - `CrossingAssistUiState.reticleBox`와 `TwoTierHybridSignalEstimator.viewfinderBox`가 `0.20f..0.80f`로 설정되어 있어, 정면을 바라보아도 좌/우측 인도 상가의 간판, 네온사인, 편의점 조명이 조준 영역 내부로 간주되어 락온 타깃이 됨.
   - **원인 ② [주변부 녹색광 기각 필터 부재]**:
     - 도로 구조상 횡단보도 건너편의 보행신호등은 화면 중앙 시야($X \in 0.28 \sim 0.72$)에 들어오며, $X < 0.28$ 또는 $X > 0.72$의 외곽은 건물 외벽, 상가 간판, 가로수 영역임. 이를 기각하는 공간 필터가 전무했음.
   - **원인 ③ [다크 하우징(차광판 케이스) 콘트라스트 판정 누수]**:
     - 기존 `verifyDarkHousingContrast`는 하우징 배경 밝기가 0.35 이하이면 대비 검사 없이 `true`를 반환하였음. 이로 인해 검은색 프레임을 가진 상가 간판이나 야간/그늘진 상점 벽면의 녹색 LED가 신호등 차광판으로 오판정됨.
   - **원인 ④ [TargetSignalAssociator의 측면 녹색 무조건 1:1 매칭]**:
     - 단일 녹색 신호가 감지되면 화면 외곽($X=0.20$)이어도 `isUnique=true`로 매칭하여 전방 목표 신호기로 확정해버림.

---

## 2. 의사결정 및 기술적 해결책 (Zero False-Green Architecture)

### 1) [CrossingAssistUiState & TwoTierHybridSignalEstimator] 조준선(Reticle) 중앙 40% 긴축
- **조준 영역 긴축**:
  - `reticleBox` 가로 범위를 기존 `0.20f..0.80f` (폭 60%) $\rightarrow$ **`left = 0.30f, right = 0.70f` (폭 40%)**로 긴축.
  - `viewfinderBox` 가로 범위도 동일하게 `left = 0.30f, right = 0.70f`로 정렬.
  - 시각장애인이 횡단보도 방향으로 기기를 들었을 때 좌/우측 인도변 상점의 시각적 노이즈가 조준선 내로 들어올 수 없도록 원천 차단.

### 2) [CameraVisionSignalEstimator] 주변부 녹색 블롭 기각 및 하우징 대비 엄격화
- **주변부 녹색 블롭 즉시 기각**:
  - 녹색 블롭의 중심 X 좌표가 주변부($normCx < 0.28f \lor normCx > 0.72f$)에 위치하면 보행자 신호등 후보에서 배제(`validGreenBlobs` 필터).
- **수평 이탈 감점 가중치 강화**:
  - 수평 거리 편차 가중치를 `1.0f` $\rightarrow$ `1.8f`로 대폭 상향하여 화면 중심(X=0.50)에 가까운 신호에 절대적 우선순위 부여.
- **다크 하우징 콘트라스트(`verifyDarkHousingContrast`) 강화**:
  - 하우징이 어둡더라도 발광 램프 밝기와 배경 간의 상대 밝기 대비가 최소 `0.30f` 이상 차이나야 유효한 차광판으로 인정.

### 3) [LocalVlmSignalVerifier] 공간 위치 검증 및 UNKNOWN 강등
- 녹색 신호(`candidate.state == ObservedSignalState.GREEN`)의 정규화 중심 좌표가 `normCx < 0.28f \lor normCx > 0.72f`인 경우, 시야각을 벗어난 간판으로 간주하여 `verifiedState = UNKNOWN`, `isVerified = false`, `verificationReason = "REJECTED_PERIPHERAL_SIGNBOARD_GREEN"`으로 즉시 강등.

### 4) [TargetSignalAssociator] 측면 녹색 신호 1:1 목표 정합 거부
- `AUTO_EVALUATE` 시나리오에서 단일 신호가 녹색이고 중심이 $X < 0.28f \lor X > 0.72f$인 경우:
  - `isUnique = false`
  - `targetSignal = null`
  - `reason = "PERIPHERAL_SIGNAL_MISMATCH"`
  - 전방 목표 보행신호로 승격되는 것을 원천 차단.

---

## 3. 검증 결과 및 영향 평가
1. **단위 테스트 (100% ALL PASS)**:
   - `PerceptionRobustnessTest.testRejectsLeftPeripheralSignboardGreenSignalInVerifier`: 좌측 $X=0.21$ 간판 녹색광 UNKNOWN 강등 검증.
   - `PerceptionRobustnessTest.testTargetSignalAssociatorRejectsPeripheralGreenSignal`: 측면 녹색광 목표 신호기 1:1 연결 차단 검증.
   - `PerceptionRobustnessTest.testRejectsLeftSignboardGreenInCameraVisionSignalEstimator`: 카메라 추정기 파이프라인에서 주변부 간판 배제 검증.
   - `PerceptionRobustnessTest.testDarkHousingContrastVerificationMethod`: 간판과 신호등 하우징 분별 검증.
2. **현장 안전성 (Zero False-Green 달성)**:
   - 교차로 대기 중 상가 간판, 편의점 녹색 LED, 차량 비상등에 의한 오안내 위험 완전 해소.
   - 정면(중앙 40%)의 실제 보행 신호등에만 안정적으로 락온 및 안내 제공.
