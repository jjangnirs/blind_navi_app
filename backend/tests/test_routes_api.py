import io
import logging

import pytest
from app.adapters.routing.factory import set_pedestrian_router
from app.adapters.routing.fake_adapter import FakePedestrianRouter, FakeRouterMode
from app.main import app
from fastapi.testclient import TestClient


@pytest.fixture(autouse=True)
def reset_router():
    """각 테스트마다 FakePedestrianRouter를 기본 NORMAL 모드로 초기화"""
    fake = FakePedestrianRouter(mode=FakeRouterMode.NORMAL)
    set_pedestrian_router(fake)
    yield fake
    set_pedestrian_router(None)


@pytest.fixture
def client():
    return TestClient(app)


def test_pedestrian_route_success(client: TestClient):
    """POST /v1/routes/pedestrian 기본 정상 응답 및 면책 고지문 검증"""
    payload = {
        "origin": {"lat": 35.1595, "lon": 126.8526},
        "destination": {"lat": 35.1610, "lon": 126.8550},
        "originName": "광주광역시청",
        "destinationName": "평화공원",
        "excludeStairs": True,
    }
    response = client.post("/v1/routes/pedestrian", json=payload)
    assert response.status_code == 200

    data = response.json()
    assert "dataVersion" in data
    assert data["provider"] == "TMAP"
    assert data["totalDistanceMeters"] > 0
    assert data["totalDurationSeconds"] > 0
    assert data["excludeStairs"] is True
    assert len(data["fullGeometry"]) >= 2
    assert len(data["maneuvers"]) >= 1
    assert len(data["segments"]) >= 1

    # disclaimer 필수 검증
    disclaimer = data["disclaimer"]
    assert "TMAP 보행자 경로 안내" in disclaimer
    assert "안전 경로가 아닙니다" in disclaimer
    assert "음향신호기" in disclaimer

    # 비밀키 노출 여부 검증
    assert "appKey" not in response.text
    assert "APP_KEY" not in response.text


def test_pedestrian_route_stair_exclusion_flag(client: TestClient):
    """excludeStairs 옵션이 정상 반영되는지 검증"""
    payload = {
        "origin": {"lat": 35.1595, "lon": 126.8526},
        "destination": {"lat": 35.1610, "lon": 126.8550},
        "excludeStairs": False,
    }
    response = client.post("/v1/routes/pedestrian", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["excludeStairs"] is False


def test_pedestrian_route_out_of_bounds_validation(client: TestClient):
    """대한민국 지리적 범위를 벗어난 좌표는 400 검증 오류를 반환해야 함"""
    payload = {
        "origin": {"lat": 0.0, "lon": 0.0},  # 범위 밖
        "destination": {"lat": 35.1610, "lon": 126.8550},
    }
    response = client.post("/v1/routes/pedestrian", json=payload)
    assert response.status_code == 400
    data = response.json()
    assert data["error"]["code"] == "VALIDATION_ERROR"


def test_pedestrian_route_auth_error_simulation(
    client: TestClient, reset_router: FakePedestrianRouter
):
    """401 인증 오류 시 502 PROVIDER_AUTH_ERROR 변환 검증"""
    reset_router.set_mode(FakeRouterMode.AUTH_ERROR_401)
    payload = {
        "origin": {"lat": 35.1595, "lon": 126.8526},
        "destination": {"lat": 35.1610, "lon": 126.8550},
    }
    response = client.post("/v1/routes/pedestrian", json=payload)
    assert response.status_code == 502
    data = response.json()
    assert data["error"]["code"] == "PROVIDER_AUTH_ERROR"


def test_pedestrian_route_rate_limited_simulation(
    client: TestClient, reset_router: FakePedestrianRouter
):
    """429 공급자 호출한도 초과 시 429 및 Retry-After 헤더 검증"""
    reset_router.set_mode(FakeRouterMode.RATE_LIMITED_429)
    payload = {
        "origin": {"lat": 35.1595, "lon": 126.8526},
        "destination": {"lat": 35.1610, "lon": 126.8550},
    }
    response = client.post("/v1/routes/pedestrian", json=payload)
    assert response.status_code == 429
    data = response.json()
    assert data["error"]["code"] == "PROVIDER_RATE_LIMITED"
    assert response.headers.get("Retry-After") == "60"


def test_pedestrian_route_server_error_simulation(
    client: TestClient, reset_router: FakePedestrianRouter
):
    """500 공급자 서버 오류 시 502 PROVIDER_UNAVAILABLE 변환 검증"""
    reset_router.set_mode(FakeRouterMode.SERVER_ERROR_500)
    payload = {
        "origin": {"lat": 35.1595, "lon": 126.8526},
        "destination": {"lat": 35.1610, "lon": 126.8550},
    }
    response = client.post("/v1/routes/pedestrian", json=payload)
    assert response.status_code == 502
    data = response.json()
    assert data["error"]["code"] == "PROVIDER_UNAVAILABLE"


def test_pedestrian_route_timeout_simulation(
    client: TestClient, reset_router: FakePedestrianRouter
):
    """공급자 타임아웃 시 504 PROVIDER_TIMEOUT 변환 검증"""
    reset_router.set_mode(FakeRouterMode.TIMEOUT)
    payload = {
        "origin": {"lat": 35.1595, "lon": 126.8526},
        "destination": {"lat": 35.1610, "lon": 126.8550},
    }
    response = client.post("/v1/routes/pedestrian", json=payload)
    assert response.status_code == 504
    data = response.json()
    assert data["error"]["code"] == "PROVIDER_TIMEOUT"


def test_access_log_does_not_leak_route_coordinates(client: TestClient):
    """엑세스 로그에 요청 바디의 좌표 원문이 남지 않는지 검증"""
    log_capture = io.StringIO()
    handler = logging.StreamHandler(log_capture)
    access_logger = logging.getLogger("safecross.access")
    access_logger.addHandler(handler)

    try:
        payload = {
            "origin": {"lat": 35.1595123, "lon": 126.8526456},
            "destination": {"lat": 35.1610789, "lon": 126.8550123},
        }
        client.post("/v1/routes/pedestrian", json=payload)

        logged_content = log_capture.getvalue()
        # 세부 소수점 좌표가 엑세스 로그에 찍히지 않아야 함
        assert "35.1595123" not in logged_content
        assert "126.8526456" not in logged_content
        assert "35.1610789" not in logged_content
        assert "126.8550123" not in logged_content
    finally:
        access_logger.removeHandler(handler)
