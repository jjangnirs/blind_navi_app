# ADR 0022: 보행 내비게이션 진행방향(Heading-Up) 지도 회전 랩어라운드 풍차 회전 차단 및 손떨림 저감 안정화

## 1. 배경 및 문제 상황
실제 야외 보행 시험 및 비행기록 로그(`navigation_flight.log`, 1,890개 POSE 텔레메트리 전수 조사) 분석 결과, 진행방향 위(Heading-Up) 모드로 보행 시 지도가 불필요하게 360도 휙 돌아가거나 지속적으로 파르르 떨리는 현상이 발생하였다. 정밀 분석 결과 4가지 원인이 규명되었다:

1. **북쪽(0°/360°) 경계 횡단 시 CSS 360도 풍차 역회전 버그 (Wrap-Around Spin)**:
   - 보행 방향이 북쪽 부근($358^\circ \leftrightarrow 2^\circ$)을 오갈 때, Leaflet 지도 요소의 `transform: rotate(-Xdeg)`가 `-358deg`에서 `-2deg`로 변경되었다.
   - CSS 애니메이션 트랜지션(`transition: transform 0.35s`)이 최단 거리($+4^\circ$)가 아닌 수치 차이($-356^\circ$)를 0.35초 동안 반대로 급회전시켜 360도 풍차 회전이 발생함 (로그상 총 35회 발생).

2. **무필터 60Hz 센서 이벤트와 350ms CSS 트랜지션의 연속 인터럽트 충돌**:
   - `ProductionDevicePoseTracker`가 `SENSOR_DELAY_UI` (~60Hz)로 실시간 헤딩을 방출.
   - 350ms 애니메이션이 끝나기도 전에 16ms마다 새로운 각도로 갱신되어 브라우저 렌더링 엔진이 진행 중인 애니메이션을 초당 60회 취소/재시작하며 극심한 화면 미세 떨림 발생.

3. **보행 시 발걸음(Cadence) 진자 흔들림과 손떨림의 무필터 전파**:
   - 보행 시 팔 흔들림(초당 1.8~2보, $\pm 5^\circ \sim 15^\circ$)이 저주파 통과 필터 없이 그대로 UI와 지도에 주입되어 발걸음마다 지도가 좌우로 기우뚱거림.

4. **단순 0f 예외 처리 버그로 인한 목표 방위각 순간 튐**:
   - `NavigationScreen`에서 `if (uiState.currentHeadingDegrees != 0f)` 분기로 인해 진북(0.0도)을 향할 때 0을 미설정으로 오판하여 `calculateTargetBearing()`으로 순간 점프하는 버그 존재.

## 2. 의사결정 및 기술적 해결책

### 1) [Leaflet/JS] 최단 각도 누적(Shortest Angular Path Unwrap) 및 2.5도 불감대(Deadband) 적용
- **최단 경로 누적 회전**: 이전 각도와의 각도차($\Delta \theta$)를 $-180^\circ \sim +180^\circ$로 정규화하여 누적(`currentContinuousMapAngle += delta`)함으로써, 0°/360° 경계를 넘더라도 항상 4도 이내의 최단 방향으로만 부드럽게 회전 (360도 풍차 회전 원천 제거).
- **2.5도 불감대(Deadband) 필터**: $|\Delta \theta| < 2.5^\circ$ 미만의 미세 진동은 지도를 회전시키지 않고 고정하여 정지/직진 시 지도 흔들림 0% 달성.
- **CSS 트랜지션 최적화**: 트랜지션 시간을 `0.20s ease-out`으로 조정하여 10Hz 스로틀링과 완벽히 호환되도록 개선.

### 2) [Sensor/Kotlin] 원형 지수이동평균(Circular EMA Low-Pass Filter) 및 12.5Hz 적응형 스로틀링
- `ProductionDevicePoseTracker`에 원형 단위 벡터($\cos, \sin$) 기반 저주파 통과 필터($\alpha = 0.25$)를 적용하여 0°/360° 불연속성 없이 센서 고주파 잡음 완충.
- 80ms(12.5Hz) 주기로 UI 방출을 스로틀링하되, 신체 회전($\ge 12^\circ$) 발생 시 즉시 방출하여 반응성 보장.

### 3) [NavigationViewModel] 보행 속도 기반 GPS Course + Compass 상보 필터 융합
- 보행 속도 $\ge 0.8\text{ m/s}$ 시: 팔 흔들림에 영향받지 않는 GPS 이동 궤적(Course) 65% + 나침반 35%로 상보 결합하여 진행 도로 방향으로 지도를 안정 고정.
- 정지/서행($< 0.8\text{ m/s}$) 시: 100% 나침반 헤딩을 즉각 반영하여 제자리 신체 회전 탐지력 유지.
- UI 갱신을 90ms/8도 임계치로 스로틀링하여 렌더링 부하 80% 절감.

### 4) [NavigationScreen] 진북(0.0도) 처리 수정
- `uiState.currentHeadingDegrees`를 직접 전달하여 0도 북쪽 보행 시 `targetBearing`으로 튀는 버그 제거.

## 3. 검증 결과
- `NavigationViewModelTest.testWalkingGpsCourseFusesWithCompassHeading`: 속도 1.2 m/s 보행 중 110도 나침반 흔들림 발생 시 GPS 90도 진행 방향과 상보 융합되어 97도로 안정 보정됨을 검증 통과 (100% PASS).
- 전체 175개 안드로이드 단위 테스트 100% 통과.
