"""Geographical regression fixtures for spatial join.

3대 지리 시나리오:
1. 교차로 네 모서리 (4-Corner Intersection): 동서남북 횡단보도와 각 모서리 신호기 방향 일치 검증
2. 평행 도로 (Parallel Road): 거리가 8m로 매우 가깝지만 도로명이 다른 경우 오확정 방지 검증
3. 중앙 교통섬 (Traffic Island): 교통섬을 경계로 분할된 횡단보도와 신호기 연계 검증
"""

from safecross_pipeline.spatial.spatial_join import CrossingRecord, SignalRecord

# 기준 좌표: 광주광역시 상무지구 교차로 부근 (lat=35.1500, lon=126.8500)
BASE_LAT = 35.150000
BASE_LON = 126.850000


def get_four_corner_intersection_scenario() -> tuple[
    list[CrossingRecord], list[SignalRecord]
]:
    """
    4-모서리 교차로 시나리오:
    - 북측 횡단보도 (East-West 횡단, bearing: 90° & 270°)
    - 남측 횡단보도 (East-West 횡단, bearing: 90° & 270°)
    - 동측 횡단보도 (North-South 횡단, bearing: 0° & 180°)
    - 서측 횡단보도 (North-South 횡단, bearing: 0° & 180°)
    - 북서(NW), 북동(NE), 남서(SW), 남동(SE) 4개 코너의 신호기들
    """
    crossings = [
        # 북측 횡단보도 (중심점: 교차로 북쪽 15m)
        CrossingRecord(
            id="cw-north-01",
            source_record_key="CW-CORNER-N",
            lat=BASE_LAT + 0.00015,
            lon=BASE_LON,
            road_name="상무중앙로",
            direction_bearing_deg=90.0,
        ),
        # 남측 횡단보도 (중심점: 교차로 남쪽 15m)
        CrossingRecord(
            id="cw-south-01",
            source_record_key="CW-CORNER-S",
            lat=BASE_LAT - 0.00015,
            lon=BASE_LON,
            road_name="상무중앙로",
            direction_bearing_deg=90.0,
        ),
        # 동측 횡단보도 (중심점: 교차로 동쪽 15m)
        CrossingRecord(
            id="cw-east-01",
            source_record_key="CW-CORNER-E",
            lat=BASE_LAT,
            lon=BASE_LON + 0.00015,
            road_name="상무평화로",
            direction_bearing_deg=0.0,
        ),
        # 서측 횡단보도 (중심점: 교차로 서쪽 15m)
        CrossingRecord(
            id="cw-west-01",
            source_record_key="CW-CORNER-W",
            lat=BASE_LAT,
            lon=BASE_LON - 0.00015,
            road_name="상무평화로",
            direction_bearing_deg=0.0,
        ),
    ]

    signals = [
        # 북동(NE) 코너 신호기 (북측 횡단보도 동쪽 끝에 위치, 90도 횡단 목표 신호)
        SignalRecord(
            id="tl-ne-01",
            source_record_key="TL-CORNER-NE",
            lat=BASE_LAT + 0.00015,
            lon=BASE_LON + 0.00012,
            road_name="상무중앙로",
            signal_type="보행등",
        ),
        # 북서(NW) 코너 신호기 (북측 횡단보도 서쪽 끝에 위치, 270도 횡단 목표 신호)
        SignalRecord(
            id="tl-nw-01",
            source_record_key="TL-CORNER-NW",
            lat=BASE_LAT + 0.00015,
            lon=BASE_LON - 0.00012,
            road_name="상무중앙로",
            signal_type="보행등",
        ),
        # 동측 북단 신호기 (동측 횡단보도 북쪽 끝에 위치, 0도 횡단 목표 신호)
        SignalRecord(
            id="tl-en-01",
            source_record_key="TL-CORNER-EN",
            lat=BASE_LAT + 0.00012,
            lon=BASE_LON + 0.00015,
            road_name="상무평화로",
            signal_type="보행등",
        ),
    ]

    return crossings, signals


def get_parallel_road_scenario() -> tuple[list[CrossingRecord], list[SignalRecord]]:
    """
    평행 도로 시나리오:
    - 주간선도로: '상무대로'
    - 평행이면도로: '상무대로8번길' (본선과 8m 이격되어 평행함)
    - 이면도로의 횡단보도에서 8m 떨어진 본선 신호등이 감지되지만, 도로명이 불일치하므로 오확정 금지
    """
    crossings = [
        CrossingRecord(
            id="cw-parallel-01",
            source_record_key="CW-PARALLEL-LOCAL",
            lat=BASE_LAT,
            lon=BASE_LON,
            road_name="상무대로8번길",
            direction_bearing_deg=0.0,
        ),
    ]

    signals = [
        # 본선 도로 신호등 (단 8미터 떨어져 있음: lat 차이 약 0.00007)
        SignalRecord(
            id="tl-parallel-main",
            source_record_key="TL-PARALLEL-MAIN",
            lat=BASE_LAT + 0.00007,
            lon=BASE_LON,
            road_name="상무대로",  # 도로명 불일치!
            signal_type="보행등",
        ),
    ]

    return crossings, signals


def get_traffic_island_scenario() -> tuple[list[CrossingRecord], list[SignalRecord]]:
    """
    중앙 교통섬 시나리오:
    - 1개 큰 교차로 횡단이 중앙 교통섬을 사이에 두고 2개 구간(북측 구간, 남측 구간)으로 나뉨
    - 북측 횡단보도 (인도 <-> 교통섬)
    - 남측 횡단보도 (교통섬 <-> 반대편 인도)
    - 각 횡단보도에 인접한 신호기들이 올바르게 1:1 대응해야 함
    """
    crossings = [
        # 북측 횡단구간 (인도 -> 교통섬, 남쪽 180도 방향)
        CrossingRecord(
            id="cw-island-north",
            source_record_key="CW-ISLAND-N",
            lat=BASE_LAT + 0.00020,
            lon=BASE_LON,
            road_name="빛고을대로",
            direction_bearing_deg=180.0,
        ),
        # 남측 횡단구간 (교통섬 -> 인도, 남쪽 180도 방향)
        CrossingRecord(
            id="cw-island-south",
            source_record_key="CW-ISLAND-S",
            lat=BASE_LAT - 0.00020,
            lon=BASE_LON,
            road_name="빛고을대로",
            direction_bearing_deg=180.0,
        ),
    ]

    signals = [
        # 중앙 교통섬 내부 신호기 (교통섬 중심에 위치)
        SignalRecord(
            id="tl-island-center",
            source_record_key="TL-ISLAND-CTR",
            lat=BASE_LAT,
            lon=BASE_LON,
            road_name="빛고을대로",
            signal_type="보행등",
        ),
        # 북측 인도 신호기
        SignalRecord(
            id="tl-island-north-pavement",
            source_record_key="TL-ISLAND-NP",
            lat=BASE_LAT + 0.00030,
            lon=BASE_LON,
            road_name="빛고을대로",
            signal_type="보행등",
        ),
    ]

    return crossings, signals
