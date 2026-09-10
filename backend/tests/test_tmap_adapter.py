import httpx
import pytest
from app.adapters.routing.circuit_breaker import CircuitBreaker, CircuitState
from app.adapters.routing.fake_adapter import parse_tmap_geojson
from app.adapters.routing.port import ROUTE_DISCLAIMER_TEXT, RouteRequest
from app.adapters.routing.tmap_adapter import TmapPedestrianRouter
from app.core.exceptions import AppException
from app.domain.schemas import LocationPoint


@pytest.fixture
def origin_point():
    return LocationPoint(lat=35.1595, lon=126.8526)


@pytest.fixture
def dest_point():
    return LocationPoint(lat=35.1610, lon=126.8550)


def test_tmap_stair_exclusion_payload_mapping(origin_point, dest_point):
    """exclude_stairs=True일 때 searchOption이 30, False일 때 0으로 매핑되는지 검증"""
    router = TmapPedestrianRouter(app_key="test-key")

    # 1. 계단 제외 True (기본)
    req_stairs_excluded = RouteRequest(
        origin=origin_point,
        destination=dest_point,
        exclude_stairs=True,
    )
    payload1 = router._build_payload(req_stairs_excluded)
    assert payload1["searchOption"] == "30"
    assert payload1["startX"] == 126.8526
    assert payload1["startY"] == 35.1595

    # 2. 계단 제외 False
    req_stairs_allowed = RouteRequest(
        origin=origin_point,
        destination=dest_point,
        exclude_stairs=False,
    )
    payload2 = router._build_payload(req_stairs_allowed)
    assert payload2["searchOption"] == "0"


def test_parse_tmap_geojson_includes_disclaimer_and_geometry():
    """TMAP GeoJSON 파싱 시 maneuvers, segments, disclaimer가 올바르게 구성되는지 검증"""
    sample_geojson = {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [126.85, 35.15]},
                "properties": {
                    "index": 0,
                    "pointIndex": 0,
                    "name": "출발지",
                    "description": "출발",
                    "totalDistance": 250,
                    "totalTime": 200,
                    "turnType": 200,
                },
            },
            {
                "type": "Feature",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[126.85, 35.15], [126.86, 35.16]],
                },
                "properties": {
                    "index": 1,
                    "name": "보행로",
                    "distance": 250,
                    "time": 200,
                },
            },
            {
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [126.86, 35.16]},
                "properties": {
                    "index": 2,
                    "pointIndex": 1,
                    "name": "도착지",
                    "description": "도착",
                    "turnType": 201,
                },
            },
        ],
    }

    normalized = parse_tmap_geojson(sample_geojson, exclude_stairs=True)
    assert normalized.provider == "TMAP"
    assert normalized.total_distance_m == 250
    assert normalized.total_duration_sec == 200
    assert normalized.exclude_stairs is True
    assert len(normalized.maneuvers) == 2
    assert len(normalized.segments) == 1
    assert len(normalized.full_geometry) == 2
    assert "안전 경로가 아닙니다" in normalized.disclaimer
    assert normalized.disclaimer == ROUTE_DISCLAIMER_TEXT


import asyncio


def test_tmap_adapter_missing_api_key(origin_point, dest_point):
    """API 키 미설정 시 SERVER_CONFIG_ERROR 발생 검증"""

    async def _run():
        router = TmapPedestrianRouter(app_key="")
        req = RouteRequest(origin=origin_point, destination=dest_point)
        with pytest.raises(AppException) as exc_info:
            await router.route(req)
        assert exc_info.value.code == "SERVER_CONFIG_ERROR"
        assert exc_info.value.status_code == 500

    asyncio.run(_run())


def test_tmap_adapter_auth_error_no_retry(origin_point, dest_point):
    """401 인증 실패 시 재시도 없이 즉시 PROVIDER_AUTH_ERROR 예외 발생 검증"""

    async def _run():
        call_count = 0

        def mock_handler(request: httpx.Request):
            nonlocal call_count
            call_count += 1
            return httpx.Response(401, json={"error": "Unauthorized"})

        transport = httpx.MockTransport(mock_handler)
        async with httpx.AsyncClient(transport=transport) as client:
            router = TmapPedestrianRouter(
                app_key="invalid-key",
                client=client,
                max_retries=2,
            )
            req = RouteRequest(origin=origin_point, destination=dest_point)
            with pytest.raises(AppException) as exc_info:
                await router.route(req)

            assert exc_info.value.code == "PROVIDER_AUTH_ERROR"
            assert exc_info.value.status_code == 502
            assert call_count == 1  # 401은 재시도하지 않아야 함

    asyncio.run(_run())


def test_tmap_adapter_rate_limited_no_retry(origin_point, dest_point):
    """429 레이트 리밋 시 재시도 없이 즉시 PROVIDER_RATE_LIMITED 및 Retry-After 전파 검증"""

    async def _run():
        call_count = 0

        def mock_handler(request: httpx.Request):
            nonlocal call_count
            call_count += 1
            return httpx.Response(
                429,
                headers={"Retry-After": "45"},
                json={"error": "Too Many Requests"},
            )

        transport = httpx.MockTransport(mock_handler)
        async with httpx.AsyncClient(transport=transport) as client:
            router = TmapPedestrianRouter(
                app_key="test-key",
                client=client,
                max_retries=2,
            )
            req = RouteRequest(origin=origin_point, destination=dest_point)
            with pytest.raises(AppException) as exc_info:
                await router.route(req)

            assert exc_info.value.code == "PROVIDER_RATE_LIMITED"
            assert exc_info.value.status_code == 429
            assert exc_info.value.headers.get("Retry-After") == "45"
            assert call_count == 1  # 429는 재시도하지 않아야 함

    asyncio.run(_run())


def test_tmap_adapter_server_error_retry_and_circuit_breaker(origin_point, dest_point):
    """500 서버 오류 시 최대 2회 재시도(총 3회 호출) 후 실패 및 서킷 브레이커 누적 검증"""

    async def _run():
        call_count = 0

        def mock_handler(request: httpx.Request):
            nonlocal call_count
            call_count += 1
            return httpx.Response(500, json={"error": "Internal Server Error"})

        transport = httpx.MockTransport(mock_handler)
        cb = CircuitBreaker(failure_threshold=2, recovery_timeout_sec=30.0)

        async with httpx.AsyncClient(transport=transport) as client:
            router = TmapPedestrianRouter(
                app_key="test-key",
                client=client,
                circuit_breaker=cb,
                max_retries=2,
            )
            req = RouteRequest(origin=origin_point, destination=dest_point)

            # 1회차 라우팅 호출 (내부에서 3번 시도 후 실패)
            with pytest.raises(AppException) as exc_info:
                await router.route(req)
            assert exc_info.value.code == "PROVIDER_UNAVAILABLE"
            assert exc_info.value.status_code == 502
            assert call_count == 3
            assert cb.failure_count == 1
            assert cb.state == CircuitState.CLOSED

            # 2회차 라우팅 호출 -> threshold=2 도달하여 서킷 OPEN
            with pytest.raises(AppException):
                await router.route(req)
            assert cb.failure_count == 2
            assert cb.state == CircuitState.OPEN

            # 3회차 라우팅 호출 -> 서킷 브레이커로 인해 외부 요청 없이 503 즉시 차단
            with pytest.raises(AppException) as exc_info3:
                await router.route(req)
            assert exc_info3.value.code == "CIRCUIT_BREAKER_OPEN"
            assert exc_info3.value.status_code == 503
            assert exc_info3.value.headers.get("Retry-After") == "30"

    asyncio.run(_run())
