# ADR-0037: ARCore Geospatial(VPS) 정밀 방향 적용 및 VPS 측정 시험 앱

## 1. 배경 및 문제 의식 (Context)
- 10/01 현장 로그에서 휴대폰 나침반의 1초 변화량이 p90 13°, p95 23°로 흔들렸고, 10/02 VPS 시험 앱 측정에서 나침반이 VPS 대비 **13~30° 틀어져** 있었다(자기장 간섭).
- 방향 가이드 화살표, "몇 도 돌리세요" 음성, 지도 회전, 경로 복귀 방향이 모두 나침반에 의존하므로 시각장애인에게 잘못된 방향을 안내할 수 있다.
- ARCore Geospatial API는 Google VPS(거리뷰 영상 기반)로 위치·방향을 보정한다. 신호등을 인식하는 기능은 없다(Scene Semantics 라벨에 신호등 없음).

## 2. 시험 앱 측정 결과 (`android-app/vpsprobe`, 2026-10-02 밤, 광주)
| 항목 | 지점1 | 지점2 |
|---|---|---|
| VPS 지원 | 지원됨 | 지원됨 |
| 위치 오차 | 0.6~0.8 m | 0.8~0.9 m |
| 방향 오차 | 2.3~2.5° | 2.6~2.8° |
| VPS↔GPS 위치 차이 | 약 20 m (남남서) | 약 20 m |
| VPS↔나침반 방향 차이 | 약 −30° | 약 −13° |

한계: 사실상 한 장소(3.3 m 간격), 야간, 정지 상태, 실제 위치(정답) 미확인.

## 3. 결정 사항 (Decisions)

### 3.1 방향 출처 결정 (`NavigationViewModel.resolveHeading`)
| 조건 | 사용 방향 | 화면 표시 |
|---|---|---|
| VPS 방향 오차 ≤ 10°, 1.5초 이내 | VPS 방향 (GPS 진행방향 융합 생략) | "VPS 정밀 방향" |
| VPS를 놓쳤지만 60초·30 m 이내에 잰 나침반 보정값 있음 | 나침반 + 보정값 | "VPS 보정 나침반" |
| 그 외 | 기존 나침반 + GPS 진행방향 융합 | "나침반" |

- 기준 축: 휴대폰을 눕혀 들면 윗변(+Y), 세워 들면 카메라 정면(−Z) 중 수평 성분이 큰 축 (`GeospatialHeadingMath`).
- 나침반 보정값은 나침반과 같은 축(윗변)일 때만 측정한다.

### 3.2 화면 없는 ARCore 공급자 (`GeospatialHeadingProvider`)
- 전용 스레드에서 1×1 오프스크린 EGL 컨텍스트를 만들어 ARCore 카메라 텍스처로 사용, 약 10 Hz로 `Session.update()`.
- 길안내 화면이 보이는 동안만 실행. 횡단보도 카메라 화면(CameraX)으로 가기 직전 `pauseBlocking()`으로 카메라를 놓아준다.
- ARCore 미설치·미지원·카메라 권한 없음·인증 실패 시 조용히 비활성(사유는 `[VPS]` 비행 기록).
- 의존성: `com.google.ar:core:1.56.0`, `com.google.android.gms:play-services-location:21.3.0` (Geospatial 필수, 미포함 시 `AR_ERROR_GOOGLE_PLAY_SERVICES_LOCATION_LIBRARY_NOT_LINKED`; 21.4.0은 Kotlin 2.3 메타데이터를 요구해 프로젝트 Kotlin 2.0.21과 비호환).
- 매니페스트: `com.google.ar.core` = optional(미지원 기기도 설치 가능), API 키는 `.env`의 `ARCORE_API_KEY`를 빌드 시 주입.
- 설정 화면 "VPS 정밀 방향" 토글(기본 켜짐).

### 3.3 인증 키
- Google Cloud "ARCore API" 사용 설정 후 발급한 API 키(`AIza…`). OAuth 클라이언트 ID가 아님.
- 키 제한: Android 앱(`kr.safecross.mobile`, `kr.safecross.vpsprobe`) + SHA-1, API 제한 = ARCore API만.
- ARCore Geospatial 사용은 무료이며 프로젝트당 분당 세션 1,000회·요청 100,000회 한도가 있다.

## 4. 개인정보 및 정책 충돌
- VPS 위치 확인 시 **카메라 영상 특징이 Google 서버로 전송**된다. 이는 ADR-002 "영상은 온디바이스 처리" 원칙과 충돌한다.
- 시험 단계에서는 설정 토글과 화면 고지로 운영하며, 정식 배포 전 기본값(켜짐/꺼짐)과 동의 절차를 팀이 결정해야 한다.
- 길안내 중 카메라가 계속 켜져 배터리 소모·발열이 늘고 카메라 사용 표시(초록 점)가 나타난다.

## 5. 현장 결과 (2026-10-02 22:48~23:01, 482 m 보행)
- 시작 후 약 12초 만에 VPS 방향 오차 53° → 3°.
- 방향 출처: VPS 77%(564초), VPS 보정 나침반 13%(92초), 나침반 10%(74초).

## 6. 검증
- 단위 테스트 `VpsHeadingFusionTest`(6건): 축 선택(세움/눕힘), VPS 우선, 보정값 60초 유지·만료, 부정확 VPS 무시, 세운 자세에서 보정값 미측정.
- APK: `app-debug-1003-v41.apk`(최초 적용), 시험 앱 `vpsprobe-1002-v3.apk`.

## 7. 남은 과제
- 정답 위치(정지선 등) 기준 VPS·GPS 정확도 검증, 낮 시간·보행 중 측정.
- VPS 위치로 GPS 보정(현재는 방향만 사용).
- 신호등 좌표 확보 후 신호등 예상 위치 투영·조준 안내 (신호 인식 자체에는 현재 효과 없음).
