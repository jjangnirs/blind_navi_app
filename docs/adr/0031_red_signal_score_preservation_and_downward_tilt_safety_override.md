# ADR-0031: 적색 신호 평활화 신뢰도 보존 및 한손 하향 각도(-65°) 안전 우선권 보장

## 1. 배경 및 문제 상황 (Context & Problem Statement)
- **일시**: 2026년 9월 27일 13시 45분경
- **현상**: 사용자가 보행 대기 중 한손으로 스마트폰을 잡고 신호등을 바라보며 대기할 때 화면 중앙에는 빨간색 신호등 타겟 박스가 잡혀있으나, 음성 안내가 돌연 중단되고 "스마트폰을 올바른 각도로 들어주세요" 각도 경고만 나오거나 침묵하는 화면-음성 괴리 발생.
- **포렌식 분석 (13:45:02 ~ 13:45:15 365개 프레임 전수 조사)**:
  1. **초반 6초 (13:45:02~07)**: 전방 수평(Pitch -15°)에서 적색 신뢰도 0.99로 `RED_ESTIMATE` 확정 및 "적색 신호입니다. 대기하세요" 정상 발화.
  2. **후반 7초 (13:45:08~15)**: 사용자가 대기하며 팔이 자연스럽게 내려가 스마트폰 Pitch가 **-59.4°**로 하향됨.
  3. `LocalVlmSignalVerifier`: 녹색 신호와 달리 적색 신호는 평활화 시 신뢰도 유지 로직이 누락되어, 블러/순간 노이즈 시 `boostedScore = (candidate.score * 0.70f).coerceAtLeast(0.30f)`에 의해 신호 점수가 **0.31로 폭락**.
  4. `CrossingDecisionEngine`: 적색 안전 우선권 임계치(`score >= 0.90f`)가 깨지며 `POOR_DEVICE_TILT`로 인해 강제로 `UNKNOWN` 강등.
  5. 화면에는 빨간색 락온 박스가 그려져 있는데, 음성은 "각도를 들어주세요" 경고만 나오는 시각-청각 충돌 발생.

## 2. 의사결정 (Decision)

### (1) `LocalVlmSignalVerifier` 적색 평활화 신뢰도 보존
- 최근 이력 중 적색 비율이 60% 이상인 경우, 프레임이 일시적으로 흔들리거나 신호 점수가 낮아져도 신뢰도를 **0.94f ~ 0.99f**로 유지.
- 불필요한 `LOW_CALIBRATED_SCORE` 및 각도 불량 강등 방지.

### (2) `CrossingDecisionEngine` 적색 안전 우선권 각도 마진 완화
- `isDefiniteRed` 조건을 `candidateSignal.state == RED && candidateSignal.score >= 0.75f`로 현실화.
- 적색 확정 시 허용 Pitch 한계를 기존 -38°에서 **-65°까지 확장**. 한손으로 스마트폰을 쥐고 편안하게 대기하는 각도에서도 적색 안전 경고를 끊김 없이 유지.

### (3) `CrossingAssistScreen` 화면 시각 피드백 일치
- 미확정(UNKNOWN) 상태에서는 바운딩 박스를 반투명(alpha 0.45)으로 표시하여 확정 상태와 구분.
- 뱃지 텍스트를 "⚠️ 각도 조정 필요" 대신 "🔴 적색 확인 중..."으로 명확히 하여 혼선 제거.

## 3. 결과 및 검증 (Consequences & Verification)
- 단위 테스트: `testDefiniteRedSignalMaintainsSafetyAtDownwardPitchMinus60Degrees()` 포함 199개 단위 테스트 100% 통과.
- 패치 빌드: `app-debug-0927-v31.apk` (176 MB) Galaxy S25 Ultra 단말기 `Download` 폴더 전송 완료.
