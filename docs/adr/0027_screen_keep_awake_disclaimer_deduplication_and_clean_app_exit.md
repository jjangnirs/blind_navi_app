# ADR-027: 절전 모드 화면 꺼짐 방지(FLAG_KEEP_SCREEN_ON), TMAP 고지문 무한 반복 루프 해소, 및 원터치 앱 사용 종료 파이프라인

## 1. 배경 및 문제 상황

실제 시각장애인 야외 보행 테스트 중 3가지 심각한 사용성 및 접근성 결함이 보고되었습니다:

1. **절전 모드 화면 꺼짐 (Screen Sleep Timeout)**:
   - 시각장애인은 보행 중 터치스크린을 수시로 탭하지 않고 음성 안내 및 진동 햅틱에 의존함.
   - 앱 내에 화면 켜짐 유지 플래그가 설정되어 있지 않아 15~30초 후 안드로이드 OS 절전 모드로 진입하며 화면이 꺼짐.
   - 이로 인해 카메라 영상 분석, 자세 센서(Compass/IMU), GPS 위치 수신 및 TTS 음성 안내가 전면 중단되는 치명적 안전 문제 발생.

2. **TMAP 사용 안내 문구 무한 반복 발화 (Disclaimer Infinite Loop)**:
   - 목적지 선택 후 경로 요약 화면 진입 시 "이 경로는 TMAP 보행자 경로 안내를 기반으로 제공되며..." 접근성 고지문이 끊임없이 반복 발화됨.
   - **근본 원인 포렌식**:
     - `MainActivity.kt`의 GPS 위치 수신 코루틴이 1초마다 `routeSummaryViewModel.updateOriginIfGpsMoved`를 호출함.
     - `updateOriginIfGpsMoved` 내부에서 기준점(`currentOrigin`)을 TMAP 경로의 첫 정점(`fullGeometry.first()`)과 비교함.
     - TMAP API는 출발지 좌표를 인근 도로망 노드로 스냅(Snap)하여 반환하므로, 실제 GPS 원본 좌표와 스냅된 노드 간에 15m 이상의 물리적 차이가 상시 발생함.
     - 결과적으로 위치가 정지해 있어도 매초 `distance > 15.0m` 조건이 참(True)이 되어 `loadRoute`가 무한 재호출됨.
     - `RouteSummaryViewModel`에 발화 완료 여부 플래그가 없어 `loadRoute` 성공 시마다 `RouteSummaryEffect.SpeakDisclaimer`가 1초마다 재방출되어 TTS 큐가 마비됨.

3. **앱 사용 종료 버튼 미작동 및 부재 (Clean App Exit Failure)**:
   - `DestinationScreen`에 앱을 명시적으로 종료할 수 있는 대형 접근성 버튼이 전무함 (안드로이드 시스템 제스처를 사용하기 어려운 시각장애인이 앱을 종료할 수 없음).
   - `NavigationScreen`의 "보행 안내 종료" 클릭 시 백스택 중복 적재(`popUpTo(inclusive = false)` 후 중복 `navigate`) 및 `routeSummaryViewModel`의 잔여 데이터 미정리로 인해 `DestinationScreen` 복귀 후에도 TMAP 고지문이 다시 터져 나옴.
   - `stopNavigation()`이 이벤트 루프를 통해 다중 호출되면서 재진입(Re-entrant) 레이스가 발생함.

---

## 2. 기술적 해결책 및 아키텍처 결정

### 1) [MainActivity] 절전 모드 화면 꺼짐 방지 (`FLAG_KEEP_SCREEN_ON`)
- `MainActivity.onCreate`에서 `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)`를 적용.
- 앱이 전면에 활성화되어 있는 동안 안드로이드 OS의 자동 화면 잠금/화면 꺼짐을 방지하여 카메라, 센서, GPS 수신 및 음성 안내가 단절 없이 100% 지속되도록 보장.

### 2) [RouteSummaryViewModel & MainActivity] 고지문 무한 발화 루프 원천 차단
- **발화 래치(`hasSpokenDisclaimer`) 도입**:
  - `RouteSummaryViewModel`에 `private var hasSpokenDisclaimer = false` 래치를 추가.
  - 최초 경로 로드 성공 시에만 1회 `RouteSummaryEffect.SpeakDisclaimer`를 방출하고 래치를 잠금.
- **무음 갱신 매개변수(`isSilentUpdate: Boolean = false`)**:
  - `updateOriginIfGpsMoved`에 의한 백그라운드 GPS 위치 자동 보정 재탐색 시에는 `isSilentUpdate = true`를 전달하여 음성 고지문을 절대 재발화하지 않음.
- **스냅 노드 오차 방지(`lastRequestedOriginGps`)**:
  - TMAP의 스냅된 첫 도로 노드가 아닌, 실제 직전 요청에 사용했던 `lastRequestedOriginGps` 좌표와 거리를 비교하도록 개선.
  - GPS 미세 노이즈 허용 임계치를 15m에서 30m로 상향하여 불필요한 재탐색 차단.
- **화면 상태 가드(`Screen.RouteSummary.route`)**:
  - `MainActivity`에서 현재 화면이 `Screen.RouteSummary.route`일 때만 `updateOriginIfGpsMoved`가 실행되도록 가드 추가.
- **명시적 리셋(`clearRoute`)**:
  - `onNavigateBack` 또는 `onStopNavigation` 시 `clearRoute()`를 호출하여 경로 상태와 래치를 즉시 초기화.

### 3) [NavigationScreen & DestinationScreen] 고대비 원터치 앱 종료 파이프라인
- **`DestinationScreen`**:
  - 상단 타이틀 바에 '설정'과 나란히 '앱 종료' 버튼(48dp) 배치.
  - 화면 최하단에 대형 고대비 '앱 사용 종료' 버튼(최소 64dp, 고대비 레드 `#B71C1C` 및 테두리 `#FF5252`) 추가.
  - `BackHandler`를 등록하여 메인 화면에서 시스템 뒤로가기 누를 시 `(context as? Activity)?.finishAffinity()`로 앱을 깔끔히 종료.
- **`NavigationScreen`**:
  - 하단에 2개의 명확한 종료 액션 제공:
    1. **"보행 안내 종료 (메인 화면으로)"** (64dp, Red, 안내만 종료하고 메인 목적지 검색 화면으로 안전 복귀)
    2. **"앱 사용 완전 종료"** (64dp, Dark Red + Red Border, 보행 안내 중지 및 `finishAffinity()`로 앱 즉시 닫기)
  - `BackHandler`를 통해 시스템 뒤로가기 시 `viewModel.stopNavigation()`이 안전하게 트리거되도록 보호.
- **`NavigationViewModel`**:
  - `stopNavigation()`의 시작부에 `if (_uiState.value.isFinished) return` 멱등성 가드를 추가하여 재진입 다중 발화 및 이벤트 중복 방지.
- **백스택 클린업**:
  - `onStopNavigation` 시 `navController.navigate(Screen.Destination.route) { popUpTo(Screen.Destination.route) { inclusive = true }; launchSingleTop = true }` 적용으로 백스택 중복 및 잔류 제거.

---

## 3. 검증 결과

1. **단위 테스트 (100% ALL PASS)**:
   - 총 187개 테스트 전체 통과 (0 failures, 0 ignored).
   - 신규 테스트 케이스 추가 및 검증 완료:
     - `RouteSummaryViewModelTest.loadRoute does not re-emit SpeakDisclaimer on subsequent loads or gps updates`
     - `RouteSummaryViewModelTest.clearRoute resets state and disclaimer latch`
2. **빌드 및 기기 전송 완료**:
   - `gradlew assembleDebug` 정상 빌드 완료.
   - Galaxy S25 Ultra 기기(`Download` 폴더)에 `app-debug-0927-v27.apk` 전송 및 파일 무결성 확인 완료.
