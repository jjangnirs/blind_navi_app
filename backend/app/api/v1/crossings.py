import json
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.core.config import (
    DATA_VERSION,
    DEFAULT_BUFFER_M,
    DEFAULT_LIMIT,
    DEFAULT_RADIUS_M,
    MAX_BUFFER_M,
    MAX_LAT,
    MAX_LIMIT,
    MAX_LON,
    MAX_RADIUS_M,
    MIN_BUFFER_M,
    MIN_LAT,
    MIN_LIMIT,
    MIN_LON,
    MIN_RADIUS_M,
)
from app.core.database import get_db
from app.core.exceptions import AppException, CommonErrorEnvelope
from app.domain.schemas import (
    CorridorRequest,
    CrossingsListResponse,
)
from app.repositories.crossing_repo import (
    find_crossings_in_corridor,
    find_crossings_nearby,
)

COMMON_RESPONSES = {
    400: {
        "model": CommonErrorEnvelope,
        "description": "잘못된 요청 또는 유효성 검증 실패",
    },
    404: {"model": CommonErrorEnvelope, "description": "요청한 리소스를 찾을 수 없음"},
    429: {"model": CommonErrorEnvelope, "description": "요청 허용량 초과 (Rate Limit)"},
    500: {"model": CommonErrorEnvelope, "description": "서버 내부 처리 오류"},
    504: {
        "model": CommonErrorEnvelope,
        "description": "요청 처리 제한시간 초과 (Timeout)",
    },
}

router = APIRouter(
    prefix="/v1/crossings",
    tags=["crossings"],
    responses=COMMON_RESPONSES,
)


@router.get(
    "/nearby",
    response_model=CrossingsListResponse,
    summary="반경 기반 주변 횡단보도 조회",
    description="주어진 중심 좌표(lat, lon)로부터 radiusM 반경 내의 횡단보도 시설 목록을 거리순으로 반환합니다.",
)
def get_nearby_crossings(
    lat: float = Query(
        ...,
        ge=MIN_LAT,
        le=MAX_LAT,
        description=f"위도 (WGS84, {MIN_LAT}~{MAX_LAT})",
    ),
    lon: float = Query(
        ...,
        ge=MIN_LON,
        le=MAX_LON,
        description=f"경도 (WGS84, {MIN_LON}~{MAX_LON})",
    ),
    radiusM: float = Query(
        DEFAULT_RADIUS_M,
        ge=MIN_RADIUS_M,
        le=MAX_RADIUS_M,
        description=f"검색 반경(미터) ({MIN_RADIUS_M}~{MAX_RADIUS_M})",
    ),
    limit: int = Query(
        DEFAULT_LIMIT,
        ge=MIN_LIMIT,
        le=MAX_LIMIT,
        description=f"최대 반환 개수 ({MIN_LIMIT}~{MAX_LIMIT})",
    ),
    db: Session = Depends(get_db),  # noqa: B008
) -> CrossingsListResponse:
    items = find_crossings_nearby(
        db=db,
        lat=lat,
        lon=lon,
        radius_m=radiusM,
        limit=limit,
    )

    return CrossingsListResponse(
        dataVersion=DATA_VERSION,
        generatedAt=datetime.now(timezone.utc).isoformat(),
        items=items,
    )


def _parse_corridor_path(path_str: str) -> list[list[float]]:
    """
    path 쿼리 문자열을 파싱하여 [[lon, lat], ...] 형태의 좌표 리스트로 변환합니다.
    지원 포맷:
    1. GeoJSON LineString: '{"type":"LineString","coordinates":[[lon,lat],...]}'
    2. JSON 좌표 리스트: '[[lon,lat],[lon,lat],...]'
    3. 세미콜론/콤마 구분자: 'lon1,lat1;lon2,lat2;...'
    """
    path_str = path_str.strip()
    if path_str.startswith("{"):
        try:
            data = json.loads(path_str)
            if data.get("type") != "LineString" or "coordinates" not in data:
                raise ValueError(
                    "GeoJSON은 type='LineString'과 coordinates 필드가 필요합니다."
                )
            return data["coordinates"]
        except (json.JSONDecodeError, ValueError) as e:
            raise AppException(
                status_code=400,
                code="BAD_REQUEST",
                message=f"GeoJSON LineString 파싱 실패: {e}",
                message_key="error.invalid_corridor_geojson",
            ) from e

    if path_str.startswith("["):
        try:
            data = json.loads(path_str)
            if not isinstance(data, list):
                raise TypeError("좌표 리스트는 배열이어야 합니다.")
            return data
        except (json.JSONDecodeError, ValueError, TypeError) as e:
            raise AppException(
                status_code=400,
                code="BAD_REQUEST",
                message=f"JSON 좌표 배열 파싱 실패: {e}",
                message_key="error.invalid_corridor_json",
            ) from e

    # 세미콜론 구분자 파싱
    try:
        coords = []
        for pair in path_str.split(";"):
            pair = pair.strip()
            if not pair:
                continue
            parts = [float(p.strip()) for p in pair.split(",")]
            if len(parts) != 2:
                raise ValueError(f"각 좌표 쌍은 'lon,lat' 형식이어야 합니다: '{pair}'")
            coords.append([parts[0], parts[1]])
        if len(coords) < 2:
            raise ValueError("최소 2개 이상의 좌표 쌍이 필요합니다.")
        return coords
    except Exception as e:
        raise AppException(
            status_code=400,
            code="BAD_REQUEST",
            message=f"경로 좌표 문자열 파싱 실패: {e}",
            message_key="error.invalid_corridor_path",
        ) from e


@router.get(
    "/corridor",
    response_model=CrossingsListResponse,
    summary="경로 회랑(Corridor) 기반 횡단보도 조회 (GET)",
    description="LineString 보행 경로와 bufferM 폭 내에 포함되는 횡단보도 목록을 경로 접근 순으로 반환합니다.",
)
def get_corridor_crossings_get(
    path: str = Query(
        ...,
        description="경로 좌표. GeoJSON LineString 또는 'lon1,lat1;lon2,lat2' 형식",
    ),
    bufferM: float = Query(
        DEFAULT_BUFFER_M,
        ge=MIN_BUFFER_M,
        le=MAX_BUFFER_M,
        description=f"경로 회랑 버퍼 폭 ({MIN_BUFFER_M}~{MAX_BUFFER_M}m)",
    ),
    limit: int = Query(
        DEFAULT_LIMIT,
        ge=MIN_LIMIT,
        le=MAX_LIMIT,
        description=f"최대 반환 개수 ({MIN_LIMIT}~{MAX_LIMIT})",
    ),
    db: Session = Depends(get_db),  # noqa: B008
) -> CrossingsListResponse:
    parsed_coords = _parse_corridor_path(path)
    try:
        # Pydantic을 통한 좌표 유효성(정점수, 대한민국 범위, 총 경로 길이) 검증
        req = CorridorRequest(
            coordinates=parsed_coords,
            bufferM=bufferM,
            limit=limit,
        )
    except ValueError as e:
        raise AppException(
            status_code=400,
            code="BAD_REQUEST",
            message=str(e),
            message_key="error.corridor_validation_failed",
        ) from e

    items = find_crossings_in_corridor(
        db=db,
        coordinates=req.coordinates,
        buffer_m=req.bufferM,
        limit=req.limit,
    )

    return CrossingsListResponse(
        dataVersion=DATA_VERSION,
        generatedAt=datetime.now(timezone.utc).isoformat(),
        items=items,
    )


@router.post(
    "/corridor",
    response_model=CrossingsListResponse,
    summary="경로 회랑(Corridor) 기반 횡단보도 조회 (POST)",
    description="GeoJSON LineString 본문과 bufferM 폭 내에 포함되는 횡단보도 목록을 경로 접근 순으로 반환합니다.",
)
def get_corridor_crossings_post(
    req: CorridorRequest,
    db: Session = Depends(get_db),  # noqa: B008
) -> CrossingsListResponse:
    items = find_crossings_in_corridor(
        db=db,
        coordinates=req.coordinates,
        buffer_m=req.bufferM,
        limit=req.limit,
    )

    return CrossingsListResponse(
        dataVersion=DATA_VERSION,
        generatedAt=datetime.now(timezone.utc).isoformat(),
        items=items,
    )
