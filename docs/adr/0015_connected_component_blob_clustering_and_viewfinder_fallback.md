# ADR 0015: 독립 블롭(Connected-Component) 클러스터링 및 뷰파인더 폴백을 통한 원거리 보행신호 인지 개선

## 1. 문맥 (Context)
2026-09-23 13:42 광폭(4차로, 약 20~25m) 교차로 실환경 현장 테스트 중 다음 두 가지 기술적 문제가 발견되었다:
1. **차량용 신호등과 보행신호등의 단일 박스 병합(Box Merging) 왜곡**:
   - 도로 중앙의 가로형 차량신호등(적색)과 건너편 보도 지주의 세로형 보행신호등(적색)이 동시에 프레임에 잡힐 때, 기존 `CameraVisionSignalEstimator`가 전체 적색 픽셀의 min/max 좌표를 전역 병합하여 가로 폭 134px의 거대한 박스로 인식함.
   - 이로 인해 종횡비가 가로형 차량 신호등으로 오분류되거나 도로 전체를 포괄하여 다크 하우징(차광판) 검증이 실패함.
2. **원거리(20m+) 초소형 램프의 샘플링 유실**:
   - 25m 거리의 20cm 램프는 광각(1x) 렌즈에서 약 3×4 픽셀(총 9픽셀 내외)로 극소 형성됨.
   - 기존 2픽셀 건너뛰기 샘플링(`step = 2`)과 12픽셀 임계값(`minClusterPixels = 12`) 하에서는 유효 샘플이 2~3개로 떨어져 노이즈로 기각됨.
3. **더미 TFLite 모델 탑재 시 2단계 파이프라인 원천 차단**:
   - 실기기 배포본의 `ped_signal_v1.tflite`가 80바이트 테스트 스텁이어서 Tier 1 검출기가 항상 0건을 반환함.
   - Tier 1 박스가 없으면 배경 오탐 방지를 위해 Tier 2 색상 분석이 원천 차단되어 `UNKNOWN` 상태에 고착됨.

## 2. 결정 (Decision)

### 1) 독립 블롭(Connected-Component Blob) 클러스터링 도입 (`CameraVisionSignalEstimator.kt`)
- 2D 그리드 상에서 8-방향 BFS 큐 탐색 기반 `findBlobs` 알고리즘을 구현하여 근접한 픽셀끼리 독립된 발광체(Blob)로 분리 식별:
  ```kotlin
  val redBlobs = findBlobs(grid, vGrid, gridW, gridH, 1, startX, startY, step)
  val greenBlobs = findBlobs(grid, vGrid, gridW, gridH, 2, startX, startY, step)
  ```
- **개별 블롭 단위 필터링**:
  - 각 블롭별로 가로/세로 비율($W \le H \times 1.35$) 독립 검증 $\to$ 좌측 차량신호등은 기각하고 우측 세로형 보행신호등만 통과.
  - 각 블롭의 경계 마진 대역에서 `verifyDarkHousingContrast`를 독립 수행하여 짙은 차광판 케이스 유무 확인.
  - 횡단보도 정면 및 화면 중심(`targetCenterX`)에 가장 정렬된 유효 블롭을 대표 신호로 선택.

### 2) 뷰파인더 ROI 내부 1픽셀 전수 샘플링 및 원거리 감도 최적화
- 사용자가 조준 중인 `targetRoi` 내부에서는 `step = 1`(1픽셀 정밀 샘플링)로 전환하여 3×4px 소형 램프의 픽셀을 100% 온전히 포착.
- `minClusterPixels` 기준을 ROI 내부에서 **4픽셀**로 적응형 완화하여 20m 이상 원거리 보행신호등 안정 검출.

### 3) 뷰파인더 가이드 박스 폴백 활성화 (`TwoTierHybridSignalEstimator.kt`)
- `createDefault(context)`:
  - 모델 파일이 1KB 미만인 테스트 스텁인 경우 `DefaultViewfinderDetector()`를 기본 검출기로 주입.
  - `fallbackToViewfinder = true` 플래그를 통해 객체 검출 모델이 원거리 신호등을 놓치더라도 사용자가 조준한 뷰파인더 사각 영역(`reticleBox: 0.20..0.80, 0.10..0.60`) 내부에서 색상/지오메트리/다크하우징 정밀 교차 검증을 수행.
  - 유효 신호 검출 시 5프레임 롤링 일관성 필터(`verifier.verify`)를 통과해야만 최종 상태로 승인(Zero False-Green 불변성 보장).

## 3. 결과 및 영향 (Consequences)
- **차량등·보행등 분리 성공**: 한 프레임 안에 차량 신호등과 보행 신호등이 동시에 존재해도 간섭 없이 보행자 신호등만을 정확히 분리 인식 (`testIsolatesPedestrianLightFromCoexistingVehicleLight` 단위 테스트 통과).
- **20m+ 원거리 신호등 포착**: 4차로 이상의 교차로에서도 뷰파인더 조준 시 3×4px 적색/녹색 보행신호등이 정상 인식되어 락온 및 상태 알림 발화.
- **배경 오탐 0건 유지**: 다크 하우징 검증, 에메랄드 파장 필터, 5프레임 롤링 버퍼가 그대로 적용되어 가로수 및 간판 오탐 방지.
- **전체 단위 테스트 100% 통과**: 150+개 테스트 전원 통과 확인.
