# Safe Cross KR Android Application

시각장애인 및 저시력자를 위한 보행 보조 네이티브 Android 애플리케이션입니다.

## SDK 및 빌드 버전 선택 근거
- **compileSdk / targetSdk**: `35` (Android 15)
  - **선택 이유**: Google Play의 최신 targetSdk 정책 준수 및 최신 Jetpack Compose 접근성 semantics, 음성 오디오 덕킹, 햅틱 피드백 API의 온전한 지원을 확보하기 위해 최신 안정 SDK인 API 35를 선택함.
- **minSdk**: `28` (Android 9.0 Pie)
  - **선택 이유**: [SRD.md](../docs/SRD.md)의 `SR-NF-030` 요구사항(Android API 28 이상 지원)에 따라 기존 보급형 스마트폰 사용자의 접근성을 보장함.
- **Version Catalog**: `gradle/libs.versions.toml`에 모든 라이브러리 및 플러그인 버전을 명시적으로 고정함.

## 보행 안전 및 접근성 한계 필수 고지 (`disclaimer`)
- **배경**: TMAP 보행자 경로 API의 `searchOption="30"`(계단 제외) 옵션은 보도 턱낮춤(2cm 이하), 점자블록, 음향신호기 구비 여부를 일체 보장하지 않습니다.
- **화면 고지**: `RouteSummaryScreen` 상단에 고대비 노란색/검은색 배너(`DisclaimerBanner`, 최소 18sp)로 경고문을 고정 표시하며, 글꼴 200% 확대 시에도 줄임표 없이 전체 문장을 표시합니다.
- **음성 고지 (TTS / TalkBack)**:
  - 화면 진입 시 `TtsAnnouncementHelper`를 통해 면책 고지 전문을 즉시 음성으로 낭독합니다.
  - TalkBack 사용자를 위해 `liveRegion = LiveRegionMode.Polite` 및 전용 semantics를 제공합니다.
  - "주의사항 다시 듣기" 버튼(최소 64dp)을 제공하여 언제든지 고지문을 다시 청취할 수 있습니다.
- **안내 시작 통제**: 사용자가 고지 내용을 인지하고 "확인 후 보행 안내 시작" 버튼(최소 64dp)을 클릭해야만 실제 보행 내비게이션 상태로 전이됩니다.

## 빌드 및 로컬 환경 설정
### 1. JDK 및 Android SDK
- **JDK 17**: Eclipse Temurin OpenJDK 17 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`)
- **Android SDK**: `C:\Users\JJang_Nirs\AppData\Local\Android\Sdk` (API 35 플랫폼 및 Build-Tools 35.0.0, platform-tools 37.0.1)
- `gradle.properties`에 `org.gradle.java.home`이 지정되어 있어 별도 설정 없이 즉시 빌드 가능합니다.

### 2. 명령줄 실행
```powershell
cd android-app

# 단위 테스트 실행
.\gradlew.bat testDebugUnitTest

# 디버그 APK 빌드
.\gradlew.bat assembleDebug
```

## 주요 기능 및 접근성 설계

### 1. 저시력자 전용 초고대비 대형 방향 안내 표시기 (`LowVisionDirectionIndicator`)
- `NavigationScreen` 화면 상단에 4dp 고대비 노란 테두리(`HighContrastYellow` #FFD600)와 순수 흑색 배경(#000000)으로 배치
- 84dp 초대형 원형 심볼 내 56dp 방향 화살표(직진, 좌/우회전, 횡단보도 등)
- 38sp ExtraBold 대형 남은 거리 텍스트 및 24sp 볼드 행동 명칭
- TalkBack 스크린리더를 위한 전용 `contentDescription` 병행 지원

### 2. 분기점 즉시 음성 안내 및 사전 알림
- 회전 및 분기점(`currentManeuverIndex`) 변경 시 기존 큐를 취소하고 새 지침을 즉시 음성 발화 (`QUEUE_FLUSH`)
- 분기점 30m 전 및 15m 전 사전 접근 음성 안내 발화 및 중복 방지

### 4. 시각장애인 특화 길안내 포맷터 (`BlindGuidanceFormatter`)
- **시계 방향(Clock Face) 변환**: "좌회전/우회전" 대신 사용자 헤딩 기준 상대 각도를 1~12시 방향(예: "12시 방향(정면)", "2시 방향", "9시 방향(좌측)")으로 직관적 변환
- **보폭 기준 걸음 수 환산**: 미터(m) 거리를 성인 평균 보폭(0.65m) 기준 걸음 수로 자동 계산하여 "약 35걸음 앞(22미터)" 형태로 제공
- **시각 단서 필터링**: TMAP 원문의 "소망약국 방면으로", "2번 출구 방면" 등 시각장애인에게 무의미한 상호/지명 접미사를 제거하고 보행 행동 중심으로 정제
- **신체 회전 지시**: 진행 방향 편차가 18도 이상일 때 "오른쪽으로 60도 몸을 돌려 2시 방향을 향하세요"와 같이 명확한 회전 기준점 제시

### 5. 지자기 나침반(Rotation Vector) 실시간 헤딩 및 햅틱 콤파스 (`ORIENTATION_ALIGNED`)
- `Sensor.TYPE_ROTATION_VECTOR` 및 지자기 센서를 활용하여 단말기의 0.0°~360.0° 실시간 나침반 방위각 추적
- 제자리 회전 또는 보행 중 진행 방향으로 몸이 정렬되면 경쾌한 톡톡 2회 진동(`ORIENTATION_ALIGNED`, 60ms-60ms-60ms) 및 "올바른 진행 방향입니다" 음성 피드백 제공
- `NavigationScreen` 상단에 🟢 "경로 방향 정대 완료" / 🧭 "몸 방향 회전 필요" 실시간 고대비 상태 카드 제공

### 6. 횡단보도 접근 시 카메라 보행 보조 자동 연동 (`TriggerCrossingAssist`)
- 횡단보도 15m/8m 이내 접근 및 대기 모드(`APPROACHING_CROSSING`, `CROSSING`) 진입 시 `TriggerCrossingAssist` 이벤트를 방출하여 수동 터치 없이 카메라 신호 보조 모드로 자동 전환

### 7. 보행자 신호등 오인식 방지 및 스펙트럼 필터 (`CameraVisionSignalEstimator`)
- **차량용 신호등 배제:** 가로 횡형 신호등(종횡비 $W/H > 1.35$) 자동 기각
- **황색/주황색 불빛 차단:** 차량용 황색등, 가로등, 방향지시등($R \ge 150, G \ge 120, B < 120, |R-G| < 55$) 즉시 배제
- **스펙트럼 정밀화:** 한국형 보행신호 에메랄드/청록색 Green 및 고휘도 Red LED 파장 정밀화
- **ROI 최적화:** 도로 공중 배제를 위한 시야 높이(8%~65%) 탐색

### 8. SK TMAP 전국 POI 실시간 통합검색 및 기기 Geocoder 폴백 (`TmapPoiRepository`)
- **전국 장소 실시간 검색**: SK TMAP 공식 POI API(`https://apis.openapi.sk.com/tmap/pois`) 연동으로 전국의 기차역, 지하철역, 공공기관, 상호, 아파트, 도로명 주소를 실시간 위경도 좌표로 검색
- **하이브리드 즉각 필터링**: 타이핑 즉시(0ms) 로컬 추천/즐겨찾기를 우선 필터링하고 300ms 디바운스 후 전국 POI 클라우드 검색 수행
- **거리 계산 및 5km 보행 제한 안내**: 스마트폰 현재 GPS 위치 기준 직선거리를 산출하여 5km 이내(`🚶 350m`, `🚶 2.1km`) 초록 뱃지, 5km 초과(`⚠️ 15km (초과)`) 빨간 경고 뱃지 실시간 노출
- **음성 인식 자동 연동**: 음성으로 목적지를 말하면 자동으로 검색창에 반영되고 검색 결과 건수와 첫 번째 장소명을 음성으로 낭독
- **2차 안전 폴백**: 네트워크 장애 시 안드로이드 플랫폼 내장 `android.location.Geocoder`로 자동 2차 폴백

### 9. 대한민국 국토교통부 VWorld 표준 2D 정밀 전자지도 엔진 (`RealRouteMapView`)
- **국가표준 정밀 지도**: 국토교통부 VWorld 전국 2D 정밀 전자지도(`xdworld.vworld.kr`)를 기본 타일로 탑재하여 전국 1:1000 상세 건물, 골목길, 지번, 횡단보도를 100% 한글로 선명하게 표출
- **3중 안전 폴백 체계**: VWorld 타일 장애 시 OpenStreetMap(`tile.openstreetmap.org`) → CartoDB Voyager 순으로 즉각 자동 전환하여 검은 화면 없는 안정적 지도 표출 보장
- **WebView 최적화**: `MIXED_CONTENT_ALWAYS_ALLOW`, DOM Storage 활성화, Compose 수직 스크롤 간섭 방지 터치 리스너 적용

### 10. 보행 안내 화면 실시간 세부 지도 및 내 위치 추적 (`DetailedNavigationMapCard`)
- **실시간 내 위치 펄스 마커 (🔵)**: 보행자의 실시간 위치에 파란색 레이더 펄스 핀 마커 표시 ("📍 현재 내 위치 - 정상 진행 중")
- **경로 이탈 피드백 (🔴)**: 경로 이탈(`isOffRoute=true`) 감지 시 빨간색 펄스 핀 마커 및 `⚠️ 경로 이탈 주의` 뱃지 전환
- **무깜빡임 자바스크립트 갱신**: 화면 전체 재로드 없이 자바스크립트(`updateUserLocation`) 호출로 마커 핀만 부드럽게 실시간 이동
- **원터치 조작 편의**: [📍 내 위치 중심 이동] 및 [🔍 전체 경로 보기] 플로팅 버튼 제공
- **TMAP 보행로 표준 교정**: TMAP 보행자 API 응답의 `facilityType 11`을 일반 평지 보행로로 올바르게 해석하여 육교 오안내를 원천 차단

### 11. 온디바이스 고정밀 적응형 신호등 비전 엔진 (`CameraVisionSignalEstimator`)
- **RGB -> HSV 공간 분리**: 조도(명도 V)와 색상(Hue), 순도(Saturation)를 분리하여 한낮 직사광선/역광(백화 현상) 및 흐린 날/그늘(저조도)에서도 색상 파장을 정확히 분별
- **한국 경찰청 보행신호등 표준 스펙트럼**:
  - 고채도 적색(Hue 0°~15°, 345°~360°, $S \ge 0.40$)
  - 에메랄드/청록색 Green(Hue 145°~195°, $S \ge 0.35$): 일반 녹색 LED뿐만 아니라 한국 신호등 특유의 청록빛 LED 정밀 포착
- **한국형 보행신호등 2구 세로 기하 구조 분석**:
  - 상단 = 적색 정지 인형 픽토그램 / 하단 = 녹색 보행 인형 픽토그램의 공간적 상하 배치 검증
  - 차량용 가로 신호등(종횡비 $W/H > 1.35$) 및 황색등/가로등(Hue 25°~55°) 원천 배제
- **다크 하우징(Dark Housing) 콘트라스트 검증**:
  - 신호등 발광 램프 외곽 마진 테두리(Collar Band)의 명도($V_{\text{collar}}$)와 발광부($V_{\text{lamp}}$) 대비를 샘플링
  - 차광판 및 검은색 케이스가 없는 전광판, 상점 간판, 건물 유리창 조명($V_{\text{collar}} \ge 0.45, \Delta V < 0.20$)을 비신호등으로 원천 기각

### 12. 온디바이스 TFLite 런타임 및 지능형 검증기 (`TfliteModelRunner`, `LocalVlmSignalVerifier`)
- **하드웨어 가속 TFLite 러너 (`TfliteModelRunner`)**: `org.tensorflow.lite.Interpreter`를 공식 바인딩하여 NPU/NNAPI 및 멀티스레드 CPU 가속 지원
- **온디바이스 비전 검증기 (`LocalVlmSignalVerifier`)**:
  - **IoU 기반 공간 추적기 (`computeIoU`)**: 프레임 간 Bounding Box IoU $\ge 0.35$ 일 때만 동일 Track으로 인정하고, 박스 점프 시 즉시 시간 버퍼(`history.clear()`)를 리셋하여 서로 다른 위치의 불빛 오합산 100% 방지
  - **동역학(Motion) 변위 속도 필터**: 프레임 간 중심점 이동 속도($v = \Delta \text{dist} / \Delta t$)를 연산하여 차도를 가로지르는 고속 이동 차량/버스($v > 0.55/\text{sec}$)를 감지하고 `REJECTED_DYNAMIC_MOTION`으로 즉시 `UNKNOWN` 기각
  - **시간 일관성 필터(Temporal Rolling Buffer)**: 최근 5프레임 중 60% 이상 안정적으로 녹색이 지속될 때만 승인
  - **Zero False-Green 절대 원칙**: 조금이라도 의심스럽거나 불안정한 경우 즉시 `UNKNOWN` 또는 `RED`로 유지하여 보행자 생명 안전 최우선 보호

### 13. 2단계 하이브리드 보행신호 판정 파이프라인 (`TwoTierHybridSignalEstimator`)
- **Tier 1 (LiteRT 딥러닝 객체 검출)**: 전방 보행신호등 Bounding Box를 먼저 검출. 미검출 시 배경 초록색과 무관하게 즉시 `UNKNOWN`으로 차단
- **Tier 2 (박스 한정 ROI HSV 정밀 분석)**: 선별된 신호등 박스 내부 영역으로만 스캔을 한정하여 연산량 90% 절감 및 배경 잡음 완전 격리
- **Tier 3 (기하·동역학·시간 일관성 검증)**: `LocalVlmSignalVerifier`를 통해 최종 검증된 결과만 반환


## TalkBack 수동 시험 절차
1. **TalkBack 활성화**: Android 기기 설정 -> 접근성 -> TalkBack 켜기 (또는 볼륨 업+다운 키 3초 길게 누르기).
2. **목적지 검색 시험**:
   - `DestinationScreen` 진입 시 음성으로 "목적지 선택 화면입니다" 낭독 확인.
   - 검색창에 "서울역", "강남역" 등 실제 장소를 입력하거나 음성으로 말했을 때, 실시간으로 전국 POI 검색 결과가 뜨고 거리 뱃지(`🚶 350m`)가 읽히는지 확인.
3. **화면 진입 시험**: 목적지 선택 시 `RouteSummaryScreen`으로 진입하며, 국토교통부 VWorld 정밀 세부 지도와 함께 `보행 안전 및 접근성 한계 고지`가 읽히는지 확인.
4. **탐색 순서(Focus Order) 시험**:
   - 한 손가락 오른쪽 스와이프를 수행할 때 초점이 `접근성 한계 고지 배너` -> `경로 요약 정보` -> `주의사항 다시 듣기 버튼` -> `확인 후 보행 안내 시작 버튼` 순서로 논리적으로 이동하는지 확인.
5. **안내 시작 및 실시간 지도 확인 시험**:
   - `확인 후 보행 안내 시작` 버튼 더블 탭 시 `NavigationScreen`으로 전환되고, 상단 대형 방향 지시기와 함께 **실시간 세부 지도 카드(파란색 내 위치 핀)**가 올바르게 표출되는지 확인.
   - 단말기를 쥐고 걸어갈 때 지도 상의 파란색 내 위치 핀이 경로선을 따라 이동하는지 확인.
6. **나침반 정대 및 햅틱 콤파스 시험**:
   - 단말기를 쥔 손과 몸을 천천히 회전할 때, 경로 방향과 일치하는 순간 톡톡 2회 진동(`ORIENTATION_ALIGNED`)과 함께 "올바른 진행 방향입니다" 음성이 발화되는지 확인.


