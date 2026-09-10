import json
import os

from app.main import app

SNAPSHOT_PATH = os.path.join(os.path.dirname(__file__), "openapi_snapshot.json")


def test_01_openapi_schema_contains_required_paths():
    """
    수용 기준: OpenAPI 스키마에 모바일 주변 시설 및 경로 회랑 API 경로가 온전히 노출되는지 검증
    """
    schema = app.openapi()
    paths = schema.get("paths", {})

    assert "/v1/crossings/nearby" in paths, (
        "GET /v1/crossings/nearby 경로가 OpenAPI에 있어야 합니다."
    )
    assert "get" in paths["/v1/crossings/nearby"]

    assert "/v1/crossings/corridor" in paths, (
        "/v1/crossings/corridor 경로가 OpenAPI에 있어야 합니다."
    )
    assert "get" in paths["/v1/crossings/corridor"]
    assert "post" in paths["/v1/crossings/corridor"]

    assert "/v1/routes/pedestrian" in paths, (
        "POST /v1/routes/pedestrian 경로가 OpenAPI에 있어야 합니다."
    )
    assert "post" in paths["/v1/routes/pedestrian"]

    # 파라미터 검증
    nearby_params = {
        p["name"]: p for p in paths["/v1/crossings/nearby"]["get"]["parameters"]
    }
    assert "lat" in nearby_params
    assert "lon" in nearby_params
    assert "radiusM" in nearby_params
    assert "limit" in nearby_params

    corridor_get_params = {
        p["name"]: p for p in paths["/v1/crossings/corridor"]["get"]["parameters"]
    }
    assert "path" in corridor_get_params
    assert "bufferM" in corridor_get_params
    assert "limit" in corridor_get_params

    # 컴포넌트 스키마 검증
    schemas = schema.get("components", {}).get("schemas", {})
    assert "CrossingItemResponse" in schemas
    assert "CrossingsListResponse" in schemas
    assert "CorridorRequest" in schemas
    assert "CommonErrorEnvelope" in schemas
    assert "PedestrianRouteRequest" in schemas
    assert "PedestrianRouteResponse" in schemas


def test_02_openapi_schema_snapshot_match():
    """
    수용 기준: OpenAPI schema snapshot과 contract test 검증
    API 명세의 임의 변경이나 회귀(Regression)를 방지하는 스냅샷 일치 검증
    """
    current_schema = app.openapi()

    # 스냅샷 파일이 없으면 초기 생성
    if not os.path.exists(SNAPSHOT_PATH):
        with open(SNAPSHOT_PATH, "w", encoding="utf-8") as f:
            json.dump(current_schema, f, ensure_ascii=False, indent=2)

    with open(SNAPSHOT_PATH, "r", encoding="utf-8") as f:
        snapshot_schema = json.load(f)

    # 주요 계약 비교 (paths 및 components)
    assert current_schema["paths"] == snapshot_schema["paths"], (
        "OpenAPI paths 명세가 스냅샷과 일치하지 않습니다."
    )
    assert current_schema["components"] == snapshot_schema["components"], (
        "OpenAPI components 명세가 스냅샷과 일치하지 않습니다."
    )
