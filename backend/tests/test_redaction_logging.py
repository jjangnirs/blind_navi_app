import io
import logging

import pytest
from app.main import app
from fastapi.testclient import TestClient

client = TestClient(app)


@pytest.fixture
def capture_access_logs():
    """
    safecross.access 로거에 독립적인 StreamHandler를 임시 부착하여
    다른 테스트의 로깅 재설정과 무관하게 결정론적으로 로그를 캡처합니다.
    """
    logger = logging.getLogger("safecross.access")
    logger.disabled = False
    logger.setLevel(logging.INFO)

    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setLevel(logging.INFO)
    logger.addHandler(handler)

    try:
        yield stream
    finally:
        logger.removeHandler(handler)


def test_redaction_logging_masks_lat_and_lon(capture_access_logs):
    """
    수용 기준: 로그 캡처 테스트에 좌표 원문 없음 (SR-NF-022, SR-NF-041)
    /v1/crossings/nearby 호출 시 query의 lat/lon이 [REDACTED]로 마스킹되어
    원문 부동소수점 좌표가 엑세스 로그에 절대 남지 않는지 검증
    """
    test_lat = 35.1876543
    test_lon = 126.8765432

    response = client.get(
        f"/v1/crossings/nearby?lat={test_lat}&lon={test_lon}&radiusM=100"
    )

    assert response.status_code == 200
    log_text = capture_access_logs.getvalue()

    # 원문 좌표가 로그에 일체 없어야 함
    assert str(test_lat) not in log_text, (
        f"위도 원문({test_lat})이 엑세스 로그에 노출되었습니다: {log_text}"
    )
    assert str(test_lon) not in log_text, (
        f"경도 원문({test_lon})이 엑세스 로그에 노출되었습니다: {log_text}"
    )

    # 마스킹 표시가 로그에 기록되어야 함
    assert "[REDACTED]" in log_text
    assert "GET /v1/crossings/nearby" in log_text


def test_redaction_logging_masks_corridor_path(capture_access_logs):
    """
    수용 기준: 경로 회랑(corridor) 요청 시 경로 좌표 문자열이 [REDACTED]로 마스킹되는지 검증
    """
    test_path = "126.8501234,35.1501234;126.8519876,35.1519876"

    response = client.get(f"/v1/crossings/corridor?path={test_path}&bufferM=30")

    assert response.status_code == 200
    log_text = capture_access_logs.getvalue()

    assert "126.8501234" not in log_text
    assert "35.1501234" not in log_text
    assert "[REDACTED]" in log_text
    assert "GET /v1/crossings/corridor" in log_text
