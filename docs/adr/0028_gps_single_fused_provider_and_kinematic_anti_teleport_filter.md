# ADR-028: GPS 다중 프로바이더 동시 등록 결함 해소(단일 FUSED_PROVIDER 우선순위화), 보행자 기구학 이상치(Anti-Teleport) 기각 필터 및 지도 회전/흔들림 안정화

## 1. 배경 및 문제 상황

2026년 9월 26일 시각장애인 실제 야외 보행 테스트 중 3대 치명적 GPS 및 지도 안내 장애가 발생하였습니다:

1. **야외인데도 GPS 수신율이 35%로 급락 (단말기 하드웨어 불량 의심)**:
   - 광활한 야외 보행 중에도 앱 상단에 표시되는 GPS 수신율이 100%에서 갑자기 35% 수준으로 급락함.
2. **좌표 튐 및 순간이동(Teleport)**:
   - 보행 도중 현재 위치가 순간적으로 200m~435m 떨어진 곳으로 순간이동하여 경로 이탈(OFF_ROUTE) 및 무한 재탐색 발생.
3. **지도 180도 풍차 회전 및 흔들림 (지도가 너무 돌거나 흔들림)**:
   - 정상 보행 중임에도 지도가 좌우로 덜덜 떨리거나 180도 역회전하여 사용자가 방향 감각을 완전히 상실함.

---

## 2. 9월 26일 단말기 실측 로그(`navigation_flight.log.1`) 심층 포렌식 결과

단말기 MTP를 통해 9월 26일 전체 비행 기록 로그(3.14MB, 13,382행)를 추출하여 정밀 분석한 결과:

1. **200m ~ 435m 순간 점프 10건 포착**:
   - `[13:59:18.218] (acc=100.0m, sig=20%)` $\rightarrow$ 102ms 후 `[13:59:18.320] (acc=3.1m, sig=100%)` (435.2m 순간이동, 속도 4,266 m/s = 마하 12.5)
   - `[15:21:04.969] (acc=3.8m)` $\rightarrow$ 270ms 후 `[15:21:05.239] (acc=38.7m, sig=50%)` (212.0m 점프) $\rightarrow$ 134ms 후 `[15:21:05.373] (acc=3.0m)`
2. **동일 시각 17m 평행 2중 좌표 핑퐁 현상 (Point A $\leftrightarrow$ Point B, 1초에 2회 왕복)**:
   - `lat=35.12932, lon=126.85233` (Point A)와 `lat=35.12923, lon=126.85251` (Point B, 17m 거리)가 1초에 2~3회 번갈아 방출됨.
3. **목표 방위각(targetBearing) 180도 역회전 4,851회 발생**:
   - 17m 핑퐁과 위치 점프로 인해 `targetBearing`이 77도 $\leftrightarrow$ 205도로 180도 점프하는 현상이 4,851회 누적되어 지도가 팽이처럼 회전함.

### 3대 근본 원인:
1. **`ProductionLocationSource.kt`의 3개 Provider 동시 등록 결함 (핵심 원인)**:
   - `startTracking()`에서 `LocationManager.GPS_PROVIDER`, `LocationManager.FUSED_PROVIDER`, `LocationManager.NETWORK_PROVIDER` 3개를 **동일한 `locationListener`에 모두 동시 등록**함.
   - S25 Ultra의 `FUSED_PROVIDER`(Point A)와 순수 `GPS_PROVIDER`(Point B)의 칼만 필터 차이(17m 오차)가 1초에 2회 번갈아 방출되어 17m 핑퐁이 발생함.
   - `NETWORK_PROVIDER` 기지국(Cell-ID) 위치(정확도 40m~100m, 435m 떨어진 기지국 좌표)가 불시에 유입되어 200m~435m 텔레포트가 발생함.
   - 기지국 픽스(정확도 75m) 유입 시 `calculateSignalStrengthPercent`가 25점+10점 = **35%로 계산**되어 사용자가 "야외인데 GPS 수신율이 35%로 떨어진다(하드웨어 불량인가?)"고 오인하게 만듦. (하드웨어 고장이 아닌 소프트웨어 3중 Provider 충돌 버그!)
2. **보행자 기구학 순간이동(Teleport) 기각 필터 부재**:
   - 2초 이내 200m~435m 이동하는 물리적 불가능 위치 샘플을 기각하지 않고 맵과 뷰모델에 그대로 주입함.
3. **보행 속도에서의 과도한 GPS Course 융합(65%) 및 지도 1.5m 팬 감도**:
   - 보행 속도에서는 발걸음마다 GPS bearing이 $\pm 40^\circ$ 요동침에도 `gpsBrg`를 65%나 융합하여 헤딩이 요동침.
   - `RealRouteMapView`의 `panTo` 트리거 거리가 1.5m로 너무 민감하여 제자리 서행 시 1.6m GPS 미세 떨림에도 화면이 덜덜 떨림.

---

## 3. 기술적 해결책 및 아키텍처 결정

### 1) [ProductionLocationSource] 단일 Provider 우선순위 등록 원칙 확립
- 다중 Provider 동시 등록을 전면 금지하고 **단 하나의 최적 Provider만 배타적으로 등록**:
  - 1순위: Android 12+ (SDK >= 31) S25 Ultra 고정밀 융합 `LocationManager.FUSED_PROVIDER` (L1+L5 다중 위성 + Wi-Fi RTT + 센서 최적 단일 칼만 필터)
  - 2순위: 순수 하드웨어 `LocationManager.GPS_PROVIDER`
  - 3순위: 실내 보조 `LocationManager.NETWORK_PROVIDER`
- **효과**: 17m 평행 좌표 핑퐁 및 기지국 좌표 불시 난입 원천 차단.

### 2) [LocationOutlierFilter] 보행자 기구학 기반 순간이동/기지국 기각 필터 도입
- 독립 모듈 `LocationOutlierFilter`를 신설하여 `ProductionLocationSource`에 연결:
  1. **단발성 기지국 저정밀도 기각**: 선행 양호 위치(정확도 <= 15m)가 확보된 상태에서 단발성으로 들어오는 45m 초과 저정밀도 샘플 즉각 기각.
  2. **기구학 순간이동(Teleport) 기각**:
     - $\Delta t \le 3.0\text{s}$ 내에 거리 $D > 25\text{m}$ 및 속도 $V > 10\text{m/s}$ (시속 36km/h 초과)인 경우 즉각 기각.
     - 50m 초과 점프($V > 15\text{m/s}$) 즉각 기각.
  3. **데드락 방지**: 사용자가 차량이나 대중교통 탑승 시 4회 연속 이상치 발생 시 신규 위치로 강제 수용.

### 3) [NavigationViewModel] 헤딩 융합 안정화 및 정지 시 잔류 bearing 초기화
- 정지 또는 초저속($< 0.5\text{m/s}$) 시 `lastValidGpsBearing = null`로 초기화하여 정지 상태에서 과거 GPS 각도로 끌려가지 않도록 방지.
- GPS bearing 채택 조건을 `accuracy <= 12.0f`로 강화 (부정확한 신호의 각도 배제).
- 나침반과 GPS 각도차 허용 한계를 80도에서 50도로 축소하여 급격한 회전 시 나침반 100% 즉시 반영.

### 4) [RealRouteMapView] 지도 이동 불감대 상향 및 부드러운 카메라 추종
- 내 위치 카메라 자동 이동(`panTo`) 임계 거리를 기존 1.5m에서 **2.5m**로 상향하여 제자리 정지/서행 중 1.6m GPS 미세 떨림에 의한 화면 덜덜거림 완벽 차단.
- `map.panTo(latLng, { animate: true, duration: 0.45, easeLinearity: 0.25 })`로 부드러운 카메라 패닝 적용.

---

## 4. 검증 결과

1. **단위 테스트 100% 통과**:
   - `LocationOutlierFilterTest`:
     - 435m 기지국 순간이동(102ms, speed=4200m/s) 즉각 기각 검증 통과
     - 212m 점프(270ms, acc=38.7m) 즉각 기각 검증 통과
     - 단발성 75m 기지국 저정밀도 유입 기각 검증 통과
     - 4회 연속 차량 이동 시 데드락 방지 수용 검증 통과
   - `testDebugUnitTest`: 26개 태스크 전체 성공 (기존 187개 포함 100% Pass).
2. **APK 배포**:
   - `app-debug-0927-v28.apk` 생성 및 S25 Ultra 단말기 Download 폴더 배포 완료.
