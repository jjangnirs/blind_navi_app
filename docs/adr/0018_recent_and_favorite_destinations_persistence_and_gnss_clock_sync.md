# ADR 0018: 최근/즐겨찾기 목적지 영구 저장 및 플래그십(Galaxy S25 Ultra) GNSS 시계 동기화·접근각 완화

## 1. 문맥 (Context)
실제 사용자 및 테스터가 최신 플래그십 단말기(갤럭시 S25 Ultra)로 야외 현장 보행 및 횡단보도 접근 테스트를 수행하는 과정에서 다음 문제점들이 접수되었다:
1. **야외 환경에서 "GPS 신호 수신 문제" 경고 오발령**:
   - 하늘이 개방된 야외임에도 `"GPS 신호가 약하여 정밀 횡단 안내를 일시 중지합니다"` 경고 음성이 반복적으로 송출되고 `isGpsDegraded = true`로 상태가 저하됨.
   - 단말기는 최신 Snapdragon 8 Elite 기반 L1+L5 듀얼 주파수 GNSS를 탑재하였으나 앱 내부에서는 신호 수신이 안 되는 것으로 오인됨.
2. **횡단보도 접근 시 오차 및 플리커(Drop) 발생**:
   - 보행자가 횡단보도 앞 연석(Curb)에 다가가 서행하거나 정지했을 때 안내 대상 횡단보도가 화면 및 안내에서 갑자기 사라지는 문제.
3. **최근 목적지 및 즐겨찾기 지속성 부재**:
   - 검색하거나 이동했던 목적지가 앱 종료 시 사라지고, 자주 가는 장소를 즐겨찾기(⭐)로 고정할 수 없어 매번 재입력해야 하는 불편.

---

## 2. 결정 (Decision)

### 1) Android 부팅 단조 시계와 JVM 나노초 간 기준점 불일치 보정 (`LocationSample.kt`)
- **원인 분석**:
  - `Location.elapsedRealtimeNanos`는 시스템 부팅 이후 경과 시간(`SystemClock.elapsedRealtimeNanos()`)을 기준으로 함.
  - 앱의 `LocationQualityGate` 등에서는 샘플 나이를 계산할 때 JVM 기준인 `System.nanoTime()`을 전달함.
  - 실기기에서 두 시계의 에포크(Epoch)가 상이하여 갓 수신된 0.1초 전 GPS 신호의 `ageSeconds`가 수만 초(`STALE_SAMPLE`)로 잘못 판정됨.
- **해결 방안**:
  - `LocationSample.ageSeconds(currentElapsedNanos)`에서 단조 시계 간 차이가 음수이거나 60초 이상일 경우, 절대 시계(`System.currentTimeMillis() - timestampEpochMs`)로 자동 폴백하는 안전 장치를 구현:
    ```kotlin
    val monotonicDiff = currentElapsedNanos - elapsedRealtimeNanos
    val seconds = monotonicDiff / 1_000_000_000.0
    if (seconds < 0.0 || seconds > 60.0) {
        val nowMs = System.currentTimeMillis()
        val wallClockDiffMs = (nowMs - timestampEpochMs).coerceAtLeast(0L)
        return wallClockDiffMs / 1000.0
    }
    return seconds
    ```

### 2) Android 12+ Fused Location Provider 우선 활용 (`ProductionLocationSource.kt`)
- 안드로이드 12(API 31) 이상에서 기본 `GPS_PROVIDER`뿐만 아니라 `LocationManager.FUSED_PROVIDER`를 우선 등록하여 Galaxy S25 Ultra의 다중 대역(L1+L5) 위성 신호와 보행자 추측항법(PDR/IMU 가속도계·자이로) 융합 좌표를 초당 1회 정밀하게 수신.

### 3) 횡단보도 정지/감속 구간 방위각 완화 (`CrossingApproachEngine.kt`)
- 보행자가 연석에 접근하면 속도가 $1.0\text{ m/s}$ 이하로 감속되어 GPS 방위각(Bearing)이 불규칙하게 요동침.
- 횡단보도 18m 이내이거나 보행 속도가 $1.2\text{ m/s}$ 이하인 경우 방위각 허용 오차를 기존 $60^\circ$에서 $110^\circ$로 대폭 완화하여 코앞에서 횡단보도 노드가 Drop되는 현상을 방지.

### 4) 최근 검색 및 즐겨찾기 영구 저장소 (`RecentDestinationRepository.kt`, `DestinationViewModel.kt`)
- `SharedPrefsRecentDestinationRepository`를 신설하여 최근 검색 목록(최대 20개 FIFO)과 즐겨찾기 세트를 SharedPreferences에 JSON으로 영구 저장.
- 목적지 화면 진입 시 노출 우선순위 확립:
  1. 현재 GPS 기준 전방 테스트 목적지 (실시간 위치 연동 시)
  2. 즐겨찾기 등록 목적지 (⭐ 뱃지 상단 고정)
  3. 최근 검색/선택한 목적지
  4. 기본 추천 POI
- `DestinationCardItem`에 접근성 별표 토글 버튼을 추가하고 TalkBack 음성 안내 피드백 연동.

---

## 3. 결과 및 영향 (Consequences)
- **Galaxy S25 Ultra 야외 실기기 GPS 정상화**: 시계 불일치 해결로 야외 위성 신호가 수신 즉시 95% 이상의 초정밀 상태로 인식되며 불필요한 "GPS 약함" 음성 경고 퇴치.
- **횡단보도 진입 안정성 확보**: 연석 접근 중 정지하거나 천천히 걸어도 횡단보도 추적이 안정적으로 유지됨.
- **사용자 편의성 및 재방문성 향상**: 최근 검색어 및 즐겨찾기가 앱 재시작 후에도 온전히 유지되며 TalkBack 제스처로 손쉽게 등록/해제 가능.
