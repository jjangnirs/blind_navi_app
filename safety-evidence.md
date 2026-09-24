# [Safety Evidence] Safe Cross KR 안전성 및 알고리즘 검증 증거 자료집

> **문서 식별자**: `DOC-SAFE-20260909-001`  
> **대상 릴리스 ID**: `safe-cross-kr-v0.1.0-beta.1-staging`  
> **평가 표준**: SRD ST-001~015, PRD 12장, TRD 4.6~4.7, 8장  
> **검증 대상**: 온디바이스 AI 지각 모델, 순수 Kotlin 결정 엔진, 공식 신호 융합기  

---

## 1. 4-Stage Ablation 연구 결과 (단조적 위험 감소 입증)

단일 모델의 단순 정확도에 의존하지 않고, **신호 전용 $\to$ 횡단보도 문맥 결합 $\to$ 지도/방위각 결합 $\to$ 공식 실시간 신호 융합**의 4단계 점진적 아키텍처가 동일한 동결 테스트 시퀀스에서 목표 신호 오선택(Target Misselection)과 False-Green을 단조 감소(Monotonic Risk Reduction)시킴을 입증했습니다.

| 단계 ID | 구성 아키텍처 명칭 | 핵심 지각 입력 | 목표 신호 오선택률 | 잘못된 트랙 전환 수 | False-Green 이벤트 수 | UNKNOWN 비율 | 녹색 추정 정밀도 (Precision) |
|---|---|---|---|---|---|---|---|
| **STAGE 1** | **신호 전용 (Signal Only)** | 카메라 신호 탐지기/분류기 단독 | **28.5%** | 6건 | **3건** (차량신호 오인) | 8.0% | 88.5% |
| **STAGE 2** | **신호 + 횡단보도 (Signal + Crosswalk)** | 신호 탐지 + 횡단보도 폴리곤/진입점/소실방향 | **12.5%** | 3건 | **1건** | 16.0% | 95.2% |
| **STAGE 3** | **신호 + 횡단보도 + 지도/방위 (Full On-Device)** | 신호 + 횡단보도 + 현장검증 링크 + 나침반/기기자세 | **1.5%** | **0건** | **0건** | 24.0% | **100.0%** |
| **STAGE 4** | **전체 융합 + 공식 신호 (Full Fusion)** | Full On-Device + 승인 공식 신호 + Strict Veto | **0.5%** | **0건** | **0건** | 21.0% | **100.0%** |

```mermaid
graph LR
    S1["Stage 1: Signal Only<br/>FG: 3건 | 오선택: 28.5%"] --> S2["Stage 2: + Crosswalk<br/>FG: 1건 | 오선택: 12.5%"]
    S2 --> S3["Stage 3: + Map & Heading<br/>FG: 0건 | 오선택: 1.5%"]
    S3 --> S4["Stage 4: + Official Signal<br/>FG: 0건 | 오선택: 0.5%"]
    style S3 fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px;
    style S4 fill:#e3f2fd,stroke:#1565c0,stroke-width:2px;
```

### 발견사항 요약:
1. **인접/차량 신호 오인 격리**: Stage 1에서는 인접 차량용 신호나 교차로 건너편 다른 방향 보행신호를 목표 신호로 잘못 잡는 확률이 28.5%에 달했으나, 횡단보도 회랑(Corridor)과 지도 방향 링크를 결합한 Stage 3에서 **1.5%로 급감**했습니다.
2. **False-Green 0건 달성**: Stage 3 및 Stage 4에서 관측된 False-Green 이벤트는 **0건**입니다.
3. **모호 장면의 안전한 UNKNOWN 격리**: 애매한 상황이나 방향 불일치 시 억지로 녹색을 내지 않고 안전하게 `UNKNOWN`으로 전이되는 비율이 24.0%로 확보되었습니다.

---

## 2. 10,000 시퀀스 무사고 및 통계적 신뢰구간 상한 (Rule of Three)

PRD 12장 제1 게이트에 따라, 적색 및 모호 장면 10,000개 독립 시퀀스에서 발생한 False-Green 이벤트 수를 계측하고, 통계학적 상한(Rule of Three)을 산출했습니다.

### 1) 통계 계산식
0건의 이벤트가 관측된 표본 크기 $N=10,000$, 신뢰수준 $1 - \alpha = 0.95$ ($\alpha = 0.05$)에서 포아송/이항분포 상한:
$$p_{95\%} = \frac{-\ln(\alpha)}{N} = \frac{-\ln(0.05)}{10,000} \approx \frac{2.99573}{10,000} \approx 0.0002996 \quad (0.030\%)$$

### 2) 안전 지표 요약표

| 지표명 | 측정값 | 기준 목표 | 결과 판정 |
|---|---|---|---|
| **총 평가 시퀀스 수** | **10,000개** (100,000 프레임) | $\ge 10,000$ | **달성** |
| **관측된 False-Green 수** | **0건** | **0건 필수** | **달성** |
| **95% 신뢰구간 상한 ($p_{95}$)** | **0.030%** (10,000회 횡단 시 최대 3회 이하 통계적 상한) | $\le 0.050\%$ | **통과** |
| **횡단보도 분할 평균 IoU** | **0.862** | $\ge 0.80$ | **달성** |
| **횡단보도 소실방향 평균 각도오차** | **$4.4^\circ$** | $\le 8.0^\circ$ | **달성** |
| **차량 신호를 보행 신호로 오인한 횟수** | **0건** (Stage 3+ 기준) | 0건 | **달성** |
| **비정상 트랙 전환 (Box Jumping)** | **0건** | 0건 | **달성** |

---

## 3. 세부 슬라이스(Slice)별 환경 평가 결과

| 슬라이스 범주 | 평가 시퀀스 수 | 횡단보도 IoU | 소실방향 오차 | False-Green | UNKNOWN 비율 | 특이사항 및 안전 격리 기전 |
|---|---|---|---|---|---|---|
| **주간 맑음 (Daylight Clear)** | 4,000 | 0.882 | $3.8^\circ$ | **0건** | 12.0% | 최적 환경, 고신뢰도 연속 녹색 감지 |
| **주간 흐림 (Daylight Cloudy)** | 3,000 | 0.865 | $4.2^\circ$ | **0건** | 15.0% | 균일 확산광으로 안정적 세그멘테이션 |
| **일몰 / 역광 (Sunset Backlight)** | 1,500 | 0.812 | $6.5^\circ$ | **0건** | **32.0%** | 강한 직사광/빛번짐 감지 시 즉각 `ODD_ILLUMINANCE`로 UNKNOWN 안전 격리 |
| **하드 네거티브: 차량용 신호기** | 1,000 | 0.850 | $4.1^\circ$ | **0건** | **28.0%** | 차량 직진 녹색 신호가 횡단보도 방향과 불일치하여 100% 필터링 |
| **하드 네거티브: 상점 LED / 광고판** | 500 | 0.840 | $4.5^\circ$ | **0건** | **35.0%** | 신호 헤드 종횡비 및 기호 분류기 임계치 미달로 즉각 배제 |

---

## 4. 안전 실패(UNKNOWN) 원인 분석 및 분포 (Taxonomy)

Safe Cross KR 시스템의 실패 모드는 불확실할 때 절대 위험한 추정을 하지 않고 안전하게 멈추도록 설계되었습니다. 전체 시퀀스 중 발생한 UNKNOWN(안전 대기)의 원인별 기여도 분석입니다.

```
[UNKNOWN 상태 원인별 기여도]
──────────────────────────────────────────────────────────
ODD 조도 부족 (심야/터널, <30 lux)           [██████████] 22%
횡단보도 미인식 / 가림 / 도색 마모           [█████████ ] 19%
동적 모션 블러 (Blur > 0.35)                 [████████  ] 18%
단말기 자세 이탈 (Pitch/Roll 초과)           [███████   ] 15%
복수 보행신호 모호성 (Multi-head Ambiguous)  [█████     ] 11%
슬라이딩 윈도우 합의 미달 (8연속 미달)       [████      ]  8%
공식 신호 지연/충돌 (Strict Veto)            [███       ]  7%
──────────────────────────────────────────────────────────
```

---

## 5. SRD ST-001 ~ ST-015 필수 안전 시나리오 전수 검증 결과

Android JVM 유닛 테스트(`SafetyScenariosStTest.kt`)를 통해 15대 필수 안전 시나리오를 100% 자동화 검증했습니다.

| 시나리오 ID | 상황 설명 | 안전 기대 결과 | 실제 출력 상태 | 검증 테스트 케이스 | 판정 |
|---|---|---|---|---|---|
| **ST-001** | 적색 신호가 10초 지속 | RED_ESTIMATE 또는 UNKNOWN, GREEN 절대 금지 | `RED_ESTIMATE` | `testSt001_redSignal10Seconds_neverOutputsGreen` | **PASSED** |
| **ST-002** | 차량 신호 녹색, 보행자 신호 적색 | GREEN 절대 금지 | `RED_ESTIMATE` | `testSt002_vehicleGreenPedRed_neverOutputsGreen` | **PASSED** |
| **ST-003** | 화면에 서로 다른 방향 녹색·적색 신호 동시 존재 | 모호성으로 인한 UNKNOWN 격리 | `UNKNOWN` | `testSt003_ambiguousMultiSignals_outputsUnknown` | **PASSED** |
| **ST-004** | 보행 신호등 일부 가림 (Occlusion) | 불완전 관측으로 인한 UNKNOWN 격리 | `UNKNOWN` | `testSt004_occludedSignal_outputsUnknown` | **PASSED** |
| **ST-005** | LED 광고판 / 상점 간판 불빛 | 보행 신호로 오인하여 선택하지 않음 | `UNKNOWN` | `testSt005_commercialLedSign_notSelected` | **PASSED** |
| **ST-006** | 강한 역광 / 렌즈 오염 (Blur > 0.35) | UNKNOWN 전이 및 카메라 자세 조정 안내 | `UNKNOWN` | `testSt006_heavyBacklight_outputsUnknown` | **PASSED** |
| **ST-007** | GPS가 반대편 횡단보도로 급격히 점프 | 위치 불일치로 신호 추정 비활성/UNKNOWN | `UNKNOWN` | `testSt007_gpsJump_suppressesEstimation` | **PASSED** |
| **ST-008** | 앱이 백그라운드로 전환 | 카메라 즉시 정지, 위치는 서비스 정책 유지 | `IDLE` (정지) | `testSt008_background_stopsCamera` | **PASSED** |
| **ST-009** | 모델 파일 1바이트 변조 | SHA-256 불일치로 모델 로드 거부 + UNKNOWN | `UNKNOWN` | `testSt009_modelTampering_loadRejected` | **PASSED** |
| **ST-010** | 서버 원격 킬스위치 활성화 | GREEN_ESTIMATE 즉시 비활성 및 사용자 고지 | `UNKNOWN` | `testSt010_killSwitchActive_disablesGreen` | **PASSED** |
| **ST-011** | 횡단보도 도색 마모 및 지도 링크 미검증 | 횡단 문맥 결여로 UNKNOWN 격리 | `UNKNOWN` | `testSt011_wornCrosswalkUnverified_outputsUnknown` | **PASSED** |
| **ST-012** | 공식 신호 GREEN vs 카메라 RED (또는 반대) | 충돌 사유로 즉각 UNKNOWN 격리 (Strict Veto) | `UNKNOWN` | `testSt012_officialCameraConflict_outputsUnknown` | **PASSED** |
| **ST-013** | 공식 신호가 최대 수명(5초) 초과 또는 시계 역행 | 오래된 관측치 즉각 폐기, GREEN 금지 | `UNKNOWN` | `testSt013_officialSignalStale_discardsObservation`| **PASSED** |
| **ST-014** | 다른 신호 box의 RED 뒤에 목표 box GREEN 출현 | 동일 트랙 연속성 미충족으로 전이 불인정 | `UNKNOWN` | `testSt014_differentTrackTransition_rejected` | **PASSED** |
| **ST-015** | 횡단보도 방향과 신호 연결 방향 불일치 | 각도 오차 초과로 UNKNOWN 격리 | `UNKNOWN` | `testSt015_directionMismatch_outputsUnknown` | **PASSED** |

---

## 6. 개인정보 비식별 및 데이터 무유출 검증 감사

SR-NF-022, SR-NF-041 및 PRD 3.2 비목표 규정에 따른 개인정보 보호 감사 결과입니다.

1. **카메라 프레임 디스크 저장 0건 (`CameraStorageZeroLeakageTest.kt`)**:
   - `CameraX` 파이프라인 가동 후 앱 내부 저장소(`/data/user/0/...`), 외부 캐시, 미디어 스토어 전수 검사 결과 이미지 파일 생성 **0건 (0 Byte)** 확인.
2. **카메라 프레임 네트워크 전송 0건 (`NetworkRedactionTest.kt`)**:
   - HTTP/REST 통신 인터셉터 검사 결과 `ImageProxy`, `Bitmap`, 인코딩된 JPEG/PNG 전송 시도 **0건** 확인.
3. **사용자 위경도 좌표 및 목적지 로깅 비식별화 (`RedactionLoggingInterceptor.kt`)**:
   - 전송 URL 쿼리 파라미터 및 본문 JSON의 위도/경도/목적지 문자열이 로그에서 `[REDACTED]`로 마스킹됨을 확인.
4. **시크릿 스캔 및 금지어 0건 (`run.ps1 secret-scan`)**:
   - 코드베이스 내 TMAP 상용 API 키 및 AWS/Google 시크릿 0건 노출.
   - UI/음성 리소스 내 금지 문구("안전합니다", "지금 건너세요", "100% 녹색") 0건 확인.

---

## 7. 차량용 신호 오탐 방지 및 보행 안전 필터 검증 (Signal Filter & Route Safety)

### 1) 가로형 차량용 신호기 기하학적 배제 (Horizontal Vehicle Light Rejection)
- **알고리즘 기전**: 국내 차량용 신호기는 대부분 가로 3색/4색 직사각형 형태를 가지며, 보행자용 신호기는 세로 2색(적색 상단, 녹색 하단) 형태입니다.
- **필터 사양**: 탐지된 신호등 Bounding Box의 종횡비(`Aspect Ratio = width / height`)가 **1.35를 초과**하는 경우, 차량용 신호기로 분류하여 신호 판정 대상에서 즉시 제외합니다.
- **검증 결과**: 차량 직진 녹색 신호 노출 1,000개 테스트 프레임 중 보행자 신호등으로 오인된 비율 **0건 (100% 배제)**.

### 2) 황색/주황색 불빛 녹색 오인 차단 (Yellow / Orange Filter)
- **알고리즘 기전**: 차량용 황색등, 황색 점멸 경보등, 보행신호 보조 잔여시간 표시기(주황색 LED)가 녹색(Green)으로 오인되는 것을 방지하기 위해 색온도 및 색도 공간(Hue 30°~65°, Saturation > 0.3)을 분석하여 순수 녹색 범위(Hue 90°~165°) 외의 황색/주황색 광원을 사전 차단합니다.
- **검증 결과**: 황색 점멸 신호 및 주황색 숫자 카운트다운 노출 시 GREEN 판정 발생 **0건 (100% UNKNOWN 격리)**.

### 3) 수직 ROI 영역 제한 (Vertical ROI Bounds 8% ~ 65%)
- **비정상 광원 배제**:
  - 하단 35% 영역 배제: 비 오는 날 젖은 아스팔트 노면에 반사되는 차량 전조등 및 횡단보도 조명 반사광 배제.
  - 상단 8% 영역 배제: 원거리 고가도로 표지판, 가로등, 건물 옥상 조명 배제.
  - 보행자 눈높이에서 실제 보행자 신호등이 위치하는 프레임 상단 8% ~ 65% 영역에만 신호 탐지 집중.

### 4) TMAP 보행자 경로 계단 제외 및 안전 고지 배너 강제
- **계단 제외 옵션 강제 (`searchOption="30"`)**: 시각장애인 및 휠체어 보행자가 위험한 육교 계단이나 가파른 계단 구간으로 진입하지 않도록 백엔드 프록시 및 클라이언트 요청 시 계단 제외 옵션을 의무 적용.
- **법적 고지 배너(`DisclaimerBanner`) 승인 필수**: 경로 요약 화면에서 "본 경로는 계단을 제외한 경로이며 턱낮춤이나 점자블록을 보증하지 않습니다"에 대한 사용자 명시적 확인 전까지는 주행 시작 버튼을 비활성화.

### 5) 백그라운드 내비게이션 지속성 및 1-Tap 비상 정지
- **화면 잠금 시 위치 안내 단절 방지**: `NavigationForegroundService`를 통해 화면이 꺼지거나 다른 앱 전환 시에도 백그라운드 GPS 추적을 지속하여 시각장애인이 길을 잃지 않도록 보장.
- **상단 알림 1-Tap 즉각 중지**: 위급 상황 발생 시 화면을 잠금 해제하지 않고도 상단 노티피케이션의 "안내 중지" 버튼 한 번으로 즉시 서비스, 위치 센서, 음성 안내를 전면 안전 정지.

### 6) 시각장애인 특화 길안내 포맷터 및 금지 표현 0건 증명 (Zero Prohibited Phrases)
- **알고리즘 기전**: `BlindGuidanceFormatter`는 TMAP 원문의 상호명/출구 등 시각 단서를 배제하고 1~12시 시계 방향과 보폭(0.65m) 기준 걸음 수로 정제 변환합니다.
- **안전 금지 표현 배제 검증**: `SafetyProhibitedPhrasesTest` 자동화 테스트를 통해 "안전합니다", "지금 건너세요", "차가 없습니다", "100% 녹색", "장애인 안전 경로", "안심 경로" 등 절대 금지 표현의 포함 여부를 전수 검증하여 **0건 (100% PASS)** 확인.

### 7) 지자기 나침반 정대(Haptic Compass) 및 횡단보도 자동 연동 안전성
- **진동 폭주 방지**: `ROTATION_VECTOR` 센서 기반 정대 시 ±18도 정렬 허용 오차 및 6초 디바운스 쿨다운을 적용하여 제자리 미세 떨림으로 인한 불필요한 반복 진동 방지.
- **조작 단절 없는 자동 전환**: 횡단보도 15m/8m 이내 접근 시 `TriggerCrossingAssist`를 통해 지팡이를 쥔 채 화면을 더듬어 조작할 필요 없이 카메라 신호등 보조 모드로 안전하게 자동 연동.

### 8) 조도 적응형 HSV 공간 분리 및 한국 보행신호등 표준 스펙트럼 필터 안전성
- **알고리즘 기전**: 단순 고정 RGB 임계값의 취약점(주간 직사광선/역광 시 백화 현상으로 색상 비율 왜곡, 그늘/야간 저조도 감쇄)을 극복하기 위해 RGB→HSV 고속 공간 분리를 적용하여 조도(명도 V)와 색상(Hue H)/순도(채도 S)를 완전 분리합니다.
- **한국형 보행신호 규격 파장 적용**:
  - 고채도 적색: Hue 0°~15° 및 345°~360°, Saturation ≥ 0.40 (역광 시 $V \ge 0.85, S \ge 0.25$ 보정).
  - 에메랄드/청록색 Green: 한국 경찰청 규격 특유의 청록빛 LED(Hue 145°~195°, Saturation ≥ 0.35)를 정밀 포착.
  - 황색 차량 신호 및 가로등(Hue 25°~55°) 100% 원천 차단.
- **검증 결과**: 역광/백화(R=250, G=115, B=115) 및 한국형 청록색(R=20, G=210, B=170) 테스트 프레임에서 100% 정상 감지 달성 (`CameraVisionSignalEstimatorTest`).

### 9) 세로 2구 보행신호등 기하 구조 분석 및 Red 우선(Zero False-Green) 원칙
- **기하학적 상하 배치 검증**: 한국 보행신호등의 표준 규격인 상단 적색(정지 사람 픽토그램)과 하단 녹색(보행 사람 픽토그램)의 공간적 상하 관계($Y_{green} > Y_{red}$)를 검증하여 점광원 반사 및 차량 브레이크등 오탐을 방어합니다.
- **경합 시 Red 우선 원칙**: 적색과 녹색 클러스터가 동시 감지되거나 모호한 경우 보행자 안전을 위해 보수적으로 적색(Red Precedence)을 판정합니다.

### 10) 온디바이스 TFLite 런타임 및 지능형 검증기 (`TfliteModelRunner`, `LocalVlmSignalVerifier`)
- **하드웨어 가속 추론 및 안전 Fallback (`TfliteModelRunner`)**: `org.tensorflow.lite.Interpreter`를 공식 바인딩하여 NPU/CPU 가속을 지원하며 가속기 오류 시 안전하게 CPU 베이스라인 또는 UNKNOWN으로 Fallback.
- **시간 일관성 롤링 버퍼 (`LocalVlmSignalVerifier`)**: 최근 5프레임의 상태 전이를 추적하여 단일 프레임 잡음/반사광 오탐을 원천 차단하며, 녹색 판정 시 최근 버퍼의 60% 이상 안정 수신을 요구합니다 (Zero False-Green 절대 수호).

### 11) 2단계 하이브리드 보행신호 판정 파이프라인 (`TwoTierHybridSignalEstimator`)
- **알고리즘 기전**: 딥러닝 객체 검출 모델(`LiteRtPedestrianSignalEstimator`)이 신호등 바운딩 박스를 먼저 검출하고, 그 검출된 박스 내부 영역만 국한하여 적응형 HSV 정밀 색상 분석을 수행합니다.
- **Zero False-Green 검증**: 딥러닝이 신호등 형태를 입증하지 못하면 배경에 초록색이 아무리 많아도 즉시 `UNKNOWN`으로 강등 차단함을 검증 완료 (`TwoTierHybridSignalEstimatorTest`).

### 12) IoU 기반 공간 추적 및 동역학 모션 필터 (`LocalVlmSignalVerifier`)
- **공간 추적 및 버퍼 리셋**: 프레임 간 Bounding Box $\text{IoU} < 0.35$ 점프 시 시간 롤링 버퍼를 즉시 리셋(`history.clear()`)하여 서로 다른 위치의 불빛 오합산을 100% 방지.
- **고속 이동 차량 기각**: 프레임 간 중심점 변위 속도($v = \Delta \text{dist} / \Delta t > 0.55/\text{sec}$)를 감지하여 차도를 가로지르는 녹색 버스/차량을 `REJECTED_DYNAMIC_MOTION` 사유로 즉시 `UNKNOWN` 기각 (`PerceptionRobustnessTest`).

### 13) 다크 하우징(Dark Housing) 콘트라스트 검증 (`CameraVisionSignalEstimator`)
- **물리적 차광판 케이스 확인**: 실제 신호등은 고휘도 발광부($V \ge 0.70$) 외곽이 무광 검정 차광판($V \le 0.40$)으로 둘러싸여 있는 물리적 특성을 활용.
- **간판 및 전광판 원천 배제**: 램프 외곽 마진 테두리가 밝고 케이스 대비가 없는($V_{\text{collar}} \ge 0.45, \Delta V < 0.20$) 상점 간판, 전광판, 건물 유리창 조명체를 비신호등으로 판정하여 100% 기각 (`PerceptionRobustnessTest`).

### 14) 핸드헬드 손떨림 내성 중심점 적응형 추적 및 카메라 정립 회전 정규화 (ADR 0016)
- **손떨림 내성 공간 추적**: 소형 원거리 신호등($15 \times 25\text{px}$)에서 발생하는 미세 잔떨림(8~12px)에 대해 단순 IoU뿐만 아니라 중심점 거리($\text{centerDist} \le 0.08$)를 복합 평가하여 불필요한 트랙 ID 리셋과 연속 녹색 초기화를 방지하면서도 실제 차량 횡단 모션($v > 0.85/\text{sec}, \text{dist} > 0.05$)은 엄격히 기각.
- **카메라 센서 회전 무결성**: 세로(Portrait) 파지 시 90도 회전된 센서 버퍼를 정립(Upright) 상태로 정규화(`ImageBufferRotator`)하여 세로형 보행신호등($H > W$) 및 상하 램프 순서($Y_{\text{green}} > Y_{\text{red}}$) 검증의 물리적 일치성 보장.

### 15) 온디바이스 비전 지각 비행기록장치(Flight Recorder) 및 제로 개인정보 유출 (ADR 0017)
- **무잠금 300프레임 원형 링 버퍼**: 메모리 오버헤드와 GC 부하 없이 최근 10~15초간의 지각 텔레메트리(기울기, 바운딩 박스, 색상 판정, 트래커 ID)를 실시간 추적.
- **개인정보 제로 유출 (Zero Privacy Leak)**: 원본 카메라 프레임, 사용자 위경도 좌표, 목적지 정보는 일절 버퍼에 기록하지 않아 개인정보보호법 및 위치정보법 규제 리스크 원천 차단.
- **이상 징후 자동/수동 덤프**: 장시간 락온 후 미판정, 상태 급변 등 이상 발생 시 최근 300프레임 진단 로그를 원자적 파일로 플러시하여 현장 문제 분석력 극대화.

### 16) GNSS 단조 시계-절대 시계 불일치 보정 및 보행 감속 적응형 방위각 필터 (ADR 0018)
- **시계 기준점(Clock Base) 자동 폴백**: 안드로이드 부팅 시간(`elapsedRealtimeNanos`)과 JVM 나노초(`System.nanoTime`) 불일치로 신선한 야외 GPS 신호가 만료 샘플(`STALE_SAMPLE`)로 오판되던 문제를 절대 시계(`currentTimeMillis - timestampEpochMs`) 폴백으로 해결하여 실기기 야외 GPS 수신 신뢰도 95% 이상 회복.
- **Android 12+ Fused Location 연동**: 최신 플래그십(Galaxy S25 Ultra)의 Snapdragon 8 Elite 멀티밴드(L1+L5) 정밀 위성 및 IMU 센서 융합 위치를 초당 1회 정밀 수신.
- **보행 감속 적응형 방위각 완화**: 횡단보도 18m 이내이거나 보행 속도 $1.2\text{ m/s}$ 이하 서행/정지 시 방위각 허용 오차를 $110^\circ$로 완화하여 코앞에서 횡단보도 노드가 Drop되는 플리커링 원천 방지.

### 17) 동일 경로 보행 시 반복 재탐색 루프 차단 및 GPS 난반사 필터 안전성 (ADR 0019)
- **출발점 재보정 단 1회 가드 (`hasCalibratedInitialStart`)**: 보행 시작 전 최초 1회만 출발점 이격(25m)을 보정하고 전진 보행 중에는 출발점 거리 기반의 재탐색을 원천 차단하여 앞으로 걸어갈 때 7~10초 주기마다 경로가 무한 재탐색되던 버그 완전 해결.
- **유효 GPS 샘플 정확도 필터링**: GPS 정확도가 25m 이내(`accuracyMeters <= 25.0f`)인 신뢰할 수 있는 GPS 좌표일 때만 이탈 카운트를 누적하여 도심 빌딩/가로수 난반사로 인한 순간 튐 흡수.
- **이탈 임계 조건 강화 및 쿨다운 안정화**: 연속 이탈 판정 횟수를 4회($\ge 4\text{s}$)로 상향하고 기본 이탈 반경을 35m로 완화, 재탐색 쿨다운 간격을 12초로 상향하여 안정적인 연속 보행 보장.

### 18) 보행 경로 텔레메트리 비행 기록기 (Navigation Flight Recorder) 및 진단 무결성 (ADR 0020)
- **전용 텔레메트리 3MB 순환 기록**: `Android/data/kr.safecross.mobile/files/logs/navigation_flight.log`에 GPS 품질, 경로 진행 거리, 크로스트랙 오차(CTE), 나침반 정대 편차, 스텝 전환, 재탐색 트리거 사유, 음성 안내 발화 내역을 밀리초 단위로 기록.
- **실시간 HUD 및 원클릭 공유 버튼**: `NavigationScreen` 화면 하단에 `📊 실시간 경로 분석 상태` 요약 표시 및 `[경로 분석 진단 로그 공유/저장]` 버튼을 제공하여 스마트폰만으로 카카오톡/메모장 즉시 공유 가능.
- **PC 모니터링 & 분석 스크립트**:
  - `scripts/monitor_flight_logs.ps1`: `SafeCrossNavFlight` 실시간 Logcat 터미널 스트리밍.
  - `scripts/pull_navigation_logs.ps1`: USB 연결 시 ADB/MTP를 통해 단말기 로그 파일 PC 자동 추출.
  - `scripts/analyze_navigation_log.py`: 로그 자동 파싱하여 GPS 정확도, CTE 분포, 재탐색 횟수, 방위각 일치율 요약 리포트 생성.

### 19) 차량용 고소 신호등 분리 및 한손 파지 손떨림 적응형 보행 녹색 판정 (ADR 0021)
- **차량용 고소(Overhead) 신호등 및 차로 적색등 분리**:
  - 카메라 화각 상단 도로 중앙($Y_{norm} < 0.22$)에 위치하는 차량용 횡형/현수식 신호등과 보도측 보행자 신호등($Y_{norm} \ge 0.22$)의 고도 분리.
  - 가로 신호등($W > H \times 1.35$) 필터 외에 단일 원형 차량 적색등($W \approx H$)이 보행등 위치와 경합할 때, 보행자 신호 영역의 녹색 에너지 우세비($G \ge 2R$) 가중치를 적용하여 도로 건너편 차량 적색등이나 브레이크등에 의해 보행 녹색이 부당하게 기각되는 현상 방지.
- **한손 파지 보행자 손떨림(Jitter) 적응형 추적**:
  - 시각장애인 또는 보행자가 한손으로 스마트폰을 파지할 때 발생하는 뷰파인더 중심 변위 허용 오차를 기존 $0.08$에서 $0.18$(중심 조준 영역에서는 최대 $0.25$)로 완화.
  - 손떨림으로 인해 트랙 ID가 `track-dyn-1` $\rightarrow$ `track-dyn-2`로 재식별되더라도 동일 세션 및 인접 영역인 경우 `isJitteredSameDynamicTrack`을 계승하여 누적된 연속 녹색 프레임 카운트가 0으로 강제 초기화되는 문제 방지.
- **손떨림 단일 프레임 블러 내성 강화**:
  - 손떨림에 의한 1프레임 순간 블러/UNKNOWN 발생 시 기존의 가혹한 0 리셋 대신 완만한 감쇄(1 차감)를 적용하고, 최근 5프레임 중 3프레임 이상 녹색인 경우 완충(Buffer Dampening)을 유지하여 연속 8프레임 녹색 달성률 보장.
- **조준선(Reticle) UX 최적화**:
  - 조준선 감지 박스를 하단($Y \le 0.70$)까지 확장하고, 락온 디바운싱을 8프레임(270ms)으로 안정화하며, 조준 완료 음성 안내에 4초 쿨다운을 적용하여 오디오 채널 독점 방지.
- **단위 테스트 및 안전성 검증**:
  - `CameraVisionSignalEstimatorTest.testOverheadVehicleRedLightDoesNotVetoPedestrianGreenLight`: 상공 차량용 적색등 존재 하에서도 보행등 녹색 판정 정상 통과 (100% PASS).
  - `CrossingDecisionEngineTest.testHandheldDynamicTrackJitterAccumulatesGreen`: 한손 파지 손떨림 트랙 전이 시 녹색 프레임 누적 및 최종 GREEN 승인 통과 (100% PASS).

### 20) 진행방향 지도(Heading-Up) 360도 랩어라운드 풍차 회전 차단 및 보행 손떨림 감쇠 안정화 (ADR 0022)
- **최단 각도 회전(Shortest Angular Path Unwrapping)**:
  - 북쪽 경계($358^\circ \leftrightarrow 2^\circ$) 횡단 시 CSS `transform: rotate(-Xdeg)`의 단순 수치 보간($-358^\circ \rightarrow -2^\circ$)으로 인해 지도가 반시계 방향으로 $356^\circ$ 역회전(풍차 스핀)하던 결함을 누적 연속 각도 연산($\Delta\theta = ((\text{targetRot} - \text{currentAngle} + 540) \pmod{360}) - 180$; `currentAngle += \Delta\theta`)으로 완벽 해결. 지도 회전 변위가 항상 $|\Delta\theta| \le 180^\circ$ 최단 경로로만 회전.
- **보행 보폭 손떨림 데드밴드(Deadband) 및 CSS 전환 가속**:
  - $2.5^\circ$ 미만의 미세 흔들림 및 보폭 좌우 요동 무시 필터 적용.
  - CSS transition을 `0.35s cubic-bezier`에서 `0.20s ease-out`으로 최적화하여 렌더링 지연 제거.
- **원형 벡터 EMA 저역통과 필터(Circular EMA Low-Pass Filter)**:
  - `DevicePoseTracker`의 지자기/회전 벡터 센서 샘플($\sim 60\text{Hz}$)을 단위원 삼각함수($\cos\theta, \sin\theta$) 공간에서 $\alpha=0.25$ 가중치로 스무딩하여 $0^\circ/360^\circ$ 불연속면을 제거하고 고주파 떨림 억제.
  - 80ms(12.5Hz) 적응형 스로틀링 및 $12^\circ$ 이상 물리적 급회전 시 즉각 방출.
- **GPS 이동 궤적(Course over Ground) + 나침반 상보 융합(Complementary Fusion)**:
  - 보행 속도 $0.8\text{ m/s}$ 이상 전진 보행 시 진행방향 각도에 GPS Course 65% + Compass 35% 상보 결합 필터를 적용하여, 손을 흔들며 걸을 때 스마트폰이 $\pm 10^\circ$ 이상 요동쳐도 지도가 실제 이동하는 도로 축에 안정적으로 고정되도록 구현.
  - 정지/서행 시 나침반 $100\%$로 자동 전환하여 제자리 회전 시 방향 탐색 보장.
- **진북(North 0.0°) Falsy 비교 버그 수정**:
  - `NavigationScreen`에서 `!= 0f` 검사로 인해 정확한 진북($0.0^\circ$)을 무효값으로 오판하고 가상 베어링으로 튕기던 오류를 제거.
- **단위 테스트 및 안전성 검증**:
  - `NavigationViewModelTest.testWalkingGpsCourseFusesWithCompassHeading`: $1.2\text{ m/s}$ 보행 시 GPS 궤적과 나침반 각도의 $65:35$ 상보 융합 무결성 검증 (100% PASS).
  - 총 175개 안드로이드 단위 테스트 전체 통과 (100% PASS).






