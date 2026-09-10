import re
from typing import Any

# 전국 17개 광역 시·도 표준 명칭 및 별칭 사전 (DQ-008: 원천명 보존)
REGION_ALIASES: dict[str, str] = {
    "광주직할시": "광주광역시",
    "광주시": "광주광역시",
    "광주": "광주광역시",
    "전라남도 광주시": "광주광역시",
    "서울": "서울특별시",
    "서울시": "서울특별시",
    "부산": "부산광역시",
    "부산시": "부산광역시",
    "대구": "대구광역시",
    "대구시": "대구광역시",
    "인천": "인천광역시",
    "인천시": "인천광역시",
    "대전": "대전광역시",
    "대전시": "대전광역시",
    "울산": "울산광역시",
    "울산시": "울산광역시",
    "세종": "세종특별자치시",
    "세종시": "세종특별자치시",
    "경기": "경기도",
    "강원": "강원특별자치도",
    "강원도": "강원특별자치도",
    "충북": "충청북도",
    "충남": "충청남도",
    "전북": "전북특별자치도",
    "전북도": "전북특별자치도",
    "전남": "전라남도",
    "경북": "경상북도",
    "경남": "경상남도",
    "제주": "제주특별자치도",
    "제주도": "제주특별자치도",
}

# 광주광역시 5개 자치구 지리적 중심점 (WGS84 lat, lon)
GWANGJU_BOROUGH_CENTERS: dict[str, tuple[float, float]] = {
    "동구": (35.1460, 126.9230),
    "서구": (35.1520, 126.8600),
    "남구": (35.1150, 126.8900),
    "북구": (35.1950, 126.9100),
    "광산구": (35.1600, 126.7600),
}

GWANGJU_BOUNDS = {
    "min_lat": 35.05,
    "max_lat": 35.28,
    "min_lon": 126.65,
    "max_lon": 127.05,
}


def normalize_region_name(raw_region: str) -> tuple[str, str]:
    """
    행정구역 명칭을 정규화하되 원천명을 함께 보존합니다.
    Returns: (canonical_name, raw_name)
    """
    if not raw_region:
        return "", ""

    raw_clean = raw_region.strip()
    canonical = REGION_ALIASES.get(raw_clean, raw_clean)
    return canonical, raw_clean


def parse_address_sigungu(address: str | None) -> tuple[str | None, str | None]:
    """
    주소 문자열에서 (시도, 시군구)를 추출합니다.
    """
    if not address or not address.strip():
        return None, None

    clean = address.strip()
    # 예: "광주광역시 서구 상무대로 100", "광주시 북구 용봉동 300"
    match = re.search(
        r"([가-힣]+(?:특별시|광역시|특별자치시|특별자치도|도|시))\s+([가-힣]+(?:구|시|군))",
        clean,
    )
    if match:
        raw_sido, raw_sigungu = match.group(1), match.group(2)
        canonical_sido, _ = normalize_region_name(raw_sido)
        return canonical_sido, raw_sigungu

    # "광주 동구 금남로" 등 축약형
    match_short = re.search(
        r"^(광주|서울|부산|대구|인천|대전|울산|세종|경기)\s+([가-힣]+(?:구|군))",
        clean,
    )
    if match_short:
        raw_sido, raw_sigungu = match_short.group(1), match_short.group(2)
        canonical_sido, _ = normalize_region_name(raw_sido)
        return canonical_sido, raw_sigungu

    return None, None


def estimate_borough_from_coords(lat: float | None, lon: float | None) -> str | None:
    """
    좌표가 광주광역시 영역 내에 위치할 경우 가장 근접한 자치구(5개 구)를 추정합니다.
    """
    if lat is None or lon is None:
        return None

    # 광주 광역 바운딩 박스 검사
    if not (GWANGJU_BOUNDS["min_lat"] <= lat <= GWANGJU_BOUNDS["max_lat"]):
        return None
    if not (GWANGJU_BOUNDS["min_lon"] <= lon <= GWANGJU_BOUNDS["max_lon"]):
        return None

    best_borough = None
    min_dist_sq = float("inf")

    for borough, (c_lat, c_lon) in GWANGJU_BOROUGH_CENTERS.items():
        dist_sq = (lat - c_lat) ** 2 + (lon - c_lon) ** 2
        if dist_sq < min_dist_sq:
            min_dist_sq = dist_sq
            best_borough = borough

    return best_borough


def resolve_administrative_division(
    road_address: str | None = None,
    lot_address: str | None = None,
    lat: float | None = None,
    lon: float | None = None,
) -> dict[str, Any]:
    """
    1차 도로명주소, 2차 지번주소, 3차 좌표 공간 기반으로 관할 행정구역(시도, 시군구)을 체계적으로 식별합니다.
    """
    # 1. 도로명주소 분석
    sido, sigungu = parse_address_sigungu(road_address)
    if sido and sigungu:
        return {
            "sido": sido,
            "sigungu": sigungu,
            "method": "ROAD_ADDRESS",
            "is_estimated": False,
        }

    # 2. 지번주소 분석
    sido, sigungu = parse_address_sigungu(lot_address)
    if sido and sigungu:
        return {
            "sido": sido,
            "sigungu": sigungu,
            "method": "LOT_ADDRESS",
            "is_estimated": False,
        }

    # 3. 좌표 기반 광주광역시 자치구 추정 Fallback
    estimated_borough = estimate_borough_from_coords(lat, lon)
    if estimated_borough:
        return {
            "sido": "광주광역시",
            "sigungu": estimated_borough,
            "method": "COORDINATES_FALLBACK",
            "is_estimated": True,
        }

    return {
        "sido": sido,
        "sigungu": sigungu,
        "method": "UNKNOWN",
        "is_estimated": False,
    }
