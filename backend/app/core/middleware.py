import asyncio
import logging
import time
import urllib.parse
import uuid
from collections import defaultdict
from collections.abc import Callable

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from app.core.config import DEFAULT_TIMEOUT_SEC, RATE_LIMIT_PER_MINUTE
from app.core.exceptions import CommonErrorEnvelope, ErrorDetail

access_logger = logging.getLogger("safecross.access")
if not access_logger.handlers:
    handler = logging.StreamHandler()
    formatter = logging.Formatter("[%(levelname)s] %(asctime)s - %(name)s: %(message)s")
    handler.setFormatter(formatter)
    access_logger.addHandler(handler)
    access_logger.setLevel(logging.INFO)


class CorrelationIdMiddleware(BaseHTTPMiddleware):
    """
    모든 요청에 대해 X-Correlation-ID 헤더를 확인하고 없으면 신규 UUID를 발급하여
    request.state 및 응답 헤더에 주입합니다.
    """

    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        corr_id = request.headers.get("X-Correlation-ID") or str(uuid.uuid4())
        request.state.correlation_id = corr_id

        response = await call_next(request)
        response.headers["X-Correlation-ID"] = corr_id
        return response


REDACTED_PARAM_KEYS = {
    "lat",
    "lon",
    "path",
    "polyline",
    "coordinates",
    "startx",
    "starty",
    "endx",
    "endy",
    "origin",
    "destination",
}


def get_redacted_url(url: urllib.parse.SplitResult | str) -> str:
    """
    URL에서 lat, lon, path, coordinates 등 민감한 위치 좌표 파라미터의 값을 [REDACTED]로 치환합니다.
    """
    if isinstance(url, str):
        parsed = urllib.parse.urlsplit(url)
    else:
        parsed = url

    if not parsed.query:
        return parsed.path

    query_params = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
    redacted_params = []
    for k, v in query_params:
        if k.lower() in REDACTED_PARAM_KEYS:
            redacted_params.append((k, "[REDACTED]"))
        else:
            redacted_params.append((k, v))

    redacted_query = urllib.parse.unquote(urllib.parse.urlencode(redacted_params))
    return f"{parsed.path}?{redacted_query}"


class RedactionLoggingMiddleware(BaseHTTPMiddleware):
    """
    접근 로그에서 위치 좌표 원문(lat, lon, path 등)을 제거/마스킹하고
    correlation_id, method, redacted path, status code, latency만 기록합니다. (SR-NF-022, SR-NF-041)
    """

    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        start_time = time.perf_counter()
        corr_id = getattr(request.state, "correlation_id", "unknown")
        redacted_url = get_redacted_url(str(request.url))

        try:
            response = await call_next(request)
            duration_ms = (time.perf_counter() - start_time) * 1000.0
            access_logger.info(
                f"[{corr_id}] {request.method} {redacted_url} -> {response.status_code} ({duration_ms:.2f}ms)"
            )
            return response
        except Exception as exc:
            duration_ms = (time.perf_counter() - start_time) * 1000.0
            access_logger.error(
                f"[{corr_id}] {request.method} {redacted_url} -> ERROR: {exc} ({duration_ms:.2f}ms)"
            )
            raise


class TimeoutMiddleware(BaseHTTPMiddleware):
    """
    요청별 실행 타임아웃(기본 5.0초)을 적용하며, 시간 초과 시 504 Gateway Timeout을 반환합니다.
    """

    def __init__(self, app, timeout_sec: float = DEFAULT_TIMEOUT_SEC):
        super().__init__(app)
        self.timeout_sec = timeout_sec

    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        corr_id = getattr(request.state, "correlation_id", str(uuid.uuid4()))
        try:
            return await asyncio.wait_for(call_next(request), timeout=self.timeout_sec)
        except asyncio.TimeoutError:
            envelope = CommonErrorEnvelope(
                error=ErrorDetail(
                    code="TIMEOUT",
                    message="요청 처리 제한 시간을 초과했습니다.",
                    messageKey="error.timeout",
                    correlationId=corr_id,
                )
            )
            return JSONResponse(
                status_code=504,
                content=envelope.model_dump(mode="json"),
                headers={"X-Correlation-ID": corr_id},
            )


class InMemoryRateLimiter:
    """
    클라이언트 IP 기반 60초 슬라이딩 윈도우 처리율 제한기입니다.
    """

    def __init__(self, max_requests_per_minute: int = RATE_LIMIT_PER_MINUTE):
        self.max_requests = max_requests_per_minute
        self.window_seconds = 60.0
        self.requests: dict[str, list[float]] = defaultdict(list)

    def is_allowed(self, client_key: str) -> tuple[bool, int]:
        now = time.time()
        cutoff = now - self.window_seconds
        # 만료된 타임스탬프 정리
        timestamps = [t for t in self.requests[client_key] if t > cutoff]
        self.requests[client_key] = timestamps

        if len(timestamps) >= self.max_requests:
            retry_after = int(self.window_seconds - (now - timestamps[0])) + 1
            return False, max(1, retry_after)

        self.requests[client_key].append(now)
        return True, 0


class RateLimitMiddleware(BaseHTTPMiddleware):
    """
    클라이언트 IP 기반 요청 제한을 수행하고 초과 시 429 Too Many Requests를 반환합니다.
    """

    def __init__(self, app, limiter: InMemoryRateLimiter | None = None):
        super().__init__(app)
        self.limiter = limiter or InMemoryRateLimiter()

    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        # /health 등 헬스체크는 rate limit 제외
        if request.url.path == "/health":
            return await call_next(request)

        client_ip = request.client.host if request.client else "unknown"
        allowed, retry_after = self.limiter.is_allowed(client_ip)

        if not allowed:
            corr_id = getattr(request.state, "correlation_id", str(uuid.uuid4()))
            envelope = CommonErrorEnvelope(
                error=ErrorDetail(
                    code="RATE_LIMITED",
                    message="요청 허용량을 초과했습니다. 잠시 후 다시 시도해 주세요.",
                    messageKey="error.rate_limited",
                    correlationId=corr_id,
                    details=[{"retryAfterSeconds": retry_after}],
                )
            )
            return JSONResponse(
                status_code=429,
                content=envelope.model_dump(mode="json"),
                headers={
                    "X-Correlation-ID": corr_id,
                    "Retry-After": str(retry_after),
                },
            )

        return await call_next(request)
