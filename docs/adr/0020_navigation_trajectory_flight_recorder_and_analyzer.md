# ADR 0020: 실시간 보행 내비게이션 경로 진행 분석용 비행 기록기(Navigation Flight Recorder) 및 진단 툴링

## 1. 문맥 (Context)
야외 실도로 보행 내비게이션 테스트 중 GPS 수신 상태, 경로 이탈(Cross-Track Error) 여부, 경로 재탐색 트리거 원인, 기기 헤딩과 분기점 방위각(Bearing) 간의 정대 상태, 음성 안내 발화 시점을 정밀 분석할 수 있는 전용 텔레메트리 비행 기록기(Flight Recorder)가 요구되었다.

기존 신호등 인지 비행 기록기(`PerceptionFlightRecorder`)와 마찬가지로, 현장에서 스마트폰만으로 진단 로그를 즉시 확인/공유하고, PC 연결 시 실시간 스트리밍 모니터링 및 분석 리포트 생성이 가능해야 한다.

---

## 2. 결정 (Decision)

### 1) 내비게이션 전용 비행 기록기 (`NavigationFlightRecorder.kt`)
- **저장 위치:** 단말기 로컬 파일 (`Android/data/kr.safecross.mobile/files/logs/navigation_flight.log`) 및 Logcat (`TAG = SafeCrossNavFlight`).
- **저장 용량:** 최대 3MB 순환 롤링 (초과 시 백업 후 롤링).
- **수집 카테고리:**
  1. `[ROUTE_START]`: 출발지, 도착지, 총거리, 예상시간, 스텝 수, 횡단보도 수
  2. `[GPS]`: 위경도(정규화), 수신 정확도(`acc`), 보행 속도(`spd`), 방위각(`brg`), 신호 강도(`sig`), 위성 수(`sats`)
  3. `[PROGRESS]`: 현재 스텝 번호, 누적 진행거리(`distAlong`), 잔여 거리(`remDist`), 다음 분기점 거리(`nextM`), 크로스트랙 오차(`CTE`), 이탈 상태 및 카운트
  4. `[POSE]`: 기기 헤딩(`heading`), 목표 방위각(`targetBearing`), 편차(`diff`), 정대 여부(`aligned`), 안내 문구
  5. `[APPROACH]`: 30m / 15m 분기점 접근 예고
  6. `[STEP_CHANGE]`: 분기점 도달 및 다음 단계 전환
  7. `[REROUTE_TRIGGER]`: 이탈 거리/카운트 또는 출발점 이격 등 재탐색 유발 원인 및 수치
  8. `[REROUTE_SUCCESS] / [REROUTE_FAIL]`: 재탐색 결과 및 신규 경로 사양
  9. `[GUIDANCE]`: 우선순위, 카테고리, 발화 음성 전문
  10. `[FINISH]`: 목적지 도착 완료, 총 보행거리

### 2) 내비게이션 엔진 연동 (`RouteProgressEngine.kt`, `NavigationViewModel.kt`)
- `RouteProgressState`에 `offRouteConsecutiveCount`를 노출하여 연속 이탈 카운트를 추적.
- `NavigationViewModel.kt`의 `processLocationSample`, `processDevicePose`, `recalculateRouteFromCurrentLocation`, `enqueueGuidance`, `setRoute` 전 과정에 비행 기록기 로깅 파이프라인 연동 (포즈 로그는 1초 스로틀링 적용).

### 3) 실시간 UI 및 원클릭 공유 버튼 (`NavigationScreen.kt`)
- 화면 하단에 `📊 실시간 경로 분석 상태` HUD 카드 및 **`경로 분석 진단 로그 공유/저장`** 버튼 추가.
- 탭 시 최근 150줄의 상세 경로 로그를 카카오톡, 이메일, 클립보드로 즉시 복사/공유 가능.

### 4) PC 분석 및 모니터링 툴링 (`scripts/`)
- `scripts/monitor_flight_logs.ps1`: `SafeCrossNavFlight:V` 실시간 Logcat 스트리밍 추가.
- `scripts/pull_navigation_logs.ps1`: USB 연결 단말기에서 `navigation_flight.log`를 자동 추출 (ADB 및 MTP 지원).
- `scripts/analyze_navigation_log.py`: 로그를 자동 파싱하여 GPS 정확도, CTE 분포, 재탐색 횟수, 방위각 일치율, 발화 요약을 구조화된 리포트로 출력.

---

## 3. 결과 및 영향 (Consequences)
- 보행자가 걷는 동안 발생하는 모든 GPS 위치, 오차 거리, 안내 발화, 재탐색 이벤트가 밀리초 단위로 투명하게 기록됨.
- 현장에서 "왜 경로가 변경되었는지", "어느 지점에서 GPS가 튀었는지", "다음 안내가 제때 나왔는지"를 즉각 진단할 수 있음.
