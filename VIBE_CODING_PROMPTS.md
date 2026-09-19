# 바이브 코딩용 단계별 프롬프트

> 대상: Codex, Claude Code, Gemini CLI 등 저장소를 읽고 수정할 수 있는 코딩 에이전트  
> 사용법: 아래 프롬프트를 **번호 순서대로 한 개씩** 실행한다. 여러 단계를 한 번에 요청하지 않는다.

## 0. 사용 규칙

1. `README.md`, `PRD.md`, `SRD.md`, `TRD.md`, `DATA_SOURCES.md`, `SKILL.md`를 먼저 저장소에 둔다.
2. AI에게 0번 마스터 프롬프트를 프로젝트 지침으로 제공한다.
3. 그다음 1번부터 한 번에 하나씩 붙여넣는다.
4. AI가 “완료”라고 해도 테스트 명령과 실제 출력을 확인한다.
5. 요구사항을 만족하지 못하면 다음 번호로 넘어가지 않는다.
6. 버전·API 파라미터는 공식 문서에서 확인하고 잠근다. AI가 기억만으로 버전을 만들지 못하게 한다.
7. 실제 API 키, 사용자 좌표, 촬영 영상은 프롬프트에 붙이지 않는다.

## 1. 마스터 프롬프트 — 매 세션의 첫 지침

```text
너는 Safe Cross KR 저장소의 시니어 풀스택·Android·ML 안전 엔지니어다.

작업 시작 전 반드시 다음 파일을 읽어라.
- README.md
- PRD.md
- SRD.md
- TRD.md
- DATA_SOURCES.md
- IMPLEMENTATION_PLAN.md
- SKILL.md

우선순위는 안전 > 접근성 > 개인정보 > 정확성 > 성능 > 개발속도다.

절대 규칙:
1. 공공데이터의 신호등/횡단보도 정보는 정적 위치·시설 속성이다. 실시간 적색/녹색으로 해석하지 마라.
2. 공식 실시간 신호정보는 승인된 공급자·교차로·이동방향·신선도가 검증된 경우만 사용하라. 공개 API라고 가정하지 마라.
3. 단일 카메라 프레임 또는 모델 점수 하나로 GREEN_ESTIMATE를 출력하지 마라.
4. 색상 분류 전에 횡단보도·지도·기기 방위로 목표 보행신호를 고유하게 연결하라.
5. 위치·방향·횡단 문맥·같은 신호 track의 프레임 합의·품질·ODD·모델 허용 버전 중 하나라도 불충분하면 UNKNOWN으로 처리하라.
6. 공식 신호와 카메라가 충돌하면 점수를 평균내지 말고 UNKNOWN으로 처리하라.
7. 사용자 문구에 “안전합니다”, “지금 건너세요”, “차가 없습니다”, “100% 녹색”을 사용하지 마라.
8. 운영 앱의 카메라 프레임을 저장·로그·분석 SDK·네트워크로 보내지 마라.
9. API 키와 비밀을 APK, 저장소, 테스트 fixture, 로그에 넣지 마라.
10. 위치·목적지 원문을 관측 로그에 남기지 마라.
11. 핵심 기능은 TalkBack으로 화면을 보지 않고 수행 가능해야 한다.
12. 실패 테스트를 삭제하거나 요구를 낮춰 통과시키지 마라.
13. 안전 임계값을 임의로 낮추지 마라. 값은 설정과 근거로 버전 관리하라.

작업 방식:
- 먼저 현재 저장소와 관련 요구사항 ID를 확인하라.
- 범위를 3~7개의 작은 단계로 계획하라.
- 모호하지만 안전에 영향이 없는 것은 문서에 맞춰 합리적으로 결정하라.
- 안전, 외부 API 계약, 데이터 손실에 영향이 큰 모호함만 질문하라.
- 테스트를 먼저 또는 구현과 함께 작성하라.
- 변경 후 관련 lint, unit, integration test를 직접 실행하라.
- 마지막 답변은 변경 파일, 충족한 요구 ID, 실행한 명령/결과, 남은 위험으로 요약하라.
- 요청 범위 밖 대규모 리팩터링은 하지 마라.

이번 요청만 수행하고 다음 단계는 자동으로 시작하지 마라.
```

## 2. 프롬프트 1 — 모노레포 골격

```text
[마스터 프롬프트를 적용한 상태]

목표: Safe Cross KR 모노레포의 실행 가능한 최소 골격을 만들어라. 아직 실제 길찾기나 카메라 AI를 구현하지 마라.

관련 요구:
- README.md 저장소 구조
- TRD.md 3장
- SR-NF-020~026

해야 할 일:
1. android-app, backend, data-pipeline, ml, infra, docs 디렉터리 구조를 만든다.
2. backend는 Python 3.12+ FastAPI로 /health가 200을 반환하게 한다.
3. infra/docker-compose.yml에 PostgreSQL+PostGIS를 넣는다.
4. data-pipeline은 import 가능한 Python package와 pytest 한 개를 만든다.
5. Android는 Kotlin+Compose 빈 앱과 unit test가 빌드되게 한다. minSdk 28, target/compile SDK는 현재 설치 가능한 최신 안정판으로 선택하고 이유를 기록한다.
6. 루트 .env.example에는 변수 이름만 둔다. .env와 키 파일을 .gitignore에 넣는다.
7. Makefile 또는 동등한 명령 진입점에 bootstrap, lint, test, backend-up, backend-down을 만든다.
8. CI를 backend/data/android로 나누고 secret scan 기본 작업을 추가한다.
9. 선택한 의존성 버전은 공식 release/documentation을 확인하고 lock/version catalog에 고정한다. 확인할 수 없으면 임의 최신값을 만들지 말고 TODO와 검증 방법을 남긴다.

수용 기준:
- docker compose config 통과
- backend pytest 통과
- data-pipeline pytest 통과
- Android unit test 또는 assembleDebug 통과
- rg로 실제 키/금지 샘플 비밀 없음
- README에 Windows PowerShell과 bash 실행법 존재

작업 후 다음 단계로 넘어가지 말고 결과만 보고하라.
```

## 3. 프롬프트 2 — PostGIS 스키마와 마이그레이션

```text
[마스터 프롬프트 적용]

목표: 시설 원천, raw record, crossing, signal device, field verification, user report, 감사 이력을 PostGIS에 구현하라.

관련 요구:
- SR-F-020~027, SR-F-092~094
- SR-NF-026
- TRD.md 6장

먼저 기존 migration과 DB conventions를 읽어라.

해야 할 일:
1. Alembic migration을 추가한다.
2. source, raw_record, crossing, signal_device, field_verification, user_report, audit_event 테이블을 만든다.
3. crossing.geom은 geometry(Point,4326), GiST index를 사용한다.
4. boolean은 nullable로 만들어 false와 unknown을 구분한다.
5. source ID + source record key + 유효기간으로 이력 관리한다.
6. published 상태와 quality_status를 constraint로 제한한다.
7. 현장 검증값이 원천값을 삭제/덮어쓰지 않도록 별도 테이블과 projection query를 만든다.
8. downgrade를 포함하되 데이터 삭제 위험을 주석과 문서에 명시한다.
9. migration upgrade/downgrade 테스트, constraint 테스트, 반경 공간쿼리 테스트를 만든다.

수용 기준:
- 빈 DB upgrade head 성공
- seed fixture 삽입 성공
- ST_DWithin 100m 조회가 예상 레코드만 반환
- null/false가 구분됨
- 같은 원천키 새 기준일 적재 시 이전 이력 보존
- 테스트가 실제 PostGIS에서 통과

DDL만 만들고 API와 ETL은 구현하지 마라.
```

## 4. 프롬프트 3 — 공공데이터 ETL

목표: 전국횡단보도표준데이터와 전국신호등표준데이터 CSV를 raw→staging→canonical까지 안전하게 처리하라.

```text
[마스터 프롬프트 적용]

목표: 광주광역시횡단보도표준데이터와 광주광역시신호등표준데이터 CSV를 raw→staging→canonical까지 안전하게 처리하라.

관련 요구:
- SR-F-020~027
- DQ-001~008
- DATA_SOURCES.md 7~12장
- TRD.md 7장

입력 파일을 인터넷에서 임의 다운로드하지 말고 tests/fixtures에 최소 합성 CSV를 만든다. 합성 fixture에는 UTF-8-SIG와 CP949, 정상·결측·잘못된 좌표·중복·알 수 없는 Y/N 값을 포함하라.

해야 할 일:
1. CLI `python -m pipeline ingest --source <traffic-light|crosswalk> --file PATH --retrieved-at ISO_TIME`를 만든다.
2. 원본 SHA-256, 행 수, encoding, parser version을 source run에 기록한다.
3. 원본 열 alias를 canonical field에 명시적으로 매핑한다.
4. Y/YES/1, N/NO/0, 공란을 true/false/null로 구분하고 알 수 없는 값은 격리한다.
5. 위도/경도 숫자, 대한민국 1차 범위, 날짜, 필수키를 검증한다.
6. 행정구역 alias를 데이터 파일로 분리하되 원천명을 보존한다.
7. 정상/격리/중복/결측 보고서를 JSON으로 생성한다.
8. 동일 실행 재처리는 idempotent해야 한다.
9. 원천 파일을 코드가 수정하지 않았음을 해시로 검증한다.

수용 기준:
- 두 fixture 형식 파싱
- 공란이 false로 바뀌지 않음
- 위경도 뒤바뀜/범위 밖 격리
- 두 번 실행해 published 중복 없음
- 원본 해시 불변
- 단위·통합 테스트 통과

실제 공공데이터 API 키와 네트워크 호출은 아직 추가하지 마라.
```

## 5. 프롬프트 4 — 공간조인 후보와 검수

```text
[마스터 프롬프트 적용]

목표: crossing과 signal을 진행방향별로 보수적으로 연결하고 모호 후보를 검수 큐로 보내라.

관련 요구:
- SR-F-025, SR-F-028~029, DQ-006~011
- TRD.md 7장

해야 할 일:
1. 10m, 20m, 30m 거리의 many-to-many 후보를 계산한다.
2. 거리, 도로명 일치, 관리번호 관계, 주소 유사도, 방향 정보의 근거를 별도 feature로 저장한다.
3. 후보가 유일하지 않거나 방향이 충돌하면 자동 확정하지 않는다.
4. 자동 확정 조건도 field_verified가 아니면 AI 대상 allowlist에 넣지 않는다.
5. 운영자용 CSV/GeoJSON 검수 export와 import를 구현한다.
6. 위조/잘못된 운영자 import를 막는 스키마와 역할 검사를 만든다.
7. 교차로 네 모서리, 평행도로, 중앙 교통섬 fixture를 만들고 회귀 테스트한다.
8. 횡단 시작점·끝점·approach bearing·signal device를 연결하는 `crossing_signal_link`를 구현한다.
9. 공식 공급자 코드가 있을 경우 provider intersection/movement ID를 링크에 추가하되 현장검증 없이 활성화하지 않는다.

수용 기준:
- 가장 가까운 점이라는 이유만으로 방향이 다른 신호를 확정하지 않음
- 복수 후보는 REVIEW_REQUIRED
- 검수 전 published facility 탐색은 가능하나 GREEN allowlist는 불가
- 검수 변경 이력 보존
- 서로 반대 방향의 같은 횡단보도는 별도 링크로 유지
- 모든 AI allowlist 항목에 승인된 방향별 링크 존재
```

## 6. 프롬프트 5 — 주변 시설 API

```text
[마스터 프롬프트 적용]

목표: 모바일이 쓸 주변 시설 및 경로 회랑 API를 구현하라.

관련 요구:
- SR-F-020~027
- SR-NF-010, SR-NF-020~026, SR-NF-040~043
- TRD.md 5장

해야 할 일:
1. GET /v1/crossings/nearby를 구현한다.
2. GET /v1/crossings/corridor를 구현한다.
3. lat/lon 대한민국 범위, radius 10~500m, corridor vertex/길이 상한을 검증한다.
4. 응답에 dataVersion, source/reference/ingested/verification, nullable 시설값을 포함한다.
5. field verification projection과 원천 충돌 플래그를 명시한다.
6. access log에서 query의 lat/lon과 route geometry를 제거 또는 안전하게 redaction한다.
7. OpenAPI schema snapshot과 contract test를 만든다.
8. rate limit, timeout, correlationId, 공통 오류 envelope를 구현한다.

수용 기준:
- null과 false가 JSON에서 구분됨
- 미검증/오래된 값이 검증된 값처럼 표시되지 않음
- SQL injection/과도한 geometry 방어
- 로그 캡처 테스트에 좌표 원문 없음
- PostGIS explain에서 spatial index 사용 확인
```

## 7. 프롬프트 6 — TMAP 보행 경로 어댑터와 면책 고지 배너

```text
[마스터 프롬프트 적용]

목표: TMAP 보행자 경로 API(계단 제외 searchOption="30")를 연동하고 접근성 한계 면책 배너(DisclaimerBanner)와 확인 절차를 구현하라.

관련 요구:
- SR-F-010~015, SR-F-035
- TRD.md 4.3장
- DATA_SOURCES.md 3장

공식 TMAP 보행자 경로 문서의 현재 endpoint, method, header, body, 계단 제외 옵션을 확인하고 근거 URL을 docs/adr에 남겨라.

해야 할 일:
1. PedestrianRouter port와 TmapPedestrianRouter adapter, Android의 TmapRouteRepository를 만든다.
2. searchOption="30"(계단 제외) 옵션을 지정하여 보행자 경로를 요청한다.
3. TMAP GeoJSON 응답(Feature Point의 turnType, facilityType, Feature LineString의 거리/소요시간/지오메트리)을 Maneuver와 RouteSegment로 정규화한다.
4. RouteSummaryScreen에 DisclaimerBanner(턱낮춤 2cm 이하, 점자블록, 음향신호기 미보장 고지)를 18sp 이상으로 고정 배치하고 글꼴 200%에서도 줄임표 없이 표시한다.
5. 화면 진입 시 고지문 전문을 TTS로 즉시 낭독하고, "주의사항 다시 듣기" 버튼(64dp)을 제공한다.
6. 사용자가 "확인 후 보행 안내 시작" 버튼(64dp)을 클릭해야만 실제 내비게이션으로 전이되도록 통제한다.
7. connect/read timeout, circuit breaker, 제한 재시도를 구현하고 위치·목적지 원문이 로그에 남지 않게 한다.

수용 기준:
- TMAP 경로를 “장애인 안전 경로”로 표현하지 않고 명시적 면책 고지
- 계단 제외 searchOption="30" request mapping 테스트 통과
- 면책 배너 낭독 및 사용자 확인 통제 테스트 통과
- 모바일/APK 및 원격 로그에 키/위치 누출 없음
```

## 8. 프롬프트 7 — Android 접근성 앱 골격 및 저시력 안내

```text
[마스터 프롬프트 적용]

목표: 목적지 선택부터 경로 시작, 4단계 보행 모드, 저시력자 방향 안내 표시기, 실시간 GPS 뱃지, 시각장애인 특화 시계방향/걸음수 포맷터, 지자기 나침반 햅틱 콤파스, 횡단보도 자동 카메라 연동, 종료까지 TalkBack과 고대비 UI로 구현하라.

관련 요구:
- SR-F-001~006, SR-F-035~038, SR-F-070~079-B
- TRD.md 4.1, 4.8장

해야 할 일:
1. Onboarding, Destination, RouteSummary, Navigation, CrossingAssist, Settings 화면을 구현한다.
2. 실시간 4단계 보행 모드 상태머신(WalkingMode: IDLE → WALKING → APPROACHING_CROSSING → CROSSING)과 WalkingModeStatusBadge를 제작한다.
3. NavigationScreen 상단에 실시간 GPS 수신 강도(%) 및 오차 반경(±m) 뱃지(80% 이상 녹색, 60~79% 청색, 40~59% 주황, 40% 미만 적색)를 노출한다.
4. 저시력자를 위한 초고대비 대형 방향 안내 표시기(LowVisionDirectionIndicator: 4dp 형광노랑 테두리, 84dp 대형 방향 심볼, 38sp ExtraBold 대형 거리 텍스트, 24sp 행동 라벨)를 화면 최상단에 배치한다.
5. 시각장애인 특화 음성 길안내 포맷터(BlindGuidanceFormatter)를 구축하여 1~12시 시계 방향 안내, 평균 보폭(0.65m) 기준 걸음 수 환산, TMAP 시각 단서(상호명/방면) 필터링, 신체 회전각 안내를 적용한다.
6. Sensor.TYPE_ROTATION_VECTOR 지자기 센서로 실시간 나침반 헤딩을 추적하고, 경로 방향 정렬 시 ORIENTATION_ALIGNED 톡톡 2회 진동 및 정대 음성/상태 카드를 제공한다.
7. 횡단보도 15m/8m 접근 시 수동 터치 없이 카메라 신호 보조 화면으로 자동 전환되는 TriggerCrossingAssist 이벤트를 연동한다.
8. 방향 분기점 변경 시 즉시 새 지침을 음성 발화(QUEUE_FLUSH)하고 30m 및 15m 전 사전 접근 알림을 발화한다.
9. 백그라운드 및 화면 꺼짐 시에도 상태바 알림창 갱신과 위치/TTS 안내를 유지하는 NavigationForegroundService를 연동한다.
10. 모든 icon/action에 고유 semantics 제공, 주요 안전 버튼 64dp, 일반 터치 타깃 최소 48dp 보장.
11. 글꼴 200%, 다크모드, TalkBack 환경에서 전체 흐름 검증.

수용 기준:
- TalkBack으로 전맹 사용자가 화면 없이 시작/진행/종료 가능
- 저시력자용 84dp 대형 방향 화살표와 38sp 대형 거리 표시기 시인성 확보
- 시계 방향 및 걸음 수 환산, 나침반 정대 햅틱 콤파스 및 자동 횡단보도 연동 테스트 통과
- 4단계 보행 모드 상태머신 및 분기점 즉시 음성 발화 단위 테스트 통과
- GPS 뱃지 및 Foreground Service 알림창 정상 갱신 확인
- accessibility unit/instrumentation test 통과
```

## 9. 프롬프트 8 — Android 위치 내비게이션

```text
[마스터 프롬프트 적용]

목표: 위치 권한, navigation foreground service, 경로 진행, 시설 접근 알림을 구현하라. 카메라 신호 추정은 구현하지 마라.

관련 요구:
- SR-F-002~005, SR-F-030~034
- SR-F-080~084
- TRD.md 4.2~4.3

현재 target SDK의 Android 공식 위치/foreground service 문서를 확인하고 필요한 manifest permission과 런타임 순서를 기록하라.

해야 할 일:
1. LocationSource interface와 production/fake implementation을 만든다.
2. 내비게이션 세션 중에만 location foreground service를 실행한다.
3. 알림에 즉시 중지 action을 제공한다.
4. 위치 샘플의 accuracy, age, bearing, mock 상태를 보존한다.
5. route polyline projection, progress, off-route를 순수 Kotlin으로 구현한다.
6. 현장 검증 crossing만 정확한 방향 대상으로 하고 나머지는 일반 접근 정보만 말한다.
7. GPS 부정확하면 거리/방향 정밀 표현을 중지한다.
8. 같은 crossing 알림 반복을 방향+쿨다운으로 제어한다.
9. recorded trace fixture로 접근, 반대편 도로, GPS jump, 경로 이탈을 테스트한다.

수용 기준:
- 권한 거부/대략 위치/GPS OFF에서 크래시 없음
- accuracy 불량 시 정밀 횡단 상태 진입 없음
- 프로세스 복구 후 IDLE/UNKNOWN
- 위치 원문 로그 없음
- Android 버전별 foreground service 테스트 통과
```

## 10. 프롬프트 9 — TTS와 진동 중재기

```text
[마스터 프롬프트 적용]

목표: 안전 메시지 우선순위와 반복 방지를 갖춘 TTS·진동 시스템을 구현하라.

관련 요구:
- SR-F-070~076
- TRD.md 4.8

해야 할 일:
1. GuidanceArbiter를 UI와 분리한 순수 Kotlin 모듈로 만든다.
2. priority는 SAFETY > CROSSING > ROUTE > INFO다.
3. 새 SAFETY 메시지가 오래된 ROUTE 큐를 제거하도록 한다.
4. 같은 상태의 쿨다운, 다시 듣기, 전체 중지를 구현한다.
5. TTS 초기화/언어/오디오 포커스 실패를 상태로 표현한다.
6. RED, GREEN_ESTIMATE, UNKNOWN의 진동 vocabulary를 문서화하고 사용자 설정을 제공한다.
7. 앱 문자열 리소스에서 금지 문구를 검사하는 테스트를 추가한다.
8. “녹색으로 추정됩니다. 앱만으로 안전을 보장할 수 없습니다.”처럼 추정과 한계를 포함한다.

수용 기준:
- 안전 메시지가 경로 안내에 막히지 않음
- 반복 폭주 없음
- TTS 실패 시 TalkBack/화면 상태 제공
- 진동이 꺼져도 핵심 정보 접근 가능
- 금지 문구 0건
```

## 11. 프롬프트 10 — CameraX와 적응형 HSV 비전 신호 추정기

```text
[마스터 프롬프트 적용]

목표: CameraX 프레임 파이프라인과 실시간 고정밀 비전 신호 추정기(CameraVisionSignalEstimator) 및 횡단보도·신호 연결기를 구현해 차량용 신호등 오인식 방지와 수명주기·개인정보·접근성을 검증하라.

관련 요구:
- SR-F-040~041, SR-F-043a~d, SR-F-049, SR-F-052~054
- TRD.md 4.4, 4.6

해야 할 일:
1. 사용자 명시 동작 후만 열리는 CrossingAssistScreen을 만든다.
2. CameraX ImageAnalysis를 lifecycle에 bind하고 STRATEGY_KEEP_ONLY_LATEST를 사용한다.
3. ImageProxy는 try/finally에서 반드시 close한다.
4. FrameRef는 저장/직렬화/네트워크 전송이 불가능한 앱 내부 타입으로 만든다.
5. CameraVisionSignalEstimator에 다음 고정밀 적응형 비전 파이프라인을 구현한다:
   - 도로 위 공중 신호 배제를 위한 시야 높이 ROI(8%~65%) 탐색
   - RGB→HSV 고속 공간 분리를 통해 조도(V)와 색조(H)/채도(S)를 완전 분리하여 직사광선/역광(백화 현상) 및 그늘/야간(저조도) 적응형 보정 적용
   - 한국 경찰청 보행신호등 표준 규격: 고채도 적색(Hue 0°~15°, 345°~360°) 및 에메랄드/청록색 Green(Hue 145°~195°) 광원 정밀 세그멘테이션
   - 차량용 가로 3~4구 횡형 신호등 배제 (Aspect Ratio Width/Height > 1.35 및 Width >= 16)
   - 차량용 황색(Yellow)/주황색 불빛 및 가로등(Hue 25°~55°) 즉시 배제
   - 세로 2구 보행신호등 기하 구조(상단 적색 정지 사람 / 하단 녹색 보행 사람) 공간 배치 분석 및 상충 시 Red 우선(Zero False-Green) 원칙 적용
6. fake crosswalk estimator가 mask/polygon, entrance, direction, quality를 반환하게 한다.
7. fake associator가 unique, ambiguous, wrong-direction 시나리오를 반환하게 한다.
8. 앱 background, 화면 종료, 권한 취소에서 카메라를 해제한다.
9. 네트워크 mock이 프레임 관련 호출 0건임을 검증한다.
10. 카메라 방향/기울기와 횡단보도 탐색 상태를 TTS와 진동으로 표현한다.

수용 기준:
- 역광/백화 및 에메랄드 청록색 신호등 검출 단위 테스트 통과
- 가로형 차량 신호등 및 황색 불빛 입력 시 UNKNOWN으로 안전 기각 단위 테스트 통과
- 단일 fake GREEN으로 사용자 GREEN_ESTIMATE가 나오지 않음
- 횡단 문맥 없음·복수 신호·방향 불일치에서 UNKNOWN
- background에서 카메라 종료 및 프레임 파일 생성 0건, 네트워크 전송 0건
- 분석 지연이 누적되지 않고 권한 취소/회전/잠금에서 크래시 없음
```

## 12. 프롬프트 11 — LiteRT / TFLite 온디바이스 런타임 및 지능형 검증기

```text
[마스터 프롬프트 적용]

목표: 동결된 횡단보도 및 보행신호 .tflite 테스트 모델을 각 estimator에 연결하고, TfliteModelRunner 및 LocalVlmSignalVerifier를 통합하라.

관련 요구:
- SR-F-042~054, SR-F-047a~b
- SR-NF-001~015
- TRD.md 4.5, 8장

해야 할 일:
1. 모델 manifest, SHA-256, labels, input/output tensor 계약을 검증한다 (ModelContractValidator).
2. TfliteModelRunner에 org.tensorflow.lite.Interpreter를 공식 바인딩하여 NPU(NNAPI) 및 4스레드 CPU 멀티스레드 하드웨어 가속 추론을 구현한다.
3. LocalVlmSignalVerifier에 최근 5프레임의 시간 일관성 롤링 버퍼(Temporal Rolling Buffer)를 구현하여 단일 프레임 잡음/반사광 오탐을 방지하고 녹색 판정 시 60% 이상 프레임 안정을 검증한다.
4. CPU baseline을 먼저 만들고 지원 기기에서 GPU/NPU 경로를 기능 플래그로 추가한다.
5. 가속기 실패 시 안전하게 CPU 또는 UNKNOWN으로 fallback한다 (Zero False-Green).
6. 골든 입력의 출력 오차 허용범위를 정의하고 backend별 비교 테스트를 만든다.
7. P50/P95 latency, peak memory, 모델 초기화 시간을 benchmark한다.
8. 모델 score를 사용자에게 확률 또는 안전도로 표시하지 않는다.
9. 전체 장면 입력과 작은 신호용 고해상도 ROI crop을 실제 기기에서 비교한다.
10. 두 모델의 출력이 동일한 원본 프레임 좌표계를 사용함을 골든 테스트한다.

수용 기준:
- TfliteModelRunner 하드웨어 가속 및 수명주기 테스트 통과
- LocalVlmSignalVerifier 시간 일관성 롤링 버퍼 및 녹색 안전 강등 테스트 통과
- 변조 모델 로드 거부 및 label 순서 오류 탐지
- 가속기 실패 시 크래시/false-green 없음
- 비행기 모드에서 100% 로컬 동작
```

## 13. 프롬프트 12 — 안전 상태기계

```text
[마스터 프롬프트 적용]

목표: 모델 관측, 횡단 문맥, 목표 신호 track, 위치·방향, 선택적 공식 신호, ODD와 kill switch를 결합하는 결정론적 CrossingDecisionEngine을 구현하라.

관련 요구:
- SR-F-056~069
- ST-001~015
- TRD.md 4.6~4.7

이 모듈은 Android 프레임워크에 의존하지 않는 순수 Kotlin이어야 한다.

해야 할 일:
1. IDLE, APPROACH, STOP_REQUIRED, SCANNING, RED_ESTIMATE, GREEN_CANDIDATE, GREEN_ESTIMATE, UNKNOWN 상태를 구현한다.
2. 모든 입력에 monotonic timestamp와 최대 age를 적용한다.
3. GREEN_CANDIDATE는 사용자에게 노출하지 않는다.
4. green은 crosswalk context, configurable window, 같은 track의 minimum frames, agreement, calibrated score, unique target, location/heading/quality/ODD/allowlist를 모두 통과해야 한다.
5. 공식 신호를 사용하면 provider/intersection/movement/freshness를 별도 검증한다.
6. 공식 신호와 카메라, 지도 방향과 영상 방향처럼 안전에 중요한 증거가 충돌하면 평균하지 않고 즉시 UNKNOWN으로 전이한다.
7. 필수 입력 하나가 만료/충돌하면 즉시 UNKNOWN으로 전이한다.
8. RED/UNKNOWN은 green보다 보수적으로 우선하도록 충돌정책을 명시한다.
9. 상태 전이 로그에는 reason code와 버전만 포함하고 위치/프레임은 제외한다.
10. table-driven, property-based, fuzz test를 추가한다.
11. ST-001~015를 모두 자동화한다.

수용 기준:
- 단일 또는 드문 green 관측으로 GREEN_ESTIMATE 불가
- 차량 green/보행 red fixture에서 green 불가
- 여러 방향 신호에서 UNKNOWN
- 다른 track의 RED→GREEN은 유효 전이가 아님
- 공식 GREEN/카메라 RED 또는 반대에서 UNKNOWN
- 센서 age 경계 전후 테스트
- kill switch 즉시 적용
- 같은 입력 순서에 결정론적 결과
```

## 14. 프롬프트 13 — 모델 학습 파이프라인

```text
[마스터 프롬프트 적용]

목표: 라이선스 확인이 끝난 로컬 데이터만 사용하여 재현 가능한 횡단보도 segmentation, 신호 detector/classifier, 목표 신호 associator의 학습·평가·LiteRT export 파이프라인을 만든다. 데이터 자체는 저장소에 커밋하지 마라.

관련 요구:
- PRD 출시 게이트
- TRD.md 8장

해야 할 일:
1. dataset manifest schema를 정의한다: source/license/consent/site/device/time/weather/label/hash/split.
2. 교차로 site 단위 split validator를 만들어 train과 test 누출을 막는다.
3. pedestrian/vehicle/bicycle/ambiguous/hard-negative와 crosswalk mask/entrance/direction/target-link 라벨을 지원한다.
4. config 파일 하나로 seed, augmentation, optimizer, input size, epochs를 재현한다.
5. 전체 accuracy가 아니라 crosswalk IoU/Dice, 방향 각도오차, 목표 신호 연결 정확도, green precision, false-green event, UNKNOWN, slice 지표를 계산한다.
6. 연속 프레임을 독립 표본으로 잘못 세지 않고 sequence event 평가를 구현한다.
7. float, PTQ INT8, 필요 시 QAT 모델을 비교한다.
8. LiteRT export 후 Android golden set과 같은 전처리/후처리 계약을 확인한다.
9. model card와 evaluation.json을 자동 생성한다.
10. test set 또는 개인정보 원본을 artifact registry에 무단 업로드하지 않는다.
11. 같은 동결 시퀀스로 signal-only, signal+crosswalk, signal+crosswalk+map/heading ablation을 수행한다.

수용 기준:
- site leakage validator 통과
- seed 고정 재현성 보고
- hard-negative slice 별도 보고
- 목표 신호 오선택과 잘못된 track 전환 별도 보고
- 0 observed false-green이면 신뢰구간 상한도 표시
- export 모델 hash/labels/manifest 생성

이 작업 결과만으로 출시 승인을 주장하지 마라.
```

## 15. 프롬프트 14 — 보안·개인정보·안전 회귀 CI

```text
[마스터 프롬프트 적용]

목표: 위험한 회귀를 CI에서 조기에 차단하라.

관련 요구:
- SR-NF-020~026, SR-NF-040~043
- PRD 출시 게이트

해야 할 일:
1. secret scan과 dependency/SBOM scan을 구성한다.
2. Android string resource와 서버 현지화 파일의 금지 문구를 검사한다.
3. 네트워크 인터셉터 테스트로 좌표·목적지·프레임이 로그에 없는지 확인한다.
4. 앱 저장소 검사 테스트로 카메라 프레임 파일 0건을 확인한다.
5. 모델 manifest 서명/해시 오류 회귀 테스트를 CI에 넣는다.
6. ST-001~015 상태기계 테스트를 필수 branch protection job으로 만든다.
7. ETL schema drift와 대량 행 수 변화 경보를 만든다.
8. migration dry-run과 rollback 절차를 검증한다.
9. CI 로그 자체에 secret/좌표 fixture 원문이 출력되지 않게 한다.

수용 기준:
- 의도적으로 넣은 가짜 secret fixture를 CI가 탐지
- 금지 문구 fixture를 CI가 탐지
- 변조 모델 fixture를 CI가 탐지
- 데이터 열 삭제 fixture에서 schema drift 실패
- 모든 안전 job 이름과 책임자가 CODEOWNERS/문서에 연결
```

## 16. 프롬프트 15 — 공식 실시간 신호 어댑터와 안전 융합

```text
[마스터 프롬프트 적용]

목표: 공개가 확인되지 않은 API를 추정하지 않고, 승인된 시험 계약 또는 fixture로 공급자 독립적인 실시간 보행신호 어댑터와 카메라 충돌 차단을 구현하라.

관련 요구:
- PR-F-016
- SR-F-055~059, SR-F-066~069
- ST-012~015
- TRD.md 4.6~4.7, ADR-006
- DATA_SOURCES.md 2.5~2.7

사전조건:
- 기관이 제공한 공식 데이터 사전·계약·시험 인증정보가 없다면 실제 네트워크 어댑터를 만들지 말고 fake provider와 인터페이스까지만 구현한다.
- 정적 전국 신호등/횡단보도 데이터와 명목 신호시간을 실시간 상태 fixture로 사용하지 않는다.

해야 할 일:
1. `SignalStatusProvider`와 `FakeSignalStatusProvider`를 정의한다.
2. 정규화 결과에 provider, providerIntersectionId, movementId, state, sourceTimestamp, receivedElapsedRealtime, optionalRemainingSeconds, qualityFlags를 포함한다.
3. `UNSUPPORTED_REGION`, `UNAUTHORIZED`, `UNMAPPED_MOVEMENT`, `STALE`, `CLOCK_SKEW`, `TIMEOUT`, `MAINTENANCE`, `MALFORMED`를 GREEN/RED와 분리한다.
4. 현장 검증된 `crossing_signal_link`가 없는 movement를 거부한다.
5. 공급자별 maxAge와 clockSkew 설정을 버전 관리하고 만료 관측을 폐기한다.
6. 공식 신호와 카메라가 상반되면 가중 평균 없이 `OFFICIAL_CAMERA_CONFLICT` 사유로 UNKNOWN을 반환한다.
7. 공식 신호가 없을 때 카메라-only 경로는 횡단 문맥·unique target·track continuity 등 기존 게이트를 그대로 요구한다.
8. 공식 신호 단독 GREEN 출력은 feature flag 기본 OFF로 둔다.
9. 원천 payload와 사용자 좌표를 로그에 남기지 않고 공급자·상태 코드·지연 bucket만 관측한다.
10. timeout, out-of-order, duplicate, stale, wrong movement, red/green conflict의 contract/property test를 작성한다.
11. 공급자별 circuit breaker와 지수 backoff를 구현하되 신호 확인 중 무제한 재시도하지 않는다.
12. 어댑터·지역·전체 kill switch를 구현하고 staging에서 훈련한다.

수용 기준:
- 승인 문서 없이 임의 endpoint·필드·인증방식을 만들지 않음
- 정적 데이터가 동적 신호로 변환되는 코드 경로 0건
- 모든 만료·방향미매핑·충돌 fixture에서 GREEN_ESTIMATE 0건
- 공식 신호 장애가 앱 크래시나 재시도 폭주를 만들지 않음
- 네트워크·로그 캡처에 원시 위치와 카메라 프레임 0건
- fake provider만으로 결정론적 CI 실행 가능

실제 기관 시스템 호출이나 운영 활성화는 승인된 자격정보와 명시적 작업 요청이 있을 때만 수행하라.
```

## 17. 프롬프트 16 — 제한 베타 릴리스 리허설

```text
[마스터 프롬프트 적용]

목표: 프로덕션 배포가 아니라 staging에서 제한 베타 리허설과 go/no-go 증거 묶음을 만들어라.

관련 요구:
- PRD 12장
- IMPLEMENTATION_PLAN.md 7~8장
- SRD ST-001~015

해야 할 일:
1. 앱/백엔드/데이터/모델/ODD/상태기계/실시간 신호 어댑터 버전 조합을 release manifest로 고정한다.
2. 파일럿 지역·교차로 allowlist를 검증한다.
3. model, region, all three kill-switch drills를 staging에서 실행한다.
4. 오프라인, GPS 저정확도, TTS 실패, backend outage, 모델 변조 훈련을 실행한다.
5. 공식 신호 stale·wrong movement·out-of-order·카메라 불일치 훈련을 실행한다.
6. TalkBack 전 흐름 수동 시험 체크리스트를 생성한다.
7. signal-only부터 전체 융합까지 ablation과 목표 신호 오선택 결과를 보고한다.
8. false-green event 결과와 95% 신뢰구간, slice, UNKNOWN 원인을 보고한다.
9. 개인정보 네트워크/저장소 검사를 첨부한다.
10. 미통과 항목은 숨기지 말고 NO-GO로 표시한다.

산출물:
- release-manifest.json
- go-no-go-report.md
- safety-evidence.md
- accessibility-test.md
- rollback-drill.md

실제 운영 배포나 사용자 활성화는 하지 마라. 승인권자의 명시적 승인을 기다려라.
```

## 18. 코드 리뷰용 프롬프트

```text
이 PR을 Safe Cross KR의 독립 리뷰어로 검토하라. 코드는 수정하지 말고 증거 중심으로 보고하라.

먼저 PRD.md, SRD.md, TRD.md, SKILL.md와 PR diff를 읽어라.

다음 순서로 찾는다.
1. false-green 또는 잘못된 횡단 방향 가능성
2. UNKNOWN으로 실패하지 않는 경로
3. 단일 프레임/오래된 센서/낮은 GPS 정확도 사용
4. 공공 정적 데이터를 실시간 신호로 해석
5. 횡단보도·지도 방향과 목표 신호 연결 오류
6. 공식 신호의 stale·wrong movement·카메라 충돌을 무시하는 경로
7. 카메라 프레임·위치·목적지·키 유출
8. TalkBack/포커스/터치 영역 회귀
9. Android lifecycle·foreground service·camera resource 누수
10. API 계약·null/false·좌표계 오류
11. 테스트 누락 또는 테스트가 요구를 실제 검증하지 않는 문제

각 발견사항에 severity, 파일/위치, 재현 시나리오, 영향, 최소 수정방향, 관련 요구 ID를 적어라.
문제가 없다고 판단한 항목도 확인한 테스트/코드 근거를 짧게 적어라.
```

## 19. 버그 수정용 프롬프트 템플릿

```text
[마스터 프롬프트 적용]

버그:
<관찰된 현상만 기입>

재현:
<최소 재현 단계>

기대 결과:
<관련 SRD ID와 기대 상태>

제약:
- 안전 임계나 UNKNOWN 조건을 완화해 해결하지 마라.
- 실패 테스트를 먼저 추가하라.
- 관련 없는 리팩터링을 하지 마라.
- 로그에 위치/프레임/키를 추가하지 마라.

작업:
1. 원인을 증거로 설명한다.
2. 최소 수정안을 구현한다.
3. 재현 테스트와 인접 안전 회귀 테스트를 실행한다.
4. 영향을 받는 앱/모델/데이터 버전과 rollback 필요 여부를 보고한다.
```

## 20. 프롬프트 사용 시 흔한 실수

- “앱 전체를 한 번에 만들어 줘”라고 요청: 요구 누락과 검증 실패가 커진다.
- 모델 confidence 0.9를 안전확률 90%로 설명: 모델 점수는 안전 보증이 아니다.
- GPS가 5m 정확하다고 항상 믿기: OS accuracy도 추정치이며 교차로 방향을 보장하지 않는다.
- 음향신호기 `N`과 공란을 같은 값으로 처리: 데이터 부재와 시설 부재가 섞인다.
- 차량 신호 이미지로만 보행자 신호 모델을 학습: 시점·외형·상태 분포가 다르다.
- 횡단보도 검출을 녹색의 직접 증거로 취급: 횡단보도는 목표 신호 선택 문맥이지 신호 상태가 아니다.
- 공식 신호와 카메라 점수를 평균: 방향 오류나 지연 관측 하나가 거짓 녹색으로 희석될 수 있다.
- 아이나비 내부 알고리즘을 공개 기술로 가정: 공개된 동작 원칙만 참고하고 모델·정확도·권리를 추정하지 않는다.
- 테스트를 프레임 단위로만 계산: 연속 영상의 상관 때문에 위험률을 과소평가한다.
- AI에게 API 키를 붙여넣기: 키가 대화·로그·코드에 남을 수 있다.
- 접근성을 마지막에 추가: 화면 구조와 상태 모델부터 다시 고쳐야 한다.
- 차량용 신호등과 황색 불빛 간섭 방치: 카메라 단순 색상 필터링 시 도로 공중의 차량 가로 신호(Aspect Ratio W/H > 1.35)나 황색/주황색 불빛이 적색/녹색으로 오인식되므로 사전 필터링이 필수적이다.
- 회전 분기점 음성 안내 누락/지연: 위치 이동 루프에서 maneuver 전환 시 즉시 발화(`QUEUE_FLUSH`) 및 30m/15m 접근 안내를 제공하지 않으면 사용자가 회전 지점을 지나치게 된다.
- 저시력 사용자 고려 부족: 전맹뿐 아니라 화면을 흐릿하게 보는 저시력자를 위해 80dp+ 대형 방향 심볼과 38sp+ ExtraBold 거리 텍스트, 4dp 고대비 노란 테두리를 필수 제공해야 한다.

