import math
import re
from typing import Any


def calculate_distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """
    WGS84 두 좌표 간의 구면 대권 거리(meter)를 계산합니다 (Haversine 공식).
    """
    R = 6371000.0  # 지구 반경 (미터)
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)

    a = (
        math.sin(delta_phi / 2.0) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    )
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))
    return round(R * c, 2)


def calculate_bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """
    (lat1, lon1)에서 (lat2, lon2)로 향하는 초기 방위각(0 ~ 360°)을 계산합니다.
    0°: 북, 90°: 동, 180°: 남, 270°: 서
    """
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_lambda = math.radians(lon2 - lon1)

    y = math.sin(delta_lambda) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(
        delta_lambda
    )

    bearing = math.degrees(math.atan2(y, x))
    return round((bearing + 360.0) % 360.0, 1)


def calculate_bearing_diff(bearing1: float, bearing2: float) -> float:
    """
    두 방위각 사이의 최소 각도 차이(0 ~ 180°)를 계산합니다.
    """
    diff = abs(bearing1 - bearing2) % 360.0
    return round(360.0 - diff if diff > 180.0 else diff, 1)


def compute_road_match(road1: str | None, road2: str | None) -> str:
    """
    도로명 유사도를 분석합니다.
    반환값: 'EXACT' | 'PARTIAL' | 'MISMATCH' | 'UNKNOWN'
    """
    if not road1 or not road2:
        return "UNKNOWN"

    r1 = road1.strip()
    r2 = road2.strip()

    if r1 == r2:
        return "EXACT"

    # 도로명 주소에서 핵심 도로명 추출 (예: '상무대로 100' -> '상무대로')
    token1 = re.findall(r"[가-힣0-9]+(?:로|길|대로|번길)", r1)
    token2 = re.findall(r"[가-힣0-9]+(?:로|길|대로|번길)", r2)

    if token1 and token2:
        set1 = set(token1)
        set2 = set(token2)
        if set1 == set2:
            return "EXACT"
        if set1.intersection(set2):
            return "PARTIAL"
        return "MISMATCH"

    # 공통 단어 확인
    words1 = set(r1.split())
    words2 = set(r2.split())
    if words1.intersection(words2):
        return "PARTIAL"

    return "MISMATCH"


def compute_id_similarity(id1: str | None, id2: str | None) -> float:
    """
    원천 관리번호의 공통 접두어 및 유사도를 계산합니다 (0.0 ~ 1.0).
    """
    if not id1 or not id2:
        return 0.0

    s1, s2 = id1.strip(), id2.strip()
    if s1 == s2:
        return 1.0

    # 동일 지자체/지역 접두어 (예: 'CW-GJ-', 'TL-GJ-')
    prefix1 = s1.split("-")[:2]
    prefix2 = s2.split("-")[:2]
    if len(prefix1) >= 2 and len(prefix2) >= 2 and prefix1[1] == prefix2[1]:
        return 0.5

    return 0.0


def compute_match_features(
    crossing_lat: float,
    crossing_lon: float,
    signal_lat: float,
    signal_lon: float,
    approach_bearing_deg: float,
    crossing_road: str | None = None,
    signal_road: str | None = None,
    crossing_key: str | None = None,
    signal_key: str | None = None,
) -> dict[str, Any]:
    """
    공간 조인의 근거(evidence)가 되는 다차원 feature를 계산하여 딕셔너리로 반환합니다.
    """
    dist_m = calculate_distance_m(crossing_lat, crossing_lon, signal_lat, signal_lon)

    # 횡단보도에서 신호기로 향하는 상대 방위각
    relative_bearing = calculate_bearing_deg(
        crossing_lat, crossing_lon, signal_lat, signal_lon
    )

    # 횡단 진행 방향(approach_bearing)과 신호기 상대 위치 방위각의 차이
    bearing_diff = calculate_bearing_diff(approach_bearing_deg, relative_bearing)

    # 거리 구간 티어링 (10m, 20m, 30m, >30m)
    if dist_m <= 10.0:
        distance_tier = "10m"
    elif dist_m <= 20.0:
        distance_tier = "20m"
    elif dist_m <= 30.0:
        distance_tier = "30m"
    else:
        distance_tier = ">30m"

    road_match = compute_road_match(crossing_road, signal_road)
    id_sim = compute_id_similarity(crossing_key, signal_key)

    return {
        "distance_m": dist_m,
        "distance_tier": distance_tier,
        "approach_bearing_deg": approach_bearing_deg,
        "relative_signal_bearing_deg": relative_bearing,
        "bearing_diff_deg": bearing_diff,
        "road_match": road_match,
        "id_similarity": id_sim,
    }
