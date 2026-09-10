from typing import Any

from fastapi import HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel


class ErrorDetail(BaseModel):
    code: str
    message: str
    messageKey: str
    correlationId: str | None = None
    details: list[dict[str, Any]] | None = None


class CommonErrorEnvelope(BaseModel):
    error: ErrorDetail


class AppException(HTTPException):
    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        message_key: str,
        details: list[dict[str, Any]] | None = None,
        headers: dict[str, str] | None = None,
    ):
        super().__init__(status_code=status_code, detail=message, headers=headers)
        self.code = code
        self.message = message
        self.message_key = message_key
        self.details = details


def get_correlation_id(request: Request) -> str | None:
    return getattr(request.state, "correlation_id", None) or request.headers.get(
        "X-Correlation-ID"
    )


async def app_exception_handler(request: Request, exc: AppException) -> JSONResponse:
    envelope = CommonErrorEnvelope(
        error=ErrorDetail(
            code=exc.code,
            message=exc.message,
            messageKey=exc.message_key,
            correlationId=get_correlation_id(request),
            details=exc.details,
        )
    )
    headers = exc.headers or {}
    correlation_id = get_correlation_id(request)
    if correlation_id:
        headers["X-Correlation-ID"] = correlation_id

    return JSONResponse(
        status_code=exc.status_code,
        content=envelope.model_dump(mode="json"),
        headers=headers,
    )


from starlette.exceptions import HTTPException as StarletteHTTPException


async def http_exception_handler(
    request: Request, exc: HTTPException | StarletteHTTPException
) -> JSONResponse:
    code_map = {
        400: ("BAD_REQUEST", "error.bad_request"),
        404: ("NOT_FOUND", "error.not_found"),
        429: ("RATE_LIMITED", "error.rate_limited"),
        504: ("TIMEOUT", "error.timeout"),
    }
    code, msg_key = code_map.get(
        exc.status_code, ("INTERNAL_SERVER_ERROR", "error.server_error")
    )

    envelope = CommonErrorEnvelope(
        error=ErrorDetail(
            code=code,
            message=str(exc.detail),
            messageKey=msg_key,
            correlationId=get_correlation_id(request),
        )
    )
    headers = getattr(exc, "headers", None) or {}
    correlation_id = get_correlation_id(request)
    if correlation_id:
        headers["X-Correlation-ID"] = correlation_id

    return JSONResponse(
        status_code=exc.status_code,
        content=envelope.model_dump(mode="json"),
        headers=headers,
    )


async def validation_exception_handler(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    details = []
    first_msg = ""
    for err in exc.errors():
        loc = [str(x) for x in err.get("loc", [])]
        msg = err.get("msg", "")
        if not first_msg:
            first_msg = msg
        details.append(
            {
                "loc": loc,
                "msg": msg,
                "type": err.get("type", ""),
            }
        )

    error_message = (
        f"요청 유효성 검증 실패: {first_msg}"
        if first_msg
        else "요청 유효성 검증에 실패했습니다."
    )
    envelope = CommonErrorEnvelope(
        error=ErrorDetail(
            code="VALIDATION_ERROR",
            message=error_message,
            messageKey="error.validation_failed",
            correlationId=get_correlation_id(request),
            details=details,
        )
    )
    headers = {}
    correlation_id = get_correlation_id(request)
    if correlation_id:
        headers["X-Correlation-ID"] = correlation_id

    return JSONResponse(
        status_code=400,
        content=envelope.model_dump(mode="json"),
        headers=headers,
    )


async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    envelope = CommonErrorEnvelope(
        error=ErrorDetail(
            code="INTERNAL_SERVER_ERROR",
            message="서버 내부 오류가 발생했습니다.",
            messageKey="error.internal_server_error",
            correlationId=get_correlation_id(request),
        )
    )
    headers = {}
    correlation_id = get_correlation_id(request)
    if correlation_id:
        headers["X-Correlation-ID"] = correlation_id

    return JSONResponse(
        status_code=500,
        content=envelope.model_dump(mode="json"),
        headers=headers,
    )
