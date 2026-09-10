# ADR-0006: 공식 실시간 보행신호 연계 아키텍처 및 안전 융합

## 상태
승인됨 (Approved) — 2026-09-09

## 문맥
한국도로교통공단 및 지자체(서울, 광주 등)는 C-ITS 인프라 및 신호연계 센터를 통해 실시간 보행신호 잔여시간 및 현시 정보를 연계하고 있습니다 (DATA_SOURCES.md 2.5~2.7). 그러나 전국 단위의 표준화된 공개 REST API 명세나 전역 SLA는 공식 확정되지 않았으며, 지자체별/센터별로 movement ID 규격, 시계 동기화 오차, 갱신 주기가 상이합니다.

정적 공공데이터(전국신호등표준데이터 CSV)의 명목 신호시간(예: 녹색 30초)은 실시간 현시를 대변할 수 없으며, 이를 동적 신호로 변환하는 것은 시각장애인에게 치명적인 False-Green을 유발할 수 있습니다.

따라서 Safe Cross KR은 다음 원칙을 엄격히 수립합니다:
1. **임의 엔드포인트/프로토콜 추정 금지**: 승인된 공식 자격정보 및 기술 계약서가 없는 상태에서 임의의 외부 HTTP 클라이언트를 작성하지 않고, 클린 아키텍처 기반 포트(`SignalStatusProvider`)와 오프라인 시험용 `FakeSignalStatusProvider`까지만 구현한다.
2. **가중 평균 금지 (Strict Conflict Veto)**: 카메라 영상 관측과 공식 신호 데이터가 상반(Official=GREEN vs Camera=RED, 또는 Official=RED vs Camera=GREEN)될 경우, 어떠한 점수 평균이나 확률적 융합도 수행하지 않고 즉각 `OFFICIAL_CAMERA_CONFLICT` 사유로 `UNKNOWN` 안전 격리한다.
3. **공식 신호 단독 녹색 기본 차단**: 공식 신호 단독으로 `GREEN_ESTIMATE`를 발화하는 기능(`allowOfficialOnlyGreen`)은 별도의 공인 안전 승인이 있기 전까지 Feature Flag 상에서 기본값 `false`로 엄격 차단한다.
4. **개인정보 비식별 원칙 (SR-NF-022, SR-NF-041)**: 원천 통신 페이로드 원문 및 사용자의 위경도 좌표는 로깅하지 않으며, 공급자 ID, 결과 상태코드, 지연 버킷(`<100ms`, `100-300ms`, `300-500ms`, `>500ms`)만을 관측한다.

## 결정 사항

### 1. 공급자 독립적 포트 및 정규화 계약
- `SignalStatusProvider` 인터페이스:
  - `fetchSignalStatus(intersectionId: String, movementId: String, currentElapsedRealtimeNanos: Long): SignalFetchResult`
- 정규화 규격 (`NormalizedSignalStatus`):
  - `provider: String` (공급자 식별자)
  - `providerIntersectionId: String` (교차로 관리번호)
  - `movementId: String` (보행 횡단 방향 식별자)
  - `state: OfficialSignalState` (`RED`, `GREEN`, `UNKNOWN`)
  - `sourceTimestampEpochMs: Long` (신호제어기/센터 생성시각)
  - `receivedElapsedRealtimeNanos: Long` (기기 단조 수신시각)
  - `optionalRemainingSeconds: Int?` (보행 잔여시간, null 허용)
  - `qualityFlags: List<String>` (품질 및 결측 플래그)

### 2. 비정상 및 에러 상태의 엄격한 분리 (`SignalErrorReason`)
신호 상태(`RED`, `GREEN`)와 인프라/통신 장애 사유를 엄격히 분리하여 결코 장애가 정상 상태로 오인되지 않도록 합니다:
- `UNSUPPORTED_REGION`: 미지원 서비스 지역
- `UNAUTHORIZED`: 인증 실패 / 계약 만료
- `UNMAPPED_MOVEMENT`: 현장 검증된 `crossing_signal_link`에 매핑되지 않은 방향
- `STALE`: 최대 허용 나이(`maxAgeNanos`, 기본 5초) 초과 만료
- `CLOCK_SKEW`: 기기 시계와 소스 시계 간의 비정상 왜곡 또는 시간 역행
- `TIMEOUT`: 통신 타임아웃 (기본 3.0초)
- `MAINTENANCE`: 제어기/센터 점검 중
- `MALFORMED`: 파싱 및 데이터 규격 오류
- `CIRCUIT_OPEN`: 서킷 브레이커 발동 차단

### 3. 공급자별 보호 정책 (서킷 브레이커 & 지수 백오프)
- 3회 연속 실패 시 `OPEN` 상태로 전이되어 30초간 외부 호출 즉시 차단
- 신호 확인 주기 중 무제한 재시도를 금지하며, 최대 재시도 2회, 지수 백오프(500ms, 1000ms) 적용

### 4. 3단계 킬스위치 (`SignalKillSwitchRegistry`)
- `providerKill`: 특정 공급자 어댑터 비활성화
- `regionKill`: 특정 지자체/지역(예: "KR-42") 비활성화
- `globalKill`: 전체 실시간 신호 융합 즉시 비활성화

## 결과
- 공개 검증되지 않은 API에 대한 의존성 리스크 없이, 결정론적 Fake Provider를 통해 CI에서 100% 테스트 가능한 아키텍처 확보.
- 공식 신호와 카메라 간의 충돌 시 False-Green을 원천 차단하는 결정론적 안전 보장.
