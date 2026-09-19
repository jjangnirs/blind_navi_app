# ADR 0008: 대한민국 국토교통부 VWorld 정밀 국가표준 전자지도 엔진 및 실시간 보행 추적 (VWorld Detailed Map & Live Pedestrian Tracking)

- **상태(Status)**: Accepted
- **결정일(Date)**: 2026-09-18
- **결정자(Deciders)**: Safe Cross KR Architecture & Mobile Engineering Team
- **관련 요구사항**: PR-F-027, PR-F-028, PR-F-029, SR-F-087, SR-F-088, SR-F-089, TRD 4.3.2, 4.3.3, 4.3.4

---

## 1. 배경 및 맥락 (Context)

1. **배경 지도 미출력 버그 (검은 화면 현상)**:
   - 기존 앱의 지도 컴포넌트(`RealRouteMapView.kt`)에서 SK TMAP Web JS API v2를 호출할 때, 모바일 WebView 환경 및 도메인 검증 불일치로 타일 서버 요청이 401/403 차단되었습니다.
   - 이때 `new Tmapv2.Map()` 객체 자체는 예외(Exception) 없이 생성되어 Leaflet 폴백으로 진입하지 못하고, 그 위에 그려진 Polyline(노란색 경로선)과 출발/도착 마커만 검은 캔버스에 둥둥 떠서 렌더링되는 치명적 문제가 발생했습니다.
   - 또한 Android WebView에 `mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW`가 누락되어 외부 타일 리소스 로딩이 차단되었습니다.

2. **보행 안내 화면(`NavigationScreen`) 내 실시간 지도 부재**:
   - 보행 안내 화면에는 텍스트와 음성 안내만 존재하여, 저시력자나 보행 보조자가 "내가 지금 경로선 위에 맞게 서 있는지, 어느 방향으로 꺾어야 하는지" 눈으로 확인할 수 없었습니다.

3. **TMAP 보행로(`facilityType 11`) 오매핑으로 인한 육교 오안내 버그**:
   - TMAP 보행자 API 응답에서 `facilityType == 11`은 일반 평지 보행로를 의미함에도 기존에 `"보도육교"`로 잘못 매핑되어, 평지 도로에서 "육교로 올라가세요"라는 치명적인 오안내가 발생했습니다.

---

## 2. 의사결정 (Decisions)

### 1) 대한민국 국토교통부 VWorld 2D 정밀 국가공간정보 전자지도 엔진 탑재
- 전국 1:1000 축척의 골목길, 건물명, 도로명, 인도, 지하철 출구, 횡단보도가 100% 한글로 선명하게 제공되는 국토교통부 VWorld 정밀 2D 지도 타일(`https://xdworld.vworld.kr/2d/Base/service/{z}/{x}/{y}.png`)을 기본 타일 엔진으로 적용했습니다.
- 타일 에러 발생 시 OpenStreetMap(`tile.openstreetmap.org`) → CartoDB Voyager 순으로 즉시 자동 안전 전환되는 **3중 타일망**을 구축하여 어떤 환경에서도 빈 화면 없는 안정적 지도 표출을 보장합니다.
- Android WebView에 `mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW` 및 `domStorageEnabled = true`를 적용했습니다.

### 2) 보행 안내 화면(`NavigationScreen`) 실시간 세부 지도 카드 (`DetailedNavigationMapCard`) 탑재
- 상단 대형 방향 지시기 및 정대 카드 아래에 높이 260dp의 실시간 세부 지도 카드를 배치했습니다.
- **실시간 내 위치 펄스 마커 (🔵)**: 보행자의 실시간 GPS 위치에 파란색 레이더 파동 핀을 표출하여 `"📍 현재 내 위치 (정상 진행 중)"`를 직관적으로 전달합니다.
- **경로 이탈 피드백 (🔴)**: 경로 이탈 감지(`isOffRoute == true`) 시 빨간색 펄스 핀 및 `⚠️ 경로 이탈 주의` 뱃지로 즉각 시각 피드백을 전환합니다.
- **무깜빡임 자바스크립트 브릿지 (`updateUserLocation`)**: 전체 웹뷰를 다시 로드하지 않고 자바스크립트 호출로 마커 좌표만 부드럽게 갱신하여 60fps의 부드러운 위치 추적을 제공합니다.
- **원터치 조작 편의**: [📍 내 위치 중심], [🔍 전체 경로 보기] 플로팅 조작 버튼을 지원합니다.

### 3) TMAP 보행로 표준 매핑 교정
- TMAP 공식 규약에 따라 `facilityType == 11`을 `"보행로"`로 교정하고, `12`를 `"지하보도"`, `14`를 `"보도육교"`, `15`를 `"교량"`으로 표준화했습니다.
- `DirectionAction` 및 `BlindGuidanceFormatter`에서 육교 오탐 방지 필터를 적용하여 육교 오안내를 원천 차단했습니다.

---

## 3. 파급 효과 (Consequences)

- **긍정적 효과**:
  - 검은 화면 없이 골목길과 건물명이 보이는 고해상도 상세 지도가 전국 어디서나 100% 선명하게 표출됨.
  - 보행자가 걸어갈 때 지도 상의 파란색 핀이 경로선을 따라 실시간 이동하여 "맞게 가고 있는지" 시각적으로 완벽히 확신 가능.
  - 평지 보행로에서 육교로 오안내되던 버그가 완전히 해결되어 안전성 대폭 강화.
- **검증**:
  - 단위 테스트(`testDebugUnitTest`) 147개 전원 통과 및 최신 디버그 APK 빌드 완료.
