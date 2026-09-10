# Safe Cross KR Backend API

FastAPI 기반의 Safe Cross KR 백엔드 서비스입니다.  
시각장애인 및 저시력자 보행 안전을 위한 보행 경로 프록시, 주변 시설 공간 쿼리, 승인된 실시간 신호 상태 제공 및 온디바이스 모델 킬스위치 매니페스트 API를 제공합니다.

## 주요 기능 및 아키텍처

### 1. TMAP 보행자 경로 프록시 (`POST /v1/routes/pedestrian`)
- **API 키 은닉**: TMAP 상용 `appKey`를 모바일 APK나 Git에 일체 노출하지 않고 백엔드 환경변수에서 주입.
- **계단 제외 강제 (`searchOption="30"`)**: 보행 약자 안전을 위해 계단 구간 우회 경로 생성.
- **안전 고지 의무 포함**: 경로 응답에 턱낮춤 2cm 이하, 점자블록, 음향신호기를 보장하지 않는다는 법적 고지(`disclaimer`) 필드 필수 탑재.

### 2. 주변 시설 공간 쿼리 (`GET /v1/crossings/nearby`)
- PostGIS 기반 반경 내 검증된 횡단보도, 음향신호기 설치 여부(삼항 논리: `YES`/`NO`/`UNKNOWN`), 점자블록 유무 제공.
- 프라이버시 보호: 사용자 상세 위경도 및 목적지는 서버 로그에 남기지 않고 마스킹(`[REDACTED]`).

### 3. 승인된 실시간 보행신호 어댑터 (`GET /v1/signals/realtime`)
- 지자체/도로교통공단 연계 승인 교차로 대상 실시간 신호 상태 정규화.
- Freshness(최대 수명 5초) 초과 및 시계 역행 감지 시 즉각 폐기, 카메라와 불일치 시 `Strict Veto` 발동.

### 4. 원격 모델 3단계 킬스위치 매니페스트 (`GET /v1/models/manifest`)
- 온디바이스 LiteRT 모델 SHA-256 무결성 검증 매니페스트 배포.
- 중대 오탐 발생 시 모델별 / 지역별 / 전역 3단계 원격 긴급 비활성화.

## 실행 방법

```powershell
# 가상환경 활성화 후
cd backend
pip install -e ".[dev]"
uvicorn app.main:app --reload --port 8000
```

## 테스트 실행

```powershell
pytest
```
