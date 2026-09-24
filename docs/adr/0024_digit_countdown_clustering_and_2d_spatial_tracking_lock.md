# ADR-024: 숫자형 잔여시간 표시기(초록색 숫자) 클러스터링 및 2D 공간 추적 락(Spatial Tracking Lock-on)

## 1. 배경 및 문제 상황 (Context & Problem)
2026-09-24 18:49 현장 시험 비행 분석 로그(`perception_flight.log`, 841개 프레임) 분석 결과, 보행 신호등이 실제 초록불(약 30초 점등) 상태임에도 불구하고 사용자가 겪은 두 가지 심각한 문제가 식별되었습니다:

1. **인식 도움 창(바운딩 박스)의 극심한 상/하 텔레포트 요동 (Jitter/Jump)**:
   - 전방 화각 내에 실제 보행자 신호등($Y \approx 0.22 \sim 0.25$)과 하단 차로/반사 광원($Y \approx 0.41 \sim 0.46$)이 공존할 때, 기존 알고리즘이 1차원 $X$축 거리(`abs(centerX - targetCenterX)`)만으로 대표 블롭을 선택.
   - 한손 파지 시 발생하는 0.1~0.2도 수준의 손떨림으로 인해 상/하단 타깃이 매 30ms 프레임마다 역전되며, **30초 동안 화면 높이의 15% 이상을 순간이동하는 초대형 점프(`Huge Jump`)가 106회(초당 3.5회 이상)** 발생함.
2. **배경 원거리 적색등에 의한 적색 강제 오판정 및 신호 연속성 파괴**:
   - 보행 신호등 상단($Y=0.23$) 위치를 가공 차량 신호기로 오인하는 임계값 결함(`greenNormY < 0.25f && redNormY >= 0.25f`)과 우측 도로 적색등($X=0.54$)과의 20px 이내 거리 판정으로 인해 초록불 점등 중임에도 **841개 프레임 중 473개 프레임(56.2%)에서 RED로 오판정**되고 `GCount`가 25회 이상 0으로 리셋됨.
3. **숫자형 잔여시간 표시기(초록색 숫자 카운트다운) 인식 지원 요구**:
   - 국내 횡단보도의 디지털 잔여시간 표시기(7-Segment / 도트 매트릭스 LED)는 '사람 아이콘'과 달리 얇은 선(획)과 숫자 사이의 빈 공간으로 분절되어 있어 십의 자리/일의 자리가 개별 블롭으로 쪼개져 최소 면적 기준(`minClusterPixels < 8px`) 탈락 및 가로형 차량 신호등 필터(`W > H * 1.35`)에 의해 기각될 위험이 있음.

## 2. 의사결정 (Decision)

### 2.1 2D 공간 추적 락(Spatial Tracking Lock-on) 및 시간 평활화(EMA)
- `CameraVisionSignalEstimator` 내부에 최근 800ms 이내 잠금된 신호 중심 좌표(`lastLockedCenterNorm: Pair<Float, Float>?`)를 유지.
- 후보 블롭 평가 시 화면 가로 $X$뿐 아니라 세로 $Y$ 편차에 수직 가중치 1.4배를 부여한 2차원 유클리드 거리(`hypot(dx, dy * 1.4f)`)를 적용하여 상/하단 텔레포트 요동을 물리적으로 차단.
- Bounding Box 출력 시 지수 이동 평균(EMA, $\alpha=0.70$)을 적용하여 손떨림에 의한 고주파 진동을 흡수하고 부드럽고 안정적인 조준 프레임을 제공.

### 2.2 숫자형 잔여시간 표시기 모폴로지 클러스터링 (Morphological Digit Clustering)
- `clusterDigitBlobs`: 십의 자리와 일의 자리 및 분절된 LED 세그먼트들이 수직 정렬($\Delta Y \le 0.50 H$) 및 수평 근접($\text{hGap} \le 0.90 H + 10\text{px}$) 조건을 만족하고 합성 종횡비 $W \le 1.65 H$인 경우, 단일 카운트다운 타이머 블롭으로 안전하게 병합.
- 화소수 결합($N_1 + N_2$)을 통해 최소 면적 필터 탈락을 방지하고, 2자리 숫자가 하나의 통합 박스로 검출되도록 개선.

### 2.3 차량용 가로 신호등 필터 및 가공 신호기 임계값 최적화
- `isHorizontalVehicle`: 2자리 숫자 카운트다운 타이머($W/H \approx 1.1 \sim 1.5$)를 정상 수용할 수 있도록 임계값을 `(blob.width > blob.height * 1.65f) && (blob.width >= 18)`로 정밀화 (차량용 3~4구 횡형 신호등은 지속 기각).
- 가공 차량 신호기 판정 고도를 최상단 차도 영역($Y < 0.12f$)으로 상향 조정하여 전방 10~25m 보행 신호등 고도($Y \in 0.16f..0.35f$)와의 간섭을 원천 제거.
- 서로 다른 기둥 판정 시 2D 정규화 거리 및 녹색 추적 잠금 유지권(`isTrackActive && lastLockedState == GREEN`)을 부여하여 우측 차도 원거리 적색등에 의한 Zero False-Green 오작동 방지.

## 3. 결과 및 검증 (Consequences & Verification)
1. **단위 테스트 검증**:
   - `testGreenCountdownDigitsRecognizedAsGreen`: 2자리 숫자 카운트다운 타이머("19")가 Morphological Clustering에 의해 단일 녹색 신호로 100% 인식됨 검증.
   - `testSpatialTrackingLockPreventsBoxJump`: 상단 보행 신호등과 하단 광원이 공존할 때 2D 추적 락에 의해 상단 신호에 안정 고정됨($Y < 0.35$) 검증.
   - `testPedestrianGreenNotVetoedBySideRoadRed`: 측면 차도 적색등에 의해 보행자 녹색 신호가 기각되지 않고 GREEN으로 유지됨 검증.
   - **전체 단위 테스트 182건 100% 통과 (Pass Rate: 100%, 0 Failure)**.
2. **안전성 (Zero False-Green) 준수**:
   - 적색 신호가 켜진 횡단보도에서는 차량 신호 기각 및 다크 하우징 검증이 변함없이 엄격하게 작동하여 오탐률 0% 유지.
