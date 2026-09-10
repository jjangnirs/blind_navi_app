import math
from typing import Any

from safecross_pipeline.spatial.features import (
    calculate_bearing_deg,
    calculate_distance_m,
)


def calculate_linestring_bearing(coords: list[list[float]]) -> float:
    """
    LineString의 좌표 리스트 [[lon1, lat1], ..., [lonN, latN]]에서
    시작 노드에서 끝 노드로 향하는 기본 방위각(0 ~ 360°)을 계산합니다.
    """
    if len(coords) < 2:
        return 0.0

    lon1, lat1 = coords[0][0], coords[0][1]
    lon2, lat2 = coords[-1][0], coords[-1][1]
    return calculate_bearing_deg(lat1, lon1, lat2, lon2)


def distance_to_linestring_m(
    crossing_lat: float,
    crossing_lon: float,
    coords: list[list[float]],
) -> float:
    """
    횡단보도 지점에서 LineString 형상까지의 최단 거리(m)를 계산합니다.
    각 선분에 수선을 내려 정사영 거리를 구하고 선분 범위 밖이면 끝점 거리를 취합니다.
    """
    if not coords:
        return float("inf")
    if len(coords) == 1:
        return calculate_distance_m(
            crossing_lat, crossing_lon, coords[0][1], coords[0][0]
        )

    min_dist = float("inf")
    lat_rad = math.radians(crossing_lat)
    kx = 111320.0 * math.cos(lat_rad)
    ky = 110574.0

    for i in range(len(coords) - 1):
        lon1, lat1 = coords[i][0], coords[i][1]
        lon2, lat2 = coords[i + 1][0], coords[i + 1][1]

        x0, y0 = (crossing_lon - lon1) * kx, (crossing_lat - lat1) * ky
        dx, dy = (lon2 - lon1) * kx, (lat2 - lat1) * ky

        seg_len_sq = dx * dx + dy * dy
        if seg_len_sq == 0.0:
            dist = math.hypot(x0, y0)
        else:
            t = max(0.0, min(1.0, (x0 * dx + y0 * dy) / seg_len_sq))
            proj_x = t * dx
            proj_y = t * dy
            dist = math.hypot(x0 - proj_x, y0 - proj_y)

        min_dist = min(min_dist, dist)

    return round(min_dist, 2)


def match_osm_crossing_bearing(
    crossing_lat: float,
    crossing_lon: float,
    osm_features: list[dict[str, Any]],
    max_snap_dist_m: float = 15.0,
) -> tuple[float | None, float | None]:
    """
    주어진 횡단보도 좌표에 대해 반경 max_snap_dist_m 내의 가장 인접한 OSM 횡단보도 선형을 스냅하여
    (정밀 방위각, 스냅 거리)를 반환합니다.
    매칭되는 선형이 없으면 (None, None)을 반환합니다.
    """
    best_bearing: float | None = None
    min_dist_m = float("inf")

    for feat in osm_features:
        geom = feat.get("geometry", {})
        if geom.get("type") != "LineString":
            continue

        coords = geom.get("coordinates", [])
        if len(coords) < 2:
            continue

        dist_m = distance_to_linestring_m(crossing_lat, crossing_lon, coords)

        if dist_m <= max_snap_dist_m and dist_m < min_dist_m:
            min_dist_m = dist_m
            best_bearing = calculate_linestring_bearing(coords)

    if best_bearing is not None:
        return best_bearing, min_dist_m

    return None, None


def enrich_crossings_with_osm(
    crossings: list[Any],
    osm_features: list[dict[str, Any]],
    max_snap_dist_m: float = 15.0,
) -> list[Any]:
    """
    공공데이터 횡단보도 목록 중 direction_bearing_deg가 누락되었거나 보정이 필요한 경우
    OSM 형상을 스냅하여 정밀 방위각을 주입합니다.
    """
    if not osm_features:
        return crossings

    enriched_crossings = []
    for c in crossings:
        # 이미 방향이 지정되어 있지 않은 경우에만 OSM 형상으로부터 유도
        if c.direction_bearing_deg is None:
            osm_bearing, _ = match_osm_crossing_bearing(
                crossing_lat=c.lat,
                crossing_lon=c.lon,
                osm_features=osm_features,
                max_snap_dist_m=max_snap_dist_m,
            )
            if osm_bearing is not None:
                c.direction_bearing_deg = osm_bearing
        enriched_crossings.append(c)

    return enriched_crossings
