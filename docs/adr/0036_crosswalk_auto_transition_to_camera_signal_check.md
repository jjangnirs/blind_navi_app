# ADR-0036: 횡단보도 접근 시 카메라 신호 확인 화면 자동 전환 (경로상 15m · 정지 감지)

## 1. 배경 및 문제 의식 (Context)
- 기존 설계는 횡단보도 15m/8m 접근 시 `TriggerCrossingAssist`로 카메라 보조 화면에 자동 전환하도록 되어 있었으나, 실제 앱은 `setRoute()`에 횡단보도 시설 목록을 넘기지 않아(빈 목록) **한 번도 자동 전환되지 않았다** (10/01 현장 로그: 자동 전환 기록 0건).
- `CrossingApproachEngine`은 직선거리 40m 사전 알림 구간에서도 같은 상태를 내므로, 그대로 연결하면 40m에서 전환되는 문제가 있었다.
- 8m 기준은 GPS 오차(현장 10~30m)를 고려하면 횡단보도 안에서 전환될 수 있어 너무 늦다.

## 2. 결정 사항 (Decisions)

### 2.1 전환 시점
전환 후 카메라 기동·휴대폰 들기·신호 확정까지 약 5~8초(보행 6~10m)가 걸리고 GPS 오차를 감안하여 다음과 같이 정한다.

| 조건 | 동작 |
|---|---|
| 경로상 남은 거리 15m 이내 | 자동 전환 (횡단보도당 1회) |
| 30m 이내에서 속도 < 0.3m/s가 2초 이상 지속 | 즉시 전환 (GPS가 늦어도 연석에 선 사용자를 놓치지 않음) |
| GPS 정확도 > 25m | 자동 전환하지 않고 "화면의 신호 확인 버튼을 누르세요" 1회 권유, GPS가 회복되면 자동 전환 |
| 경로 이탈 중, 5m 초과 통과 후 | 동작하지 않음 |

### 2.2 구현
- `CrossingAutoTriggerPolicy`(순수 Kotlin)가 판정을 전담한다.
- 횡단보도 목록은 TMAP 경로의 횡단보도 분기점(`turnType 211~217`, `facilityType` "횡단보도" 등 `DirectionAction.CROSSWALK`)에서 추출.
- 경로상 위치는 `RouteProgressEngine.maneuverAlongDistances`로 분기점 좌표를 경로선에 단조 증가 투영해 계산(`pointIndex`는 신뢰할 수 없어 사용하지 않음).
- 직선거리가 아닌 경로상 거리를 사용하여 옆 골목·건너편 횡단보도에는 반응하지 않는다.
- `CrossingApproachEngine` 경로의 자동 전환 신호는 제거(음성 접근 안내만 유지).
- 재탐색 후에도 이미 전환한 횡단보도(15m 이내 동일 위치)에서는 재전환하지 않으며, 카메라 화면은 `launchSingleTop`으로 중복 열림 방지.
- 비행 기록 `[CROSSING_AUTO]`: 등록 횡단보도 목록, 전환 사유(`DISTANCE_15M` / `STOPPED_NEAR_CROSSWALK`), 남은 거리, GPS 정확도, 속도.

## 3. 제약
- 화면이 꺼져 있거나 앱이 백그라운드이면 안드로이드가 카메라 사용을 막아 신호 확인이 시작되지 않는다.
- 카메라 보조 화면에 전달하는 횡단보도 문맥은 아직 고정값(`CW-GMC-2026-001`)이다.

## 4. 검증 결과 (Verification)
- 단위 테스트 `CrossingAutoTriggerPolicyTest`(7건), `NavigationViewModelTest`의 15m 1회 전환 테스트.
- APK: `app-debug-1001-v38.apk`. 현장에서 `[CROSSING_AUTO]` 로그로 실제 전환 거리 확인 후 12~20m 범위에서 조정 예정.
