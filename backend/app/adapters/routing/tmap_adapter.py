import asyncio
import logging
from typing import Any

import httpx

from app.adapters.routing.circuit_breaker import CircuitBreaker
from app.adapters.routing.fake_adapter import parse_tmap_geojson
from app.adapters.routing.port import NormalizedRoute, PedestrianRouter, RouteRequest
from app.core.config import (
    TMAP_APP_KEY,
    TMAP_BASE_URL,
    TMAP_CONNECT_TIMEOUT_SEC,
    TMAP_MAX_RETRIES,
    TMAP_READ_TIMEOUT_SEC,
)
from app.core.exceptions import AppException

logger = logging.getLogger(__name__)


class TmapPedestrianRouter(PedestrianRouter):
    """
    SK Open API TMAP 보행자 경로 안내를 호출하는 실제 어댑터입니다.
    - 계단 제외 옵션(searchOption=30) 매핑
    - Connect(3s) / Read(5s) 타임아웃
    - 서킷 브레이커(연속 5회 실패 시 30초 차단)
    - 5xx/타임아웃 시 최대 2회 지수 백오프 제한 재시도
    - 4xx 인증/파라미터/429 오류는 즉시 중단 (재시도 금지)
    """

    def __init__(
        self,
        app_key: str | None = None,
        base_url: str | None = None,
        circuit_breaker: CircuitBreaker | None = None,
        client: httpx.AsyncClient | None = None,
        max_retries: int | None = None,
    ):
        self.app_key = TMAP_APP_KEY if app_key is None else app_key
        self.base_url = TMAP_BASE_URL if base_url is None else base_url
        self.circuit_breaker = circuit_breaker or CircuitBreaker()
        self.client = client
        self.max_retries = max_retries if max_retries is not None else TMAP_MAX_RETRIES

    def _build_payload(self, request: RouteRequest) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "startX": request.origin.lon,
            "startY": request.origin.lat,
            "endX": request.destination.lon,
            "endY": request.destination.lat,
            "startName": request.origin_name,
            "endName": request.destination_name,
            "reqCoordType": "WGS84GEO",
            "resCoordType": "WGS84GEO",
            # searchOption: 30(최단거리+계단제외), 0(추천경로)
            "searchOption": "30" if request.exclude_stairs else "0",
        }
        if request.pass_points:
            payload["passList"] = "_".join(
                f"{p.lon},{p.lat}" for p in request.pass_points
            )
        return payload

    async def _send_request(
        self,
        client: httpx.AsyncClient,
        payload: dict[str, Any],
        headers: dict[str, str],
    ) -> dict[str, Any]:
        last_error: Exception | None = None
        timeout = httpx.Timeout(
            timeout=TMAP_READ_TIMEOUT_SEC,
            connect=TMAP_CONNECT_TIMEOUT_SEC,
        )

        for attempt in range(self.max_retries + 1):
            try:
                logger.debug(
                    "TMAP route request attempt %d/%d (exclude_stairs=%s)",
                    attempt + 1,
                    self.max_retries + 1,
                    payload.get("searchOption") == "30",
                )
                response = await client.post(
                    self.base_url,
                    json=payload,
                    headers=headers,
                    timeout=timeout,
                )

                # 200 OK
                if response.status_code == 200:
                    self.circuit_breaker.record_success()
                    return response.json()

                # 4xx 클라이언트/계약 오류는 재시도 없이 즉시 실패 처리
                if response.status_code in (401, 403):
                    logger.warning(
                        "TMAP authentication failed with status %d",
                        response.status_code,
                    )
                    raise AppException(
                        status_code=502,
                        code="PROVIDER_AUTH_ERROR",
                        message="TMAP 공급자 인증에 실패했습니다 (API 키 검증 오류).",
                        message_key="error.provider_auth",
                    )
                if response.status_code == 400:
                    logger.warning("TMAP bad request: %s", response.text)
                    raise AppException(
                        status_code=400,
                        code="PROVIDER_BAD_REQUEST",
                        message="TMAP 공급자 요청 파라미터가 올바르지 않습니다.",
                        message_key="error.provider_bad_request",
                    )
                if response.status_code == 429:
                    retry_after = response.headers.get("Retry-After", "60")
                    logger.warning(
                        "TMAP rate limit exceeded (Retry-After: %s)", retry_after
                    )
                    raise AppException(
                        status_code=429,
                        code="PROVIDER_RATE_LIMITED",
                        message="TMAP 공급자 호출 한도를 초과했습니다 (429 Too Many Requests).",
                        message_key="error.provider_rate_limited",
                        headers={"Retry-After": retry_after},
                    )
                if 400 <= response.status_code < 500:
                    logger.warning("TMAP client error %d", response.status_code)
                    raise AppException(
                        status_code=502,
                        code="PROVIDER_ERROR",
                        message=f"TMAP 공급자 요청 오류가 발생했습니다 ({response.status_code}).",
                        message_key="error.provider_error",
                    )

                # 5xx 서버 오류인 경우 재시도 대상
                logger.warning(
                    "TMAP returned server error %d on attempt %d",
                    response.status_code,
                    attempt + 1,
                )
                last_error = AppException(
                    status_code=502,
                    code="PROVIDER_UNAVAILABLE",
                    message=f"TMAP 공급자 서버 오류가 발생했습니다 ({response.status_code}).",
                    message_key="error.provider_unavailable",
                )

            except (httpx.TimeoutException, httpx.NetworkError) as exc:
                logger.warning(
                    "TMAP network/timeout error on attempt %d: %s", attempt + 1, exc
                )
                if isinstance(exc, httpx.TimeoutException):
                    last_error = AppException(
                        status_code=504,
                        code="PROVIDER_TIMEOUT",
                        message="TMAP 경로 조회 응답 시간을 초과했습니다.",
                        message_key="error.provider_timeout",
                    )
                else:
                    last_error = AppException(
                        status_code=502,
                        code="PROVIDER_UNAVAILABLE",
                        message="TMAP 경로 안내 서비스와 통신할 수 없습니다.",
                        message_key="error.provider_unavailable",
                    )

            # 지수 백오프 대기 후 재시도
            if attempt < self.max_retries:
                backoff_sec = 0.5 * (2**attempt)
                await asyncio.sleep(backoff_sec)

        # 모든 재시도 실패
        self.circuit_breaker.record_failure()
        if last_error:
            raise last_error
        raise AppException(
            status_code=502,
            code="PROVIDER_UNAVAILABLE",
            message="TMAP 공급자 호출에 실패했습니다.",
            message_key="error.provider_unavailable",
        )

    async def route(self, request: RouteRequest) -> NormalizedRoute:
        if not self.app_key:
            raise AppException(
                status_code=500,
                code="SERVER_CONFIG_ERROR",
                message="TMAP API 키(TMAP_APP_KEY)가 서버 환경변수에 구성되지 않았습니다.",
                message_key="error.server_config",
            )

        if not self.circuit_breaker.can_execute():
            raise AppException(
                status_code=503,
                code="CIRCUIT_BREAKER_OPEN",
                message="외부 경로 공급자 장애로 인해 서킷 브레이커가 동작 중입니다. 잠시 후 다시 시도해주세요.",
                message_key="error.circuit_breaker_open",
                headers={
                    "Retry-After": str(int(self.circuit_breaker.recovery_timeout_sec))
                },
            )

        payload = self._build_payload(request)
        headers = {
            "appKey": self.app_key,
            "Content-Type": "application/json",
            "Accept": "application/json",
        }

        if self.client:
            raw_data = await self._send_request(self.client, payload, headers)
        else:
            async with httpx.AsyncClient() as client:
                raw_data = await self._send_request(client, payload, headers)

        return parse_tmap_geojson(raw_data, exclude_stairs=request.exclude_stairs)
