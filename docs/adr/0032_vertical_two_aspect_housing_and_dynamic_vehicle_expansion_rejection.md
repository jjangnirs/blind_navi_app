# ADR-0032: 보행 신호등 세로 2구 하우징 검증 및 노면 차량/스케일 팽창 동적 기각 (Zero False-Green 강화)

## 1. 배경 및 문제 상황 (Context & Problem Statement)
- **배경**: 이전 비전 파이프라인 분석 결과, 단순 색상 계산 오류가 아닌 다음과 같은 구조적·의미론적 한계(Semantic Limitations)가 확인됨:
  1. **신호등 전체 하우징 기하 구조 검증 부재**: 개별 발광체 테두리의 다크 하우징만 검사하므로, 주변이 어두운 상가 간판이나 네온사인을 걸러내지 못함.
  2. **정면 접근 차량 및 노면 아스팔트 광원 필터 부재**: 수평 횡단 주행 차량만 기각하고, 정면에서 다가오는 차량(정면 접근으로 2D 중심 변위가 작은 경우)이나 아스팔트 바닥면의 차량 후미등/전조등을 신호등으로 오인할 위험 존재.
  3. **딥러닝 미검출 시 뷰파인더 폴백의 녹색 판정 취약성**: LiteRT 검출 실패 시 뷰파인더 내부에서 완벽하지 않은 녹색 후보가 통과될 수 있었음.

## 2. 의사결정 및 구현 (Decision & Implementation)

### (1) `CameraVisionSignalEstimator`: 한국 표준 세로 2구(2-Aspect) 하우징 검증
- **`verifyVerticalTwoAspectHousing()` 구현**:
  - 한국 보행신호등은 상단 적색, 하단 녹색의 세로 2구 구조로 표준화되어 있음.
  - **GREEN 판정 시**: 램프 크기($W \times H$) 기준 바로 위쪽($Y: \text{minY} - 1.4H \sim \text{minY} - 0.3H$)에는 반드시 소등된 상단 적색 렌즈와 차광판(Housing Hood)이 존재해야 함. 이 상단 영역이 어둡지 않고 밝은 벽면이나 하늘($V > 0.72$ 또는 Contrast $< 0.15$)이면 독립 간판/조명으로 즉시 기각.
  - **RED 판정 시**: 램프 바로 아래쪽에 소등된 하단 녹색 렌즈 슬롯 존재 여부 검증.

### (2) `LocalVlmSignalVerifier`: 노면 고도 및 스케일 팽창(Scale Expansion) 기각
- **노면 아스팔트 고도 게이트 (`REJECTED_ROADWAY_GROUND_PLANE`)**:
  - 횡단보도 신호등은 지상 2.5m 이상 높이에 설치되어 카메라 기준 상단/눈높이에 위치함.
  - 아스팔트 노면 바닥면($CY > 0.58f$, $\text{Top} > 0.52f$)에 위치한 광원은 차량 후미등/전조등으로 확정 기각.
- **정면 접근 차량 기각 (`REJECTED_APPROACHING_VEHICLE_SCALE_EXPANSION`)**:
  - 정면에서 다가오는 차량의 전조등/미등은 중심점 이동은 작으나 면적이 단시간(0.03~0.4초)에 2.4배 이상 급팽창함. 면적 급팽창 후보를 차량으로 판정하여 즉시 기각.

### (3) `TwoTierHybridSignalEstimator`: 뷰파인더 폴백 모드 Zero False-Green 보장
- 딥러닝 객체 검출 모델이 특정되지 않은 뷰파인더 폴백 상태에서는 녹색 신뢰도가 0.90 이상으로 완벽 검증된 경우에만 최종 GREEN을 승인하며, 그렇지 않으면 `UNKNOWN`으로 안전 차단.

### (4) `OpenCvSignalDetector`: 노면 광원 기각 통합
- OpenCV 원형도 검출 시에도 $Y > 0.52f$ 바닥 노면 영역의 원형 광원은 신호등 후보에서 배제.

## 3. 결과 및 검증 (Consequences & Verification)
- 단위 테스트 3종 추가:
  - `testRejectsRoadwayGroundPlaneVehicleLight()` 통과.
  - `testRejectsApproachingVehicleScaleExpansion()` 통과.
  - `testRejectsIsolatedGreenSignWithoutCompanionHousing()` 통과.
- 전체 202개 단위 테스트 100% 통과 (`BUILD SUCCESSFUL in 34s`).
- 최신 APK: `app-debug-0927-v32.apk` (176 MB) Galaxy S25 Ultra 단말기 `Download` 폴더 전송 완료.
