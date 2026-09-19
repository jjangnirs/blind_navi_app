# ADR 0009: SK TMAP 전국 POI 실시간 통합검색 및 기기 Geocoder 2차 폴백 (TMAP Real-time POI Search & Geocoder Fallback)

- **상태(Status)**: Accepted
- **결정일(Date)**: 2026-09-18
- **결정자(Deciders)**: Safe Cross KR Architecture & Mobile Engineering Team
- **관련 요구사항**: PR-F-026, SR-F-085, SR-F-086, TRD 4.3.1

---

## 1. 배경 및 맥락 (Context)

1. **실제 목적지 검색 불가 문제**:
   - 기존 앱은 광주광역시 4개 장소(광주광역시청, 평화공원, 상무역, 광주세광학교)만 하드코딩되어 있었으며, 실제 장소 검색 API(POI 검색)가 일체 연동되어 있지 않았습니다.
   - 사용자가 "서울역", "강남역", "스타벅스", "시청" 등 실제 가고 싶은 목적지를 검색창에 입력하거나 음성으로 말해도 결과가 없거나, 현재 위치 북쪽 200m에 임의 가짜 좌표가 생성되는 치명적인 결함이 존재했습니다.

2. **TMAP 보행자 API의 5km 보행 거리 제한**:
   - SK TMAP의 공식 보행자 경로 API(`pedestrian`)는 출발지와 목적지 직선거리가 최대 5km 이내일 때만 경로를 계산합니다.
   - 타 지역에서 앱을 켜고 하드코딩된 광주 목적지를 누르거나 장거리 장소를 선택하면 거리 초과 에러로 경로 생성이 실패했습니다.

---

## 2. 의사결정 (Decisions)

### 1) SK TMAP 공식 POI 통합검색 API 연동 (`TmapPoiRepository.kt`)
- 엔드포인트: `https://apis.openapi.sk.com/tmap/pois?version=1`
- 파라미터: `searchKeyword`, `count=20`, `resCoordType=WGS84GEO`, `reqCoordType=WGS84GEO`, `centerLat/centerLon`
- 헤더: `appKey: BuildConfig.TMAP_APP_KEY`, `Accept: application/json`
- 전국의 모든 기차역, 지하철역, 공공기관, 상호, 건물명, 아파트, 도로명 주소를 실시간 검색하여 정확한 위경도 좌표(`noorLat`, `noorLon`)와 정규화된 주소를 수신합니다.

### 2) 안드로이드 플랫폼 내장 `Geocoder` 2차 안전 폴백
- 네트워크 장애, 오프라인 또는 TMAP API 오류 시 기기 내장 `android.location.Geocoder.getFromLocationName`으로 자동 2차 폴백하여 주소 및 지명 검색이 항상 동작하도록 이중화했습니다.

### 3) 현재 위치 기준 거리 계산 및 보행 가능 여부 뱃지 노출
- 스마트폰 GPS와 검색된 각 장소의 거리를 실시간 계산하여 직관적인 시각 뱃지로 제공합니다:
  - **5km 이내(보행 가능)**: `🚶 350m`, `🚶 1.8km` (🟢 초록색 뱃지)
  - **5km 초과(보행 거리 초과)**: `⚠️ 15km (초과)` (🔴 빨간색 뱃지, 장거리 시 대중교통 이용 안내)
- TMAP 5km 보행 제한을 사전에 인지하여 경로 생성 실패를 방지합니다.

### 4) 하이브리드 즉각 반응형 UX 및 음성 검색 자동 연동
- **즉각 반응(0ms)**: 로컬 추천/즐겨찾기 목록을 즉시 필터링하여 검색 체감 속도를 극대화.
- **디바운스(300ms)**: 타이핑 완료 시 과도한 네트워크 트래픽을 방지하며 전국 POI 클라우드 검색 수행.
- **음성 인식 연동**: 음성으로 목적지를 말하면 자동으로 검색창에 입력되고, 검색 결과 건수와 첫 번째 장소명을 음성으로 낭독하여 시각장애인 접근성을 완성.

---

## 3. 파급 효과 (Consequences)

- **긍정적 효과**:
  - 전국 어디서나 사용자가 원하는 실제 목적지를 텍스트나 음성으로 자유롭게 검색하여 정확한 위경도로 보행 경로를 안내받을 수 있음.
  - 목적지까지의 거리와 5km 보행 가능 여부가 한눈에 파악되어 헛걸음 방지.
  - TMAP API + 기기 Geocoder의 이중화로 뛰어난 서비스 가용성 확보.
- **검증**:
  - `DestinationViewModelTest` 및 전체 147개 단위 테스트 100% 통과 확인.
