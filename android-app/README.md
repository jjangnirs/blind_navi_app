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

### 3. 보행자 신호등 오인식 방지 및 스펙트럼 필터 (`CameraVisionSignalEstimator`)
- **차량용 신호등 배제:** 가로 횡형 신호등(종횡비 $W/H > 1.35$) 자동 기각
- **황색/주황색 불빛 차단:** 차량용 황색등, 가로등, 방향지시등($R \ge 150, G \ge 120, B < 120, |R-G| < 55$) 즉시 배제
- **스펙트럼 정밀화:** 한국형 보행신호 에메랄드/청록색 Green 및 고휘도 Red LED 파장 정밀화
- **ROI 최적화:** 도로 공중 배제를 위한 시야 높이(8%~65%) 탐색

## TalkBack 수동 시험 절차
1. **TalkBack 활성화**: Android 기기 설정 -> 접근성 -> TalkBack 켜기 (또는 볼륨 업+다운 키 3초 길게 누르기).
2. **화면 진입 시험**: 앱 실행 시 `RouteSummaryScreen`으로 진입하며, 시스템 TTS 및 TalkBack이 `보행 안전 및 접근성 한계 고지: 이 경로는 TMAP 보행자 경로 안내(계단 제외 옵션)를 기반으로 제공되며...` 고지문을 자동으로 읽어주는지 확인.
3. **탐색 순서(Focus Order) 시험**:
   - 한 손가락 오른쪽 스와이프를 수행할 때 초점이 `접근성 한계 고지 배너` -> `경로 요약 정보` -> `주의사항 다시 듣기 버튼` -> `확인 후 보행 안내 시작 버튼` 순서로 논리적으로 이동하는지 확인.
4. **터치 타깃 시험**:
   - `주의사항 다시 듣기` 및 `확인 후 보행 안내 시작` 버튼이 최소 64dp 이상의 터치 영역을 확보하여 쉽게 탭되는지 확인.
5. **음성 다시 듣기 시험**:
   - `주의사항 다시 듣기` 버튼 더블 탭 시 면책 문구가 다시 발화되는지 확인.
6. **안내 시작 및 진행 화면 시험**:
   - `확인 후 보행 안내 시작` 버튼 더블 탭 시 `NavigationScreen`으로 전환되고, 상단에 `주의: 턱낮춤(2cm 이하)·점자블록·음향신호기 미보장` 배너와 함께 **저시력자용 대형 방향 안내 표시기**가 즉시 표시되는지 확인.

