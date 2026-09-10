# ADR 0001: TMAP 보행자 경로 API 어댑터 통합 및 접근성 한계 규정

- **상태(Status)**: Accepted
- **결정일(Date)**: 2026-09-08
- **결정자(Deciders)**: Safe Cross KR Architecture Team
- **관련 요구사항**: SR-F-010~015, TRD.md 9장, DATA_SOURCES.md 3장

---

## 1. 배경 및 맥락 (Context)

시각장애인 및 저시력자 보행 보조 내비게이션을 위해 출발지부터 목적지까지의 도보 이동 경로 안내 기능이 필요하다. 국내 도로교통 환경에서 가장 정밀한 보행자 네트워크 데이터와 계단 회피 옵션을 제공하는 상용 공급자로 **TMAP 보행자 경로안내 API**를 1차 공급자로 선정하였다.

### 공식 TMAP 문서 조사 내용
- **공식 문서 출처**: <https://tmap-skopenapi.readme.io/reference/보행자-경로안내>
- **엔드포인트(Endpoint)**: `POST https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1`
- **인증 방식**: HTTP Header `appKey: <YOUR_APP_KEY>`
- **좌표 체계**: `reqCoordType: "WGS84GEO"`, `resCoordType: "WGS84GEO"` (WGS84 경위도 좌표계, X=경도, Y=위도)
- **필수 파라미터**:
  - `startX`: 출발지 X좌표 (경도, float)
  - `startY`: 출발지 Y좌표 (위도, float)
  - `endX`: 목적지 X좌표 (경도, float)
  - `endY`: 목적지 Y좌표 (위도, float)
  - `startName`: 출발지 명칭 (UTF-8 인코딩 문자열)
  - `endName`: 목적지 명칭 (UTF-8 인코딩 문자열)
- **경로 탐색 옵션 (`searchOption`)**:
  - `0`: 추천 (기본값)
  - `4`: 추천 + 대로우선
  - `10`: 최단
  - `30`: 최단거리 + 계단제외
- **제약 사항**:
  - 경유지(`passList`): 최대 5곳 (`X1,Y1_X2,Y2` 포맷)
  - 응답 포맷: GeoJSON `FeatureCollection` (Point: 회전/시설 노드, LineString: 보행 링크)

---

## 2. 의사결정 (Decisions)

### 1) 공급자가 보장하지 않는 접근성 한계 및 "안전 경로" 표기 금지 원칙
- TMAP의 `searchOption=30`(계단제외)은 일반적인 육교 계단이나 건물 계단을 우회하는 기능일 뿐이며, 다음 사항을 **전혀 보장하지 않는다**:
  1. 보도와 차도 사이의 단차(연석 턱낮춤 2cm 이하) 유무
  2. 점자블록(선형/점형 유도블록)의 연속성 및 설치 상태
  3. 시각장애인용 음향신호기 설치 및 동작 여부
  4. 휠체어/유모차가 주행 가능한 완만한 경사도
  5. 보도 위 불법 적치물, 볼라드, 가로수 등 장애물 부재
- **절대 규칙**:
  - 시스템 내 모든 API 응답, 모바일 화면, 음성 안내(TTS), 문서에서 TMAP 경로를 **"장애인 안전 경로"** 또는 **"안전한 길"**로 지칭하거나 표현하는 것을 엄격히 금지한다.
  - 공식 명칭은 **"계단 제외 옵션이 적용된 보행 경로"**로 한정하며, 검증된 공공데이터 시설(횡단보도, 음향신호기 등)을 해당 경로 위에 독립된 문맥으로 중첩 투영하여 사용자에게 안내한다.
  - 모든 경로 API 응답에는 법적·안전 고지 문구(`disclaimer`)를 필수로 포함한다.

### 2) API 키 보안 및 프록시 아키텍처 (SR-F-015, SR-NF-021)
- `appKey`는 클라이언트(모바일 Android APK) 및 Git 저장소에 절대 포함하지 않는다.
- 백엔드 서버 환경변수(`TMAP_APP_KEY`) 및 Secret Manager에서만 안전하게 주입받으며, 모바일 앱은 백엔드의 내부 프록시 API(`POST /v1/routes/pedestrian`)만을 호출한다.

### 3) 포트/어댑터(Hexagonal) 패턴 적용
- 라우팅 엔진 교체 및 다중 공급자 지원을 위해 도메인 계층에 `PedestrianRouter` 추상 인터페이스(Port)를 정의한다.
- `TmapPedestrianRouter` 어댑터를 구현하여 TMAP 특화 파라미터를 내부 정규화 모델(`NormalizedRoute`, `Maneuver`, `RouteSegment`)로 변환한다.
- 로컬 개발 및 자동화 테스트 환경을 위해 실제 외부 네트워크 호출 없이 계약을 100% 검증하는 `FakePedestrianRouter`를 기본 제공한다.

### 4) 회복 탄력성(Resilience) 정책
- **타임아웃(Timeout)**: Connect 타임아웃 3.0초, Read 타임아웃 5.0초를 적용하여 모바일 응답 지연을 차단한다.
- **재시도(Retry) 정책**:
  - `4xx` (400, 401, 403, 404): 계약 위반 또는 인증 실패이므로 절대 재시도하지 않고 내부 표준 오류(`PROVIDER_AUTH_ERROR`, `PROVIDER_BAD_REQUEST`)로 즉시 변환한다.
  - `429` (Rate Limited): 공급자 쿼터 초과 시 재시도로 폭주시키지 않고 `Retry-After` 헤더를 존중하거나 `PROVIDER_RATE_LIMITED`로 차단한다.
  - `5xx` / 네트워크 타임아웃: 일시적 장애에 한해 최대 2회 제한 지수 백오프(0.5초, 1.0초) 재시도를 수행한다.
- **서킷 브레이커(Circuit Breaker)**:
  - 5회 연속 5xx/타임아웃 실패 시 회로를 `OPEN`하여 30초 동안 외부 호출을 즉시 차단하고 `PROVIDER_CIRCUIT_OPEN` 에러를 반환함으로써 연쇄 장애를 방지한다.

---

## 3. 결과 및 영향 (Consequences)

- **긍정적 영향**:
  - 모바일 앱이 외부 공급자 스펙 변경이나 키 유출 위험에서 완전히 분리됨.
  - "안전 경로"라는 허위 신뢰를 사용자에게 주지 않아 시각장애인 보행 시 안전사고 위험을 예방함.
  - 외부 공급자 장애 발생 시에도 서킷 브레이커와 내부 안정 오류 코드를 통해 모바일 앱이 예측 가능하게 동작함.
  - 테스트 시 TMAP 유료 쿼터 소모 없이 100% 모의 테스트 가능.
- **수반되는 과제**:
  - 향후 진정한 배리어프리 경로(음향신호기 우선, 단차 회피 등)를 제공하기 위해서는 공공데이터 및 현장 검증 데이터가 축적된 후 독자적인 보행 네트워크 그래프 기반 라우팅 엔진(Phase 2) 구축이 필요함.
