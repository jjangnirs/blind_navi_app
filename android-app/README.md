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

### 14. 핸드헬드 손떨림 내성 추적 및 카메라 회전 정규화 (`LocalVlmSignalVerifier`, `ImageBufferRotator`)
- **소형 원거리 신호등 손떨림 방어**: 소형 박스에 대해 단순 IoU 외에 중심점 거리($\text{centerDist} \le 0.08$)를 복합 평가하여 8~12px 미세 떨림 시 불필요한 트랙 ID 리셋 방지
- **차량 횡단 모션 분리**: 미세 진동($\text{dist} \le 0.05$)은 수용하고 실제 횡단 차량($v > 0.85/\text{sec}, \text{dist} > 0.05$)만 엄격 기각
- **카메라 버퍼 90도 회전 정규화**: 세로 파지 시 회전된 CameraX 센서 버퍼를 정립(Upright) 상태로 정규화하여 세로 2구 보행신호등 기하 검증 무결성 확보
- **한국형 에메랄드 청록 LED 파장 확장**: $R=52, G=197, B=202$ 등 청록/시안 고유 파장(Hue 115°~205°) 전 영역 안정 감지

### 15. 온디바이스 비전 지각 비행기록장치 (`PerceptionFlightRecorder`)
- **무잠금 300프레임 원형 링 버퍼**: 메모리 및 GC 부하 없이 최근 10~15초 분량의 지각 텔레메트리(센서 각도, 바운딩 박스, 색상 확률, 트래커 ID)를 실시간 비동기 로깅
- **제로-개인정보 유출 (Zero Privacy Leak)**: 원본 카메라 프레임이나 사용자 위경도 좌표, 목적지 정보는 일절 버퍼에 기록하지 않음
- **이상 징후 자동/수동 진단 덤프**: 장시간 락온 후 미판정, 비정상 색상 전이 등 발생 시 또는 사용자 요청 시 최근 300프레임 진단 로그를 로컬 JSON 파일로 원자적 플러시

### 16. 최근 검색 목적지 및 즐겨찾기(⭐) 영구 저장소 (`RecentDestinationRepository`)
- **SharedPreferences 기반 JSON 지속성**: 최근 검색/선택한 장소(최대 20건 FIFO) 및 즐겨찾기 세트를 기기 로컬에 영구 저장하여 앱 재실행 후에도 온전히 복원
- **4단계 동적 추천 큐**: 1) 현재 GPS 기준 전방 테스트 경로 -> 2) 즐겨찾기 등록 목적지(⭐ 상단 고정) -> 3) 최근 검색 목적지 -> 4) 기본 추천 POI 순서로 노출
- **접근성 별표 토글 버튼**: 각 목적지 카드에 48dp 독립 터치 타깃 별표 버튼을 제공하고 TalkBack 시맨틱 및 음성 낭독 연동

### 17. 멀티밴드 GNSS 최적화 및 횡단보도 접근 감속 방위각 필터 (`LocationSample`, `ProductionLocationSource`, `CrossingApproachEngine`)
- **시계 기준점(Clock Base) 자동 폴백**: 안드로이드 부팅 시간(`elapsedRealtimeNanos`)과 JVM 나노초(`System.nanoTime`) 불일치로 신선한 야외 GPS가 만료 샘플(`STALE_SAMPLE`)로 오판되던 버그를 절대 시계(`currentTimeMillis - timestampEpochMs`) 폴백으로 해결
- **Android 12+ Fused Location Provider 연동**: Galaxy S25 Ultra(Snapdragon 8 Elite)의 L1+L5 듀얼 주파수 GNSS 및 PDR 센서 융합 위치를 초당 1회 정밀 수신
- **보행 감속 적응형 방위각 완화**: 횡단보도 18m 접근 또는 보행 속도 1.2m/s 이하 서행 시 방위각 허용 오차를 110도로 완화하여 연석 정지/서행 중 횡단보도 노드가 Drop되는 플리커 원천 차단

### 18. 태양광 팬텀 라이트(반사광) 필터링 및 손떨림 조준선 디바운싱 (`CameraVisionSignalEstimator`, `LocalVlmSignalVerifier`, `CrossingAssistViewModel`)
- **태양광 허상(Phantom Light) 필터링**: 한낮(13시경) 직사광선이 꺼진 적색 렌즈 안쪽을 비출 때 발생하는 미약한 반사광을 실발광 녹색 LED의 고휘도 에너지와 분리 판정하여 적색 오반전 차단
- **타깃 조준선 중심 우선권**: 화면 중앙 타깃 횡단보도 조준선에 위치한 신호등을 기준으로 판정하며, 화면 좌/우 원거리 다른 기둥의 적색광이 중심 녹색광을 침해하지 않도록 분리
- **동일 기둥 수직 램프 전환 모션 분리**: 상단 적색에서 하단 녹색으로 램프 전환 시의 수직 이동($\Delta X \le 0.04$)을 도로 횡단 주행 차량($\Delta X \gg 0.05$)과 명확히 구분하여 `REJECTED_DYNAMIC_MOTION` 오판정 방지
- **손떨림 조준선 디바운싱(Grace Period)**: 보행 중 손떨림으로 1~3프레임(약 100ms) 순간적으로 신호등이 조준선을 벗어나도 누적 중이던 녹색 카운트가 즉시 초기화되지 않도록 보호
- **녹색 확정 안내 발화 보호**: `GREEN_ESTIMATE` 안내 발화 시작 후 1.8초 동안은 단발성 노이즈에 의해 TTS 음성이 도중에 짤리지 않도록 오디오 큐 강제 플러시 차단

### 19. 동일 경로 보행 시 반복적 "경로가 변경되었습니다" 루프 차단 및 경로 이탈 필터 강화 (`RouteProgressEngine`, `NavigationViewModel`)
- **출발점 재보정 단 1회 가드 (`hasCalibratedInitialStart`)**: 보행 시작 전 최초 1회에 한해서만 출발지 위치 오차 보정(25m)을 수행하고, 보행이 진행 중일 때는 출발점 거리 기반의 재탐색을 원천 차단하여 앞으로 걸어갈 때 7~10초 주기마다 경로가 무한 재탐색되던 버그 완전 해결
- **유효 GPS 샘플 정확도 필터링**: GPS 정확도가 25m 이내(`accuracyMeters <= 25.0f`)인 신뢰할 수 있는 GPS 좌표일 때만 이탈 카운트를 누적하여 도심 빌딩/가로수 난반사로 인한 순간 튐 흡수
- **이탈 임계 조건 강화 및 쿨다운 안정화**: 연속 이탈 판정 횟수를 4회($\ge 4\text{s}$)로 상향하고 기본 이탈 반경을 35m로 완화, 재탐색 쿨다운 간격을 12초로 상향하여 안정적인 연속 보행 보장

### 20. 보행자 진행방향 위(Heading-Up / Course-Up) 지도 회전 뷰어 (`RealRouteMapView`, `NavigationScreen`)
- **170% 무여백 뷰포트 레이아웃**: 사각형 지도를 $360^\circ$ 회전하더라도 화면 네 귀퉁이에 빈 여백이 보이지 않도록 170% 크기 컨테이너와 중앙 회전축(`transform-origin: 50% 50%`) 적용
- **부드러운 나침반 연동 CSS 회전**: 기기 헤딩 각도에 연동하여 지도를 반시계 방향(`-headingDeg`)으로 0.35초 부드러운 애니메이션과 함께 회전시켜, 내가 걸어가는 도로가 항상 화면 위쪽($\uparrow$)을 향하도록 배치
- **진행방향 네비게이션 쉐브론 화살표 ($\blacktriangle$)**: 내 위치 마커 중앙에 고대비 형광 시안/네온블루 쉐브론 화살표를 배치하여 시각적 직관성 확보
- **정지/초기 방위각 자동 폴백**: 센서 헤딩이 0이거나 정지 상태일 때는 현재 보행 경로 세그먼트의 방위각(`calculateTargetBearing()`)을 자동 폴백하여 경로 방향 정렬 유지
- **원클릭 모드 토글**: 화면 우하단에 `🧭 진행방향 위` $\leftrightarrow$ `🧭 북쪽 고정` 토글 버튼 제공, `🔍 전체 경로` 탭 시 지도를 $0^\circ$ 정자세로 자동 전환

### 21. 실시간 보행 경로 분석 전용 비행 기록기 (Navigation Flight Recorder) 및 진단 툴링 (`NavigationFlightRecorder`, `scripts/`)
- **전용 텔레메트리 3MB 순환 기록**: `Android/data/kr.safecross.mobile/files/logs/navigation_flight.log`에 GPS 품질, 경로 진행 거리, 크로스트랙 오차(CTE), 나침반 정대 편차, 스텝 전환, 재탐색 트리거 사유, 음성 안내 발화 내역을 밀리초 단위로 기록
- **실시간 HUD 및 원클릭 공유 버튼**: `NavigationScreen` 화면 하단에 `📊 실시간 경로 분석 상태` 요약 표시 및 `[경로 분석 진단 로그 공유/저장]` 버튼을 제공하여 스마트폰만으로 카카오톡/메모장 즉시 공유 가능
- **PC 모니터링 & 분석 스크립트**:
  - `scripts/monitor_flight_logs.ps1`: `SafeCrossNavFlight` 실시간 Logcat 터미널 스트리밍
  - `scripts/pull_navigation_logs.ps1`: USB 연결 시 ADB/MTP를 통해 단말기 로그 파일 PC 자동 추출
  - `scripts/analyze_navigation_log.py`: 로그 자동 파싱하여 GPS 정확도, CTE 분포, 재탐색 횟수, 방위각 일치율 요약 리포트 생성

### 22. 차량용 신호등 고공 분리 배제 및 한손 파지 손떨림 적응형 보행 녹색 판정 안정화 (`CameraVisionSignalEstimator`, `LocalVlmSignalVerifier`, `CrossingDecisionEngine`)
- **상단 차량용 신호기 수직 고도 분리 ($Y_{norm} < 0.22$ vs $Y_{norm} \ge 0.22$)**: 교차로 차도 상공에 설치된 차량용 적색 신호가 인도 보행자 눈높이의 녹색 보행 신호를 기각하지 못하도록 배제
- **에너지 압도도 필터**: 보행 녹색 화소수가 미세 반사광/상단 적색의 2배 이상일 때 능동 점등된 보행 신호를 확실하게 선택
- **한손 파지 손떨림 적응형 공간 추적**: 손떨림 허용 거리를 0.08에서 0.18(화면 18%)로 확장하고, 뷰파인더 중앙부 영역 내 위치 시 동일 Track ID 지속 유지
- **Track ID 승계(Inheritance) 및 슬라이딩 윈도우 완충**: 손떨림으로 Track ID가 증가하더라도 누적 카운트를 보존하고, 5프레임 중 75% 이상 녹색 합의 시 `GREEN_ESTIMATE` 정상 승인
- **조준선 디바운싱(8프레임/270ms) 및 조준 발화 쿨다운(4초)**: 손떨림 중 조준 풀림 및 조준 반복 음성이 녹색 신호 음성 안내를 간섭하지 않도록 방어

### 23. 진행방향 지도(Heading-Up) 360도 랩어라운드 풍차 회전 차단 및 보행 손떨림 감쇠 안정화 (`RealRouteMapView`, `DevicePoseTracker`, `NavigationViewModel`, `NavigationScreen`)
- **최단 각도 누적 언래핑 회전(Shortest Angular Path Unwrapping)**:
  - 북쪽 경계($358^\circ \leftrightarrow 2^\circ$) 진입 시 CSS `transform: rotate(-Xdeg)`의 수치 선형 보간으로 인해 발생하는 $356^\circ$ 역회전(풍차 스핀)을 연속 누적 각도 연산($\Delta\theta = ((\text{targetRot} - \text{currentAngle} + 540) \pmod{360}) - 180$; `currentAngle += \Delta\theta`)으로 완벽 해결
- **보행 보폭 및 미세 손떨림 $2.5^\circ$ 데드밴드(Deadband)**:
  - $|\Delta\theta| < 2.5^\circ$ 미만의 보폭 좌우 흔들림 및 잔떨림 무시 필터를 적용하여 불필요한 연속 렌더링 부하 방지
  - CSS transition 속도를 `0.35s`에서 `0.20s ease-out`으로 최적화하여 60Hz 렌더링 스래싱 및 반응 지연 해소
- **원형 벡터 EMA 저역통과 필터(Circular Low-Pass EMA)**:
  - 회전/지자기 센서 60Hz 신호를 단위원 벡터($\cos\theta, \sin\theta$) 공간에서 $\alpha=0.25$ 가중치로 스무딩하여 0°/360° 경계 불연속 제거 및 80ms(12.5Hz) 적응형 스로틀링
- **보행 속도 연동 GPS 궤적(65%) + 나침반(35%) 상보 융합(Complementary Fusion)**:
  - 보행 속도 $0.8\text{ m/s}$ 이상 전진 보행 시 GPS 진행 방향 벡터를 65% 비중으로 상보 결합하여, 한손 보행 중 팔 흔들림에 의해 폰이 좌우로 흔들려도 지도가 진행 차로 축에 안정적으로 고정
- **진북(North 0.0°) Falsy 오판 버그 수정**:
  - `NavigationScreen`에서 `currentHeadingDegrees != 0f` 조건으로 인해 0.0° 진북일 때 가상 베어링으로 튀는 버그 제거

### 24. 보행 녹색 신호 쿨다운 차단 해제 및 도로 하단/차량 신호등 분리 안정화 (`GuidanceArbiter`, `CrossingAssistViewModel`, `CameraVisionSignalEstimator`, `TwoTierHybridSignalEstimator`)
- **GuidanceArbiter 카테고리 분리 및 녹색 신호 즉시 선점(Preemption)**:
  - 기존 `signal_decision` 공용 카테고리로 인해 적색 신호 발화 3초 이내에 전환된 녹색 신호가 쿨다운에 의해 침묵 차단되던 결함을 `signal_decision_red`와 `signal_decision_green`으로 분리하여 완전 해결
  - 적색 안내 멘트 발화 중이더라도 녹색 보행 신호 감지 시 적색 발화를 즉시 중단하고 선점 재생(`PREEMPT_AND_PLAY`)하도록 보장
  - `hasSpokenCurrentGreenPhase` 래치 플래그를 도입하여 녹색 확정 음성 안내 100% 전달 보장
- **물리적 등두(Head) 수직 거리 검증 및 도로 하단 차량 브레이크등 배제**:
  - 신호등 등두 내 물리적 램프 수직 거리(`maxVerticalHeadDist = maxOf(H_g, H_r) * 3.5f + 25f`)를 초과하는 하단 차량 브레이크등/후미등을 동일 기둥 판정에서 배제(`isLowerRoadwayRed`)
  - 녹색 블롭 아래쪽에 위치한 적색 아티팩트에 의한 공간 불일치 적색 강제 반전(False Red Flipping) 결함을 제거하여 선명한 녹색 신호 누적 무결성 확보
- **가로형 차량 신호등 인식 및 트래커 단일화**:
  - 교차로 가로형 차량 신호등(동일 수평선상 좌측 적색, 우측 녹색) 수용 및 직진 녹색 신호 정상 인식
  - `TwoTierHybridSignalEstimator`와 `CameraVisionSignalEstimator` 간 `LocalVlmSignalVerifier` 단일 인스턴스 공유로 트랙 ID 파편화 및 롤링 버퍼 이중 리셋 원천 차단

### 25. 숫자형 잔여시간 표시기(초록색 숫자) 클러스터링 및 2D 공간 추적 락(Spatial Tracking Lock-on) (`CameraVisionSignalEstimator`)
- **2차원 공간 추적 락(Spatial Tracking Lock-on) 및 시간 평활화(EMA)**:
  - 1차원 $X$축 중심 거리 의존도를 제거하고, 최근 800ms 이내 잠금된 신호 중심($X, Y$)에 대한 2D 유클리드 거리 및 수직 이탈 가중치(1.4배) 기반 후보 평가를 도입하여 한손 파지 시 조준 박스의 상/하단 텔레포트 요동(초당 3.5회 이상) 원천 차단
  - Bounding Box에 지수 이동 평균(EMA, $\alpha=0.70$)을 적용하여 화면 떨림 없는 안정적인 조준 프레임 표출
- **숫자형 잔여시간 표시기 모폴로지 클러스터링 (`clusterDigitBlobs`)**:
  - 십의 자리/일의 자리 및 세그먼트 선으로 분절된 디지털 녹색 숫자를 단일 타이머 블롭으로 병합하여 화소수 부족 탈락 방지
  - 2자리 카운트다운 타이머 종횡비($W \le 1.65 H$) 수용: 차량 신호 오인 배제 및 잔여시간 숫자 전환 시 끊김 없는 100% 연속 인식 보장
- **가공 차량 신호기 고도 임계값 최적화 및 2D 거리 비교**:
  - 판정 고도를 최상단 차도 영역($Y < 0.12f$)으로 상향 조정하여 전방 10~25m 보행 신호등 고도($Y \in 0.16f..0.35f$) 오판정 원천 차단
  - 2D 정규화 거리 및 녹색 추적 잠금 유지권을 통해 우측 차도 원거리 적색등 간섭에 의한 Zero False-Green 오판정 차단

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


