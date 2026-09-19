# 데이터 출처 및 가져오기 절차

> 기준일: 2026-09-04  
> 문서 버전: 0.2 — 실시간 신호·횡단보도 문맥 융합 반영
> 원칙: URL은 랜딩 페이지를 기준으로 기록한다. 일회성 다운로드 URL이나 로그인 세션 URL을 코드에 고정하지 않는다.

## 1. 가장 먼저 사용할 공식 데이터

### 1.1 전국신호등표준데이터

- 공식 URL: <https://www.data.go.kr/data/15028198/standard.do>
- 제공: 지방자치단체 취합, 소관 경찰청
- 갱신 안내: 반기, 기관 자료를 매월 초 전국 단위로 병합할 수 있어 시차 존재
- 주요 열:
  - 시도명, 시군구명
  - 소재지도로명주소, 소재지지번주소
  - 위도, 경도
  - 신호등관리번호, 신호등구분
  - 보행자작동신호기유무
  - 잔여시간표시기유무
  - 시각장애인용음향신호기유무
  - 관리기관명, 관리기관전화번호
  - 데이터기준일자
- 용도: 신호등 후보 위치와 음향신호기 설치 속성 보완
- 주의:
  - 현재 신호색을 실시간으로 주는 데이터가 아니다.
  - 한 교차로에 여러 등기구 레코드가 있을 수 있다.
  - `시각장애인용음향신호기유무=N`과 공란을 구분한다.
  - 기관별 최신성과 좌표 의미가 다를 수 있다.

### 1.2 전국횡단보도표준데이터

- 공식 URL: <https://www.data.go.kr/data/15028201/standard.do>
- 제공: 지방자치단체 취합, 소관 국토교통부·경찰청
- 갱신 안내: 반기, 개별 기관 자료 병합에 시차 존재
- 주요 열:
  - 횡단보도관리번호, 횡단보도종류
  - 위도, 경도
  - 차로수, 횡단보도폭, 횡단보도연장
  - 보행자신호등유무
  - 보행자작동신호기유무
  - 음향신호기설치여부
  - 녹색신호시간, 적색신호시간
  - 교통섬유무, 보도턱낮춤여부, 점자블록유무
  - 관리기관, 데이터기준일자
- 용도: 앱의 기본 crossing 엔터티
- 주의:
  - 녹색/적색 시간은 고정 현시가 아닐 수 있고 비어 있을 수 있다.
  - 시간 정보로 현재 신호 상태를 계산하면 안 된다.
  - 위·경도가 횡단 시작점, 중심점, 대표점 중 무엇인지 기관 확인이 필요하다.

두 데이터의 공통 핵심은 “시설 위치와 속성”이다. 앱은 이 데이터를 신호가 지금 녹색이라는 근거로 사용하지 않는다.

## 2. 지역 보완 데이터

### 2.1 서울 보행자 신호등 분포도

- 공식 URL: <https://www.data.go.kr/data/15124211/fileData.do>
- API 문서 namespace 안내: <https://infuser.odcloud.kr/oas/docs?namespace=15124211/v1>
- 특징: 자치구, 보행등 관리번호, 연결 지주 관리번호, X/Y 좌표, 주소
- 한계: 서울 한정이며 좌표계 정의를 컬럼 정의서에서 반드시 확인한다.
- 사용: 데이터 수집 파이프라인의 지역 어댑터 예제로 적합하다.

### 2.2 서울 음향신호기 정보

- 공식 URL: <https://data.seoul.go.kr/dataList/OA-15543/S/1/datasetView.do?tab=A>
- 특징: 음향신호기 관리번호 등 서울시 별도 정보
- 사용: 전국 표준의 음향신호기 속성을 교차검증하는 예시
- 주의: 서울 열린데이터광장 키, 호출 규칙, 라이선스를 별도로 확인한다.

### 2.3 대구 음향신호기 설치 현황

- 공식 URL: <https://www.data.go.kr/data/15103064/fileData.do>
- 특징: 교차로별 방향별 음향신호기 대수
- 한계: 위치 좌표보다 교차로·방향별 집계 중심이므로 지오코딩과 검수 필요
- 사용: 지역마다 음향신호기 데이터 구조가 다름을 시험하는 fixture

### 2.4 광주광역시 교통정보센터 Open API 안내

- 공식 URL: <https://www.gjtic.go.kr/open-api>
- 현재 안내 항목 예: 소통 통계, 교통 흐름, 주정차 단속구간, 스쿨존, 통합주차
- 사용: 광주 파일럿의 교통·스쿨존 보완 후보
- 주의: 이 페이지에 신호등 실시간 현시가 명시되어 있지 않으므로 있다고 가정하지 않는다. 필요하면 광주 교통시설 담당 기관에 별도 제공 가능성을 문의한다.

### 2.5 서울 교차로 실시간 신호 잔여정보 예시

- 공식 URL: <https://t-data.seoul.go.kr/dataprovide/trafficdataviewopenapi.do?data_id=10120>
- 특징: 일부 C-ITS 교차로의 방향별 신호 잔여시간 필드가 있는 API 예시
- 한계: 서울·대상 교차로 한정, 방향 코드와 데이터 신선도 해석 필요
- 사용: 전국 기능이 아니라 `RegionalSpatProvider` 플러그인의 연구용 예시

SPaT를 추가할 때도 카메라/현장 신호와 충돌하면 녹색을 선택하지 않고 UNKNOWN으로 축소한다.

### 2.6 한국도로교통공단 실시간 신호정보 협력 체계

- 공식 안내: <https://www.koroad.or.kr/main/board/6/89006/board_view.do?bdNoticeYn=N&bdOpenYn=Y&cp=22&listType=list>
- 공식 확인 내용:
  - 한국도로교통공단은 지자체 신호제어 인프라를 연계해 신호 잔여시간 서비스를 확대하고 있다.
  - 광주광역시와 아이나비시스템즈 등은 2022년부터 민관 협의체에 참여했다.
  - 센터 기반 방식이므로 전국표준 신호등 CSV와는 별도의 동적 데이터 흐름이다.
- 적용 원칙:
  - 공개 REST API가 있다고 가정하지 않는다.
  - 한국도로교통공단과 광주 담당기관에 서비스 참여 절차, 제공 계약, 시험 환경, 보행신호 포함 여부를 서면 문의한다.
  - provider intersection ID, movement/approach ID, 상태 코드, source timestamp, 잔여시간 의미, 품질 플래그, 최대 지연, 점검·장애 공지를 문서로 받는다.
  - 승인받은 교차로 allowlist에만 `RegionalSignalStatusProvider`를 활성화한다.

기관 문의 체크리스트:

1. 보행자 신호의 적색·녹색·녹색점멸과 잔여시간이 모두 제공되는가?
2. 한 교차로의 횡단 방향을 구분하는 movement ID와 MAP/기하 정보가 있는가?
3. 생성시각과 수신시각의 기준 시계 및 허용 오차는 얼마인가?
4. 지연·누락·점검·신호제어기 장애는 어떤 상태 코드로 표시되는가?
5. 개발·시험·상용 환경, 인증, 호출량, 재배포·음성 안내 허용조건은 무엇인가?
6. 사용자가 현장에서 본 신호와 데이터가 다를 때 신고·정정 절차는 무엇인가?

실행 순서:

1. 담당부서 확인 후 “보행자용 실시간 신호 상태와 잔여시간의 연구·서비스 연계”를 명시한 기술문의서를 보낸다.
2. 파일럿 교차로 목록, 사용자군, 안전 설계와 개인정보 최소화 방안을 첨부한다.
3. 계약·승인 전에는 mock provider와 fixture만 사용한다.
4. 승인 후에도 현장 신호와 shadow 비교를 끝내기 전 사용자 녹색 안내에 사용하지 않는다.

### 2.7 아이나비 TLCA 기술 참고

- 공식 제품 설명: <https://www.inavi.com/navigation/아이나비-x100/>
- 기능명: TLCA(Traffic Light Change Alarm)
- 공식 설명상 동작: AR 카메라를 사용하고 차량이 완전히 정차한 뒤 적색→녹색 변화를 화면과 소리로 안내한다.
- 프로젝트에 주는 교훈:
  - 정지 상태를 먼저 확인해 탐색 범위를 제한한다.
  - 단일 색상보다 같은 목표 신호의 시간 변화를 추적한다.
  - 복합·모호 신호는 알림을 제한한다.
  - 차량의 고정 카메라와 달리 스마트폰은 자세가 계속 변하므로 횡단보도·지도·방위 문맥이 추가로 필요하다.

아이나비의 내부 모델과 학습자료는 공개되지 않았으므로 특정 알고리즘·정확도·라이선스를 추정해 복제하지 않는다.

## 3. 보행 경로 및 장소

### 3.1 TMAP 보행자 경로안내

- 공식 문서: <https://tmap-skopenapi.readme.io/reference/보행자-경로안내>
- Endpoint: `POST https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1`
- 인증: `appKey` 요청 헤더
- 좌표: 기본 WGS84GEO, X=경도, Y=위도
- 주요 옵션:
  - `searchOption=0`: 추천
  - `searchOption=10`: 최단
  - `searchOption=30`: 최단거리+계단제외
  - `passList`: 경유지 최대 5곳
- 사용 전 확인:
  - 현재 요금·쿼터·상업 이용 약관
  - 지도 표시 의무와 캐시 허용 범위
  - 서버 프록시 허용 여부
  - 장애 시 SLA와 데이터 보존 정책

예시 요청은 키를 코드에 직접 쓰지 않는다.

```bash
curl --request POST \
  --url 'https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1' \
  --header "appKey: ${TMAP_APP_KEY}" \
  --header 'content-type: application/json' \
  --data '{
    "startX": 126.8500,
    "startY": 35.1500,
    "endX": 126.8600,
    "endY": 35.1600,
    "startName": "출발",
    "endName": "도착",
    "reqCoordType": "WGS84GEO",
    "resCoordType": "WGS84GEO",
    "searchOption": "30"
  }'
```

실제 요청 형식은 계정에 표시되는 최신 Swagger/공식 예제를 우선한다.

### 3.2 SK TMAP 통합검색 (POI API)

- 공식 문서: <https://tmap-skopenapi.readme.io/reference/poi-통합검색>
- Endpoint: `GET https://apis.openapi.sk.com/tmap/pois?version=1`
- 인증: `appKey` 요청 헤더
- 주요 파라미터:
  - `searchKeyword`: 검색어 (역명, 건물명, 상호, 도로명 주소 등)
  - `count=20`: 결과 건수
  - `centerLat`, `centerLon`: 스마트폰 현재 GPS 기준 거리순 정렬 좌표
  - `reqCoordType="WGS84GEO"`, `resCoordType="WGS84GEO"`
- 용도: 전국 99.999% 장소 실시간 검색, 목적지 입구 좌표(noorLat/noorLon) 획득, 현재 위치 기준 거리 계산 및 5km 보행 제한 여부 안내.

### 3.3 대한민국 국토교통부 VWorld 표준 2D 정밀 전자지도

- 공식 포털: <https://www.vworld.kr/>
- 타일 URL: `https://xdworld.vworld.kr/2d/Base/service/{z}/{x}/{y}.png`
- 제공: 국토교통부 공간정보산업진흥원 (VWORLD)
- 특징: 전국 1:1000 상세 건물, 골목길, 도로명, 횡단보도를 100% 한글로 선명하게 표출.
- 3중 안전 폴백: VWorld 타일 에러 시 OpenStreetMap(`tile.openstreetmap.org`) → CartoDB Voyager로 즉시 자동 전환.

### 3.4 안드로이드 플랫폼 Geocoder

- API: `android.location.Geocoder`
- 용도: 네트워크 단절 또는 TMAP POI 장애 시 기기 내장 지오코더를 통한 2차 안전 주소/좌표 변환 폴백.

## 4. AI 학습·평가 후보 데이터

### 4.1 신호등/도로표지판 인지 영상(수도권 외)

- 공식 URL: <https://aihub.or.kr/aidata/27679>
- 내용: 국내 다양한 주행환경의 신호등·도로표지판 bounding box와 상태 속성
- 용도: 일반 신호 객체 detector pretraining, hard negative 확보
- 한계: 차량 카메라 시점이므로 보행자 스마트폰 시점과 분포가 다르다.

### 4.2 차선/횡단보도 인지 영상

- 수도권: <https://aihub.or.kr/aidata/27675>
- 내용: 횡단보도 polygon, 정지선·차선 정보
- 용도: 횡단보도 영역 인식의 연구 후보
- 한계: 역시 차량 시점이며 모바일 MVP의 필수 모델로 바로 쓰지 않는다.

### 4.3 Small object detection 이미지

- 공식 URL: <https://aihub.or.kr/aihubdata/data/view.do?dataSetSn=476>
- 내용: 여러 소형 객체 중 도로신호등과 보행자 신호등 클래스 포함
- 용도: 멀리 있는 작은 보행자 신호 detector의 pretraining 후보
- 한계: 상태색 라벨의 충분성, 촬영 시점, 라이선스를 데이터 설명서에서 재확인한다.

### 4.4 베리어프리존 주행영상

- 공식 URL: <https://aihub.or.kr/aihubdata/data/view.do?dataSetSn=186>
- 내용: 교통약자 시선에서 노면·공간 장애/편의 객체
- 용도: 향후 턱·경사·보행 방해물 기능 연구
- 한계: 신호 상태 판단의 직접 학습자료는 아니다.

### 4.5 다양한 주행환경 신호정보 데이터

- 공식 URL: <https://aihub.or.kr/aihubdata/data/view.do?dataSetSn=71579>
- 내용: 국내 환경·악천후 신호정보 영상과 라벨
- 용도: 악천후/조명 변화 연구와 hard negative
- 한계: 보행자 신호보다 차량 신호 중심일 수 있으므로 라벨 정의를 먼저 확인한다.

AI Hub 대용량 데이터는 계정, 활용 신청, 다운로드 도구, 이용조건 확인이 필요할 수 있다. 데이터 설명서와 구축활용가이드를 내려받아 라이선스·재배포·상업 이용 가능 여부를 법률 검토표에 기록한다.

### 4.6 횡단 문맥·지도·시간축 융합 연구

| 자료 | URL | 프로젝트 적용점 |
|---|---|---|
| Traffic Light Recognition Using Deep Learning and Prior Maps | <https://arxiv.org/abs/1906.11886> | 사전 지도와 영상 검출을 결합해 관련 신호 선택 |
| aUToLights | <https://arxiv.org/html/2305.08673v2> | 지도 prior, 신호 tracking, HMM 상태 필터 결합 |
| TLD-READY | <https://arxiv.org/html/2409.07284v1> | 주변 차선 문맥을 이용한 신호 relevance 평가 |
| Real-Time Walk Light Detection with a Mobile Phone | <https://pmc.ncbi.nlm.nih.gov/articles/PMC4778721/> | 시각장애인을 위한 휴대전화 보행신호 인식 선행연구 |

연구 결과의 수치를 Safe Cross KR 성능으로 가져오지 않는다. 카메라 설치 위치, 도시, 신호기 형태, 데이터 분포가 다르므로 설계 근거로만 사용하고 광주 스마트폰 시점에서 독립 검증한다.

## 5. 구현 공식 문서

| 주제 | 공식 URL | 적용 |
|---|---|---|
| CameraX | <https://developer.android.com/media/camera/camerax> | 카메라 Preview/ImageAnalysis |
| CameraX 구조 | <https://developer.android.com/media/camera/camerax/architecture> | use case와 lifecycle |
| LiteRT 개요 | <https://developers.google.com/edge/litert/overview> | 온디바이스 추론·2.x CompiledModel |
| LiteRT 추론 | <https://ai.google.dev/edge/litert/inference> | CPU/GPU/NPU backend 선택 |
| Android 위치 권한 | <https://developer.android.com/develop/sensors-and-location/location/permissions> | 정밀/대략/전경 위치 |
| Foreground service type | <https://developer.android.com/develop/background-work/services/fgs/service-types> | navigation의 `location`, 카메라 제한 |
| Android 접근성 | <https://developer.android.com/guide/topics/ui/accessibility/apps> | Compose semantics·검사 |
| Compose 터치 영역 | <https://developer.android.com/develop/ui/compose/accessibility/api-defaults> | 최소 48dp |
| Android TTS | <https://developer.android.com/reference/android/speech/tts/TextToSpeech> | 음성합성 |
| Android haptics | <https://developer.android.com/develop/ui/views/haptics> | 의미 기반 진동 |
| WCAG 2.2 | <https://www.w3.org/TR/WCAG22/> | 보조 접근성 기준 |

Android 공식 문서는 날짜에 따라 정책이 바뀐다. 특히 target SDK, foreground service, 위치 권한과 Google Play 정책은 출시 직전에 다시 확인한다.

## 6. 법률·제도 확인 출처

| 주제 | 공식 URL | 확인할 내용 |
|---|---|---|
| 위치정보법 | <https://www.law.go.kr/법령/위치정보의보호및이용등에관한법률> | 사업자 지위, 동의, 보호조치, 이용·제공 |
| 위치정보법 시행령 | <https://www.law.go.kr/법령/위치정보의보호및이용등에관한법률시행령> | 신고·등록·보호조치 세부 |
| 개인정보 보호법 | <https://www.law.go.kr/법령/개인정보보호법> | 최소수집, 처리근거, 파기, 안전조치 |
| 교통약자법 | <https://www.law.go.kr/법령/교통약자의이동편의증진법> | 이동편의·시설 관련 제도 |
| 교통약자법 시행규칙 | <https://www.law.go.kr/법령/교통약자의이동편의증진법시행규칙> | 음향신호기 등 시설 세부기준 |

서비스가 위치를 서버에서 어떤 방식으로 처리·보관·제공하는지에 따라 의무가 달라진다. 문서 링크만 보고 개발팀이 법률 결론을 내리지 말고 출시 구조가 확정되면 전문 자문을 받는다.

## 7. 공공데이터포털에서 파일로 받는 절차

1. 브라우저에서 전국신호등 또는 전국횡단보도 공식 URL을 연다.
2. 페이지의 `파일 다운로드` 영역으로 이동한다.
3. 개발 초기에는 CSV를 선택한다. CSV는 열 이름과 결측값을 확인하기 쉽다.
4. 다운로드한 파일 이름을 바꾸지 말고 다음과 같이 날짜 폴더에 보관한다.

```text
data/raw/data-go-kr/15028198/2026-09-04/original-file.csv
data/raw/data-go-kr/15028201/2026-09-04/original-file.csv
```

5. PowerShell에서 해시를 기록한다.

```powershell
Get-FileHash .\original-file.csv -Algorithm SHA256
```

6. CSV를 Excel로 먼저 저장하지 않는다. Excel이 날짜·관리번호·인코딩을 바꿀 수 있다.
7. 원본은 읽기 전용으로 두고 정규화 결과를 별도 `staging/`에 생성한다.

## 8. 공공데이터포털 API를 신청하는 절차

데이터셋마다 endpoint와 파라미터가 다르므로 Swagger에서 복사한다.

1. <https://www.data.go.kr/> 회원가입·로그인
2. 대상 데이터 페이지에서 `오픈 API` 또는 `활용신청` 선택
3. 개발 목적, 앱명, 예상 트래픽을 사실대로 입력
4. 자동승인/수동승인 상태 확인
5. `마이페이지 → 데이터활용 → Open API`에서 인증키 확인
6. 일반 인증키(Encoding/Decoding)와 API 문서 요구가 맞는지 확인
7. 상세 페이지의 Swagger에서 다음을 그대로 기록
   - Base URL
   - operation path
   - HTTP method
   - 인증키 위치(query/header)
   - page/perPage 또는 pageNo/numOfRows
   - 응답 JSON 경로와 오류 코드
8. `.env`에 저장하고 `.gitignore`로 제외

```dotenv
DATA_GO_KR_SERVICE_KEY=실제키는_여기에만
DATA_GO_KR_TRAFFIC_LIGHT_API_URL=Swagger에서_복사
DATA_GO_KR_CROSSWALK_API_URL=Swagger에서_복사
```

9. `.env.example`에는 값 없이 이름만 커밋

```dotenv
DATA_GO_KR_SERVICE_KEY=
DATA_GO_KR_TRAFFIC_LIGHT_API_URL=
DATA_GO_KR_CROSSWALK_API_URL=
TMAP_APP_KEY=
```

10. 1페이지 10건으로 먼저 호출하고 응답 구조를 fixture로 저장한다. fixture에는 키를 제거한다.
11. 전체 적재는 페이지를 끝까지 순회하고 총건수와 실제 적재건수를 비교한다.
12. 429/일일 한도 초과는 지수 backoff가 아니라 다음 허용 시점까지 중단한다. 인증·권한 오류는 자동 재시도하지 않는다.

## 9. 첫 CSV 점검 절차

가상환경:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install pandas pyarrow charset-normalizer
```

점검 코드 예:

```python
from pathlib import Path
import pandas as pd

path = Path("data/raw/crosswalk.csv")

for encoding in ("utf-8-sig", "cp949", "euc-kr"):
    try:
        df = pd.read_csv(path, encoding=encoding, dtype=str, keep_default_na=False)
        print("encoding:", encoding)
        break
    except UnicodeDecodeError:
        continue
else:
    raise RuntimeError("지원 인코딩으로 CSV를 읽지 못했습니다.")

print("rows:", len(df))
print("columns:", df.columns.tolist())

required = {"위도", "경도", "데이터기준일자"}
missing = required - set(df.columns)
if missing:
    raise RuntimeError(f"필수 열 없음: {sorted(missing)}")

lat = pd.to_numeric(df["위도"], errors="coerce")
lon = pd.to_numeric(df["경도"], errors="coerce")

print("invalid latitude:", (~lat.between(32, 39)).sum())
print("invalid longitude:", (~lon.between(124, 132)).sum())
print("blank reference date:", (df["데이터기준일자"].str.strip() == "").sum())
```

위 범위는 1차 방어선일 뿐이다. 도서 지역·좌표계·원천 정의에 대한 정밀 검증을 추가한다.

## 10. 광주 파일럿 데이터 추출

행정구역 명칭은 개편될 수 있으므로 문자열 하나만 하드코딩하지 않는다.

1. 원천 `시도명`의 고유값을 먼저 출력한다.
2. 현재 공식 명칭, 과거 명칭, 행정구역 alias를 별도 테이블로 관리한다.
3. 주소 텍스트와 위·경도 bounding polygon을 함께 검사한다.
4. 단순 문자열 필터 결과와 광주 행정경계 공간조인 결과의 차이를 검수한다.
5. 현장 검증 후보는 데이터 최신성, 음향신호기 속성, 접근 편의, 반복 시험 안전성을 기준으로 선택한다.

예시:

```python
print(sorted(df["시도명"].dropna().unique()))

pilot_names = {"광주광역시"}  # 실제 원천 고유값을 본 뒤 alias 갱신
pilot = df[df["시도명"].isin(pilot_names)].copy()
pilot.to_parquet("data/staging/gwangju-crossings.parquet", index=False)
```

## 11. 원천 등록부 템플릿

각 데이터셋을 이 형식으로 관리한다.

| 필드 | 예시 |
|---|---|
| source_id | `data-go-kr-15028201` |
| title | 전국횡단보도표준데이터 |
| landing_url | 공식 랜딩 URL |
| provider | 지방자치단체 |
| license | 페이지에서 확인한 정확한 표기 |
| retrieved_at | UTC ISO 8601 |
| reference_date | 레코드별 또는 파일 기준일 |
| sha256 | 다운로드 파일 해시 |
| schema_version | 내부 parser 버전 |
| row_count | 읽은 행 수 |
| quarantined_count | 격리 행 수 |
| notes | 좌표·결측·명칭 특이사항 |

## 12. 데이터 승인 체크리스트

- [ ] 공식 랜딩 URL과 제공기관을 기록했다.
- [ ] 이용허락범위와 재배포 조건을 확인했다.
- [ ] 원본 파일 해시가 있다.
- [ ] 원본을 수정하지 않았다.
- [ ] 문자 인코딩과 구분자를 기록했다.
- [ ] 스키마·필수 열·행 수를 검증했다.
- [ ] 위도/경도 순서와 좌표계를 확인했다.
- [ ] false/null/unknown을 구분했다.
- [ ] 중복·결측·오래된 기준일 보고서를 생성했다.
- [ ] 광주 필터를 명칭과 행정경계로 교차확인했다.
- [ ] 공간조인 모호 후보를 사람 검수 큐로 보냈다.
- [ ] 앱 발행 버전과 rollback 버전을 만들었다.
