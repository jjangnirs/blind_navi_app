from fastapi import FastAPI, HTTPException
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api.v1.crossings import router as crossings_router
from app.api.v1.routes import router as routes_router
from app.core.exceptions import (
    AppException,
    app_exception_handler,
    http_exception_handler,
    unhandled_exception_handler,
    validation_exception_handler,
)
from app.core.middleware import (
    CorrelationIdMiddleware,
    RateLimitMiddleware,
    RedactionLoggingMiddleware,
    TimeoutMiddleware,
)

app = FastAPI(
    title="Safe Cross KR API",
    version="0.1.0",
    description="시각장애인·저시력자 보행 보조 내비게이션 백엔드 API",
)

# 1. 미들웨어 등록 (FastAPI는 나중에 추가된 미들웨어가 먼저 실행됨)
# 실행 순서: CorrelationId -> RedactionLogging -> Timeout -> RateLimit -> Route Handler
app.add_middleware(RateLimitMiddleware)
app.add_middleware(TimeoutMiddleware)
app.add_middleware(RedactionLoggingMiddleware)
app.add_middleware(CorrelationIdMiddleware)

# 2. 공통 예외 핸들러 등록
app.add_exception_handler(AppException, app_exception_handler)
app.add_exception_handler(RequestValidationError, validation_exception_handler)
app.add_exception_handler(HTTPException, http_exception_handler)
app.add_exception_handler(StarletteHTTPException, http_exception_handler)
app.add_exception_handler(Exception, unhandled_exception_handler)


# 3. 라우터 등록
app.include_router(crossings_router)
app.include_router(routes_router)


@app.get("/health", tags=["system"])
async def health_check():
    return {"status": "ok", "version": "0.1.0"}
