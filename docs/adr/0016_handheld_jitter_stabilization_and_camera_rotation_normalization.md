# ADR 0016: 핸드헬드 손떨림 내성 추적 안정화 및 카메라 회전 정규화

## 1. 문맥 (Context)
2026-09-24 10:52 횡단보도 실환경 보행신호 테스트 중 녹색 보행 신호("파란색")가 켜지고 보행자들이 건너고 있었으나, 앱에서는 `"신호 미인식 (무신호 주의)"` 상태가 지속되는 문제가 발생하였다. 현장 사진(`media_1790215370779.jpg`) 분석 및 코드 정밀 추적 결과 다음 원인들이 규명되었다:

1. **소형 원거리 신호등의 손떨림에 의한 IoU 급감 및 Track 리셋 루프**:
   - 15~20m 원거리 신호등은 화면 상 바운딩 박스가 소형($15 \times 25\text{px}$, normalized width $\approx 0.03$)임.
   - 단말기를 손으로 들고 있을 때 필연적으로 발생하는 8~10픽셀의 미세 잔떨림($\Delta x \approx 0.018$)만으로도 프레임 간 IoU가 $0.20 \sim 0.28$로 급락.
   - `LocalVlmSignalVerifier.kt`의 `iou < 0.35f` 조건에 걸려 5프레임 롤링 버퍼가 매번 지워지고(`history.clear()`) 새로운 Track ID(`track-dyn-N`)가 부여됨.
   - `CrossingDecisionEngine.kt`에서 `lastObservedTrackId != ephemeralTrackId`가 감지되어 `consecutiveGreenCount`가 0으로 지속 초기화됨.
2. **동적 객체(차량) 속도 필터의 손떨림 오인**:
   - 30 FPS 환경($\Delta t \approx 33\text{ms}$)에서 8.7픽셀의 미세 진동 발생 시 순간 속도 $v = 0.55\text{/sec}$ 도달.
   - 정지된 보행신호등이 고속 횡단 차량으로 오판되어 `REJECTED_DYNAMIC_MOTION`으로 기각됨.
3. **CameraX ImageAnalysis 버퍼 회전 미반영**:
   - 스마트폰 세로(Portrait) 파지 시 센서 버퍼는 가로형(640x480)이며 `rotationDegrees = 90`임.
   - 버퍼가 회전되지 않은 상태로 전달되어 세로형 신호등($H > W$)이 가로형($W > H$)으로 읽혀 `isHorizontalVehicle` 필터 및 종횡비 검증에서 기각되거나 상하 위치 관계가 왜곡됨.
4. **한국형 청록(시안) LED 파장 상한**:
   - 기존 녹색 Hue 필터 상한이 $195.0^\circ$로 설정되어 있어 정오 직사광선 환경의 청록 파장($195^\circ \sim 205^\circ$) 일부 누락 가능.

---

## 2. 결정 (Decision)

### 1) 중심점 거리(Centroid Proximity) 기반 적응형 공간 추적 (`LocalVlmSignalVerifier.kt`)
- 소형 원거리 신호등 박스(`width < 0.12` 또는 `height < 0.15`)에 대해 단순 IoU뿐만 아니라 중심점 간 유클리드 거리(`centerDist`)를 복합 평가:
  ```kotlin
  val isContinuous = if (isSmallBox) {
      iou >= 0.15f || centerDist <= 0.08f
  } else {
      iou >= 0.35f || centerDist <= 0.06f
  }
  ```
- 8~12픽셀 수준의 핸드헬드 손떨림($centerDist \le 0.08f$) 환경에서도 동일 Track ID를 유지하고 5프레임 롤링 히스토리를 온전히 보존.
- 급격한 시선 전환 또는 타 신호등으로의 점프($iou < 0.15f$ AND $centerDist > 0.08f$) 발생 시에만 안전하게 히스토리 리셋 및 신규 Track 분리.

### 2) 손떨림 진동과 차량 횡단 모션 분리 (`LocalVlmSignalVerifier.kt`)
- $dist \le 0.05f$(약 24픽셀) 이하의 미세 변위는 핸드헬드 기기의 정상 진동으로 수용.
- 고속 이동 차량 기각 조건 캘리브레이션: $dist > 0.05f$ AND $velocity > 0.85\text{/sec}$를 동시에 만족할 때만 차량으로 판정하여 기각.

### 3) CameraX 버퍼 정립(Upright) 회전 정규화 (`ImageBufferRotator.kt`, `CameraPipeManager.kt`)
- `ImageBufferRotator` 유틸리티를 신설하여 `imageInfo.rotationDegrees`를 적용한 480x640 정립 RGBA 버퍼로 정규화:
  - 세로형 보행신호등($H > W$), 상하 램프 순서($Y_{green} > Y_{red}$), 차광판 상하 마진 검증이 실제 화면 및 물리 좌표계와 1:1 완벽 일치.
  - `CameraVisionSignalEstimator.kt`에서도 `frame.rotationDegrees != 0`인 경우 자동 정규화 지원.

### 4) 한국형 청록(에메랄드/시안) LED 파장 확장 (`CameraVisionSignalEstimator.kt`)
- 녹색 Hue 범위를 `115.0f..205.0f`로 확장하여 $R=52, G=197, B=202$ ($\text{Hue} \approx 182^\circ \sim 188^\circ$) 등 청록색 LED 전 파장 영역 수용.
- $B \ge G$ 고휘도 청록색 램프에 대한 RGB/HSV 광학 조건 보정.

### 5) Ephemeral Track ID 전파 (`VerificationResult`, `TwoTierHybridSignalEstimator.kt`)
- `verifier.verify()`의 검증 결과에 안정화된 `ephemeralTrackId`를 포함하고, `TwoTierHybridSignalEstimator`가 이를 최종 관측값에 전달하여 `CrossingDecisionEngine`에서 불필요한 트랙 전환 기각이 발생하지 않도록 보장.

---

## 3. 결과 및 영향 (Consequences)
- **손떨림 환경 5프레임 연속 녹색 도달**: 8~12픽셀의 핸드헬드 카메라 진동 하에서도 Track ID가 유지되고 5프레임 연속 녹색 관측이 안정적으로 축적됨 (`testHandheldTremorPreservesTrackContinuityAndAccumulatesGreen` 테스트 통과).
- **한국형 청록 보행신호 감지**: 실측치 $R=52, G=197, B=202$ 청록 LED가 신뢰도 0.90 이상의 `GREEN`으로 정확히 감지됨 (`testKoreanCyanPedestrianSignalHueDetected` 테스트 통과).
- **카메라 버퍼 회전 무결성**: 90도 회전 버퍼 정규화를 통해 세로형 신호등 오분류 방지 (`testImageBufferRotator90DegreesOrientation` 테스트 통과).
- **안전 불변성(Zero False-Green) 유지**: 실제 고속 횡단 차량($v > 0.85, dist > 0.05$) 및 공간 점프는 철저히 기각 및 리셋 유지.
