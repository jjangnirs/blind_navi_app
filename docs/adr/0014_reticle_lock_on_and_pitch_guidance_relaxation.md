# ADR 0014: 뷰파인더 락온 오표시 방지 및 카메라 하향 각도(Pitch) 완화

## 1. 문맥 (Context)
2026-09-23 실환경 현장 횡단보도 테스트 중 다음과 같은 문제점이 발견되었다:
1. **무신호 횡단보도에서의 신호등 조준 완료(락온) 오표시**:
   - 신호등이 없는 이면도로 횡단보도임에도 화면 상단에 "🎯 신호등 조준 완료 (락온)"이 뜨고 "신호등이 조준되었습니다" 음성이 발화됨.
   - **원인**: 신호등 미검출 시 생성되는 기본 스캐닝 임시 박스(중앙 $0.50, 0.30$)가 뷰파인더 영역 내부에 들어와 있어, `targetSignal.state != UNKNOWN` 여부를 확인하지 않고 무조건 락온으로 판단함.
2. **"핸드폰을 올바로 들어달라" 음성 반복 (각도 임계값 과도한 엄격함)**:
   - 보행자가 발 앞의 횡단보도 페인팅과 건너편을 동시에 비추려 스마트폰을 아래로 기울일 때 Pitch 각도가 $-25^\circ \sim -32^\circ$로 형성됨.
   - 기존 Pitch 하향 임계값이 $-22^\circ$ (히스테리시스 $-16^\circ$)로 너무 엄격하여 `POOR_DEVICE_TILT`가 주기적으로 발동, "스마트폰을 올바른 각도로 들어주세요." / "카메라를 정면으로 들어주세요." 음성이 반복 발화됨.

## 2. 결정 (Decision)

### 1) 락온(Lock-on) 조건의 유효 신호 종속화 (`CrossingAssistViewModel.kt`)
- `isSignalInReticle` 판정 시 유효한 신호(RED 또는 GREEN)가 실제로 검출된 경우에만 조준 완료로 인정:
  ```kotlin
  val isActualSignalDetected = targetSignal != null && targetSignal.state != ObservedSignalState.UNKNOWN
  val isInsideReticle = if (isActualSignalDetected && targetBox != null) {
      val cx = (targetBox.left + targetBox.right) / 2f
      val cy = (targetBox.top + targetBox.bottom) / 2f
      cx in reticle.left..reticle.right && cy in reticle.top..reticle.bottom
  } else false
  ```
- 미검출 더미 박스(`state == UNKNOWN`)에서는 락온 뱃지와 조준 음성을 전면 차단하고 `신호등을 네모 안에 맞추세요` 탐색 상태 유지.

### 2) 자연스러운 보행 화각을 위한 Pitch 각도 완화 (`DevicePoseTracker.kt`, `CrossingDecisionEngine.kt`)
- **`ProductionDevicePoseTracker.evaluateGuidance`**:
  - 하향 한계선: 기존 $-22^\circ$ (복귀 $-16^\circ$) $\to$ **$-35^\circ$ (복귀 $-28^\circ$)**
  - 상향 한계선: 기존 $+35^\circ$ $\to$ **$+40^\circ$**
  - 보행자가 횡단보도 진입 대기 시 발 앞 지면을 내려다보는 자연스러운 파지 각도($-20^\circ \sim -32^\circ$)를 `SUITABLE`로 안정적 수용.
- **`CrossingDecisionEngine.kt`**:
  - `devicePose.pitchDegrees` 허용 범위를 $-38^\circ \sim +55^\circ$로 동기화 완화하여 오경보 차단.

### 3) 무신호 횡단보도 상황 명확화 (`CrossingAssistScreen.kt`, `CrossingAssistViewModel.kt`)
- 횡단보도는 감지되었으나 신호등이 없는 경우:
  - 안내 문구를 `"신호 미인식 (무신호 주의)"` / `"횡단보도 감지됨 (신호등 미인식 / 무신호 주의)"`로 변경하여 사용자에게 현장 상황을 명확하게 피드백.

## 3. 결과 및 영향 (Consequences)
- **오조준/허위 락온 0건 달성**: 실제 신호등 램프가 감지되지 않으면 락온이 걸리지 않음 (`testSignalUnknownDoesNotLockOnReticle` 단위 테스트 통과).
- **각도 경고 피로도 대폭 감소**: 발 앞 횡단보도를 안정적으로 촬영하면서도 "올바로 들어달라"는 반복 경고음이 발생하지 않음.
- **무신호 횡단보도 직관적 인지**: 시각장애인 및 저시력자가 신호등이 없는 횡단보도임을 즉각 청각/시각으로 인지 가능.
- **전체 단위 테스트 통과**: `./gradlew testDebugUnitTest` 100% 통과 및 최신 디버그 APK 빌드 완료.
