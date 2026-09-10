import re
from datetime import date
from typing import Any


class ValidationError(ValueError):
    def __init__(self, reason: str, details: str):
        super().__init__(f"{reason}: {details}")
        self.reason = reason
        self.details = details


# 대한민국 1차 좌표 범위 (DQ-001)
MIN_LAT = 32.0
MAX_LAT = 39.0
MIN_LON = 124.0
MAX_LON = 132.0


def validate_coordinates(lat_val: Any, lon_val: Any) -> tuple[float, float]:
    """
    위도와 경도를 검증합니다.
    - 위경도 뒤바뀜 감지 시 ValidationError('COORDINATES_SWAPPED', ...) 발생
    - 대한민국 범위 밖 감지 시 ValidationError('COORDINATES_OUT_OF_BOUNDS', ...) 발생
    - 숫자 변환 불가 시 ValidationError('INVALID_NUMERIC_COORDINATES', ...) 발생
    """
    try:
        lat = float(lat_val)
        lon = float(lon_val)
    except (ValueError, TypeError) as e:
        raise ValidationError(
            "INVALID_NUMERIC_COORDINATES",
            f"위도/경도 숫자 변환 실패: lat={lat_val}, lon={lon_val}",
        ) from e

    # Check for swapped coordinates (lat in lon range, lon in lat range)
    if (MIN_LON <= lat <= MAX_LON) and (MIN_LAT <= lon <= MAX_LAT):
        raise ValidationError(
            "COORDINATES_SWAPPED",
            f"위도와 경도가 뒤바뀐 것으로 추정됩니다: lat={lat}(경도범위), lon={lon}(위도범위)",
        )

    # Check bounds
    if not (MIN_LAT <= lat <= MAX_LAT and MIN_LON <= lon <= MAX_LON):
        raise ValidationError(
            "COORDINATES_OUT_OF_BOUNDS",
            f"좌표가 대한민국 허용 범위를 벗어났습니다: lat={lat}, lon={lon} (허용: 위도 32~39, 경도 124~132)",
        )

    return lat, lon


def validate_date(date_val: Any | None) -> date | None:
    """
    기준일자(YYYY-MM-DD 또는 YYYYMMDD)를 검증하고 date 객체로 파싱합니다.
    """
    if date_val is None or str(date_val).strip() == "":
        return None

    cleaned = str(date_val).strip()
    # YYYY-MM-DD
    if re.match(r"^\d{4}-\d{2}-\d{2}$", cleaned):
        try:
            return date.fromisoformat(cleaned)
        except ValueError as e:
            raise ValidationError(
                "INVALID_DATE_FORMAT", f"유효하지 않은 날짜입니다: {cleaned}"
            ) from e
    # YYYYMMDD
    if re.match(r"^\d{8}$", cleaned):
        try:
            return date(int(cleaned[:4]), int(cleaned[4:6]), int(cleaned[6:]))
        except ValueError as e:
            raise ValidationError(
                "INVALID_DATE_FORMAT", f"유효하지 않은 날짜입니다: {cleaned}"
            ) from e

    raise ValidationError(
        "INVALID_DATE_FORMAT", f"지원하지 않는 날짜 포맷입니다: {cleaned}"
    )


def validate_source_record_key(key_val: Any | None) -> str:
    """원천 관리번호/식별자가 존재하는지 검증합니다."""
    if key_val is None or str(key_val).strip() == "":
        raise ValidationError("MISSING_RECORD_KEY", "원천 식별키가 누락되었습니다.")
    return str(key_val).strip()
