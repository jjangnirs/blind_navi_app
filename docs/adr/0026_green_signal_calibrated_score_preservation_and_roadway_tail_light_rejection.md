# ADR-026: 보행자 녹색 신호 캘리브레이션 점수 보존 및 하단 차량등 오인 차단, 음성 안내 즉시 선점(Preemption) 최적화

## 1. 배경 및 문제 상황
2026년 9월 26일 오후 실제 횡단보도 실측 로그(`perception_flight.log`, 6,286줄) 심층 포렌식 분석 결과, 사용자가 체감한 "초록 보행 신호 인식 및 음성 안내의 심한 지연"과 "신호 확인 불안정"의 구체적인 메커니즘과 통계가 밝혀졌습니다:

1. **로그 통계**:
   - 총 1,674개 프레임 판정 중 `GREEN_ESTIMATE` 579회, `RED_ESTIMATE` 542회, `GREEN_CANDIDATE` 245회, `UNKNOWN` 308회 기록.
   - 보행 녹색 신호가 정상 점등되어 있는 상황에서, **연속 녹색 누적 카운트(`consecutiveGreenCount`)가 1 이상에서 0으로 강제 초기화(Reset)된 횟수가 총 55회**에 달함.

2. **4대 근본 원인 규명**:
   - **원인 ① [`LocalVlmSignalVerifier`의 0.70x 감점 버그 (30회 누적 리셋)]**:
     - 보행 중 1프레임 손떨림 블러로 후보가 UNKNOWN으로 들어왔을 때, Verifier의 시간 롤링 큐는 최근 5프레임 중 4프레임 녹색을 근거로 `finalState = GREEN`을 정상 복원함.
     - 그러나 `finalState != candidate.state` 조건에 걸려 점수에 `0.70x` 감점 패널티가 적용됨 ($0.95 \rightarrow 0.66 \sim 0.69$).
     - `CrossingDecisionEngine`에서 `score < minCalibratedScore (0.88)` 검사에 걸려 `consecutiveGreenCount`가 0으로 즉시 초기화되고 `UNKNOWN (LOW_CALIBRATED_SCORE)`로 강락함.
     - 이 현상이 매초 발생하여 5프레임 연속 누적 조건이 30번이나 도중에 파괴됨.
   - **원인 ② [`CameraVisionSignalEstimator`의 하단 차로 차량 브레이크등 오인 및 공간 락 탈취 (17회 리셋)]**:
     - 도로 바닥면에 위치한 차량 후미등/브레이크등($Y > 0.48$, $Y_{red} > Y_{green} + 25\text{px}$)은 근거리 특성상 보행 신호등(원거리)보다 화소수가 큼.
     - 기존 코드에서 `isRedOverwhelming` (화소수 2배 이상) 조건이 `isLowerRoadwayRed`보다 우선되어 차량 브레이크등을 보행 적색으로 오인함.
     - 오인된 적색 좌표로 2D 공간 추적 락(`lastLockedCenterNorm`)이 이동하면서, 800ms 동안 기준점이 차량등(X=0.31, Y=0.51)으로 굳어져 실제 중앙 보행 신호등(X=0.50, Y=0.43)이 배제되는 15초 락 탈취 루프 발생.
   - **원인 ③ [`CrossingDecisionEngine`의 순간 점수 저하 시 하드 리셋]**:
     - 일시적 노이즈 시 점진적 감쇄(Decay) 대신 `consecutiveGreenCount = 0`으로 즉시 날려버려 누적 진행도가 전소됨.
   - **원인 ④ [`GuidanceArbiter`의 음성 안내 선점(Preemption) 잠금 현상]**:
     - `LOW_CALIBRATED_SCORE` 발생 시 `CROSSING` 등급의 "신호 감지 신뢰도가 충분하지 않습니다."가 발화됨.
     - 직후 녹색 신호가 확정되어도 `GuidanceArbiter`가 적색 발화(`signal_decision_red`)만 선점 취소하도록 되어 있어, UNKNOWN 멘트가 끝날 때까지(약 2.5초) 녹색 보행 안내 발화가 차단·지연됨.

---

## 2. 의사결정 및 기술적 해결책

### 1) [LocalVlmSignalVerifier] 시간적 검증 녹색 신호의 신뢰도 보존 (Score $\ge 0.90$)
- 시간적 평활화로 `finalState == GREEN`이 입증된 경우(최근 5프레임 중 60% 이상 녹색 지지), 일시적 블러 프레임이어도 신뢰도 점수를 `0.92f ~ 0.97f`로 확고히 유지.
- `minCalibratedScore (0.88)` 미만으로 추락하는 `LOW_CALIBRATED_SCORE` 리셋 루프를 원천 차단.

### 2) [CameraVisionSignalEstimator] 하단 차로 차량등 배제 및 2D 공간 락 탈취 방지
- **하단 차로 적색광 절대 배제**: 한국 보행신호등은 상단 적색, 하단 녹색이므로 보행 녹색등보다 25px 이상 아래쪽에 위치한 적색($Y > Y_{green} + 25\text{px}$)은 차량 브레이크등/후미등이므로 화소수 크기와 상관없이 절대 보행 적색이 될 수 없도록 보장.
- **공간 락 탈취 방지 (`isLockHijack`)**: 녹색 신호 추적 중 도로 바닥($Y > 0.48$)이나 중심에서 0.12 이상 점프한 측면 적색등이 관측되더라도 타깃 기준점(`lastLockedCenterNorm`)을 덮어쓰지 않고 중앙 보행등에 락을 견고히 유지.

### 3) [CrossingDecisionEngine] 순간 신뢰도 저하 시 점진적 감쇄 (Graceful Decay)
- `targetSignal.score < minCalibratedScore` 발생 시 0으로 즉시 초기화하지 않고 `(consecutiveGreenCount - 1).coerceAtLeast(0)`으로 1프레임씩 점진 감쇄하여 1프레임 노이즈 후 즉시 연속성을 회복하도록 개선.
- UNKNOWN 상태에서의 반복적인 중복 안내 문구 억제.

### 4) [GuidanceArbiter] 녹색 신호 인입 시 비녹색 전체 즉시 선점(Preemption) 및 큐 정화
- 녹색 보행 신호(`signal_decision_green`) 인입 시, 현재 발화 중인 음성이 적색뿐만 아니라 UNKNOWN, 횡단보도 진입 안내 등 비녹색 계열이면 즉시 발화를 중단하고 녹색 안내를 즉시 선점 재생(`PREEMPT_AND_PLAY`).
- 대기 큐에 남아있는 낡은 적색/UNKNOWN 신호 메시지를 일괄 영구 폐기(`droppedMessageIds`)하여 녹색 보행 중 엉뚱한 적색 멘트가 튀어나오는 위험 원천 방지.

---

## 3. 검증 결과
1. **단위 테스트 (100% ALL PASS)**:
   - `PerceptionRobustnessTest.testTemporallyVerifiedGreenMaintainsCalibratedScoreDuringBlurFrame` 통과
   - `CameraVisionSignalEstimatorTest.testPedestrianGreenNotVetoedByLowerRoadwayHugeVehicleTailLight` 통과
   - `GuidanceArbiterTest.testGreenGuidancePreemptsCurrentlySpeakingUnknownGuidanceAndPurgesStaleQueue` 통과
2. **빌드 및 배포**:
   - `app-debug-0927-v26.apk` 빌드 및 기기 전송 완료.
