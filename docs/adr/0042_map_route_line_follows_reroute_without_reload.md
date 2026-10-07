# ADR-0042: 재탐색 경로의 지도 안내선 반영 및 지도 재로드 방지

## 1. 배경 및 문제 의식 (Context)
- 2026-10-07 11:57 현장 테스트에서 출발 직후 `INITIAL_DEPARTURE_CALIBRATION` 재탐색으로 경로가 866 m에서 354 m로 바뀌었다. 음성 안내와 단계 진행은 새 경로를 따랐지만, 지도에는 최초 866 m 경로선이 계속 표시되었다.
- 내 위치 핀은 새 경로에 맞춘 `mapLocation`으로 표시되어, 안내선과 핀이 서로 어긋나 보였다.
- 원인 1: `NavigationScreen`이 지도 카드에 화면 진입 인자 `route`(최초 경로)를 넘겼다. 재탐색 결과는 `uiState.route`에만 반영되었다.
- 원인 2: `RealRouteMapView`는 출발·도착 이름이 바뀌면 HTML을 다시 만들었다. 이 이름은 첫/마지막 안내 문구에서 오므로 재탐색 시 바뀐다. 그러면 WebView 전체가 다시 로드되어 지도 중심·줌·회전이 초기화되고, 지도가 튀거나 돈다.

## 2. 결정 사항 (Decisions)
- 지도 카드와 "경로 진행 (n/N단계)" 표시는 `uiState.route ?: route`(현재 안내 경로)를 사용한다.
- `RealRouteMapView`의 HTML은 최초 한 번만 만든다. 경로·횡단보도·출발/도착 이름은 `replaceRoute(coords, crosswalks, origin, dest)`로 전달하여 경로 레이어만 교체한다.
- 재탐색 시 지도 중심·줌·회전은 바꾸지 않는다. 기존 회전 6° 불감대와 4 m 미만 이동 무시 규칙(ADR-0035)은 그대로 유지한다.

## 3. 검증
- `assembleDebug` 빌드 통과.
- 현장 확인 항목: 재탐색 직후 안내선이 실제 안내 경로와 일치하고, 지도가 튀거나 회전하지 않는지 확인.
- APK: `release/app-debug-1008-v46.apk`.
