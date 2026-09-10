import os
from pathlib import Path

from dotenv import load_dotenv

# 루트 디렉터리의 .env 파일 자동 로드
_env_file = Path(__file__).resolve().parent.parent.parent.parent / ".env"
if _env_file.exists():
    load_dotenv(_env_file)
else:
    load_dotenv()

DATA_VERSION: str = os.getenv("DATA_VERSION", "kr-crossing-20260908.1")

# 대한민국 지리적 범위 (SR-F-024)
MIN_LAT: float = 32.0
MAX_LAT: float = 39.0
MIN_LON: float = 124.0
MAX_LON: float = 132.0

# 반경 검색 제한 (10m ~ 500m)
MIN_RADIUS_M: float = 10.0
MAX_RADIUS_M: float = 500.0
DEFAULT_RADIUS_M: float = 100.0

# 경로 회랑(Corridor) 제한
MIN_BUFFER_M: float = 5.0
MAX_BUFFER_M: float = 100.0
DEFAULT_BUFFER_M: float = 30.0
MIN_CORRIDOR_VERTICES: int = 2
MAX_CORRIDOR_VERTICES: int = 500
MAX_CORRIDOR_LENGTH_M: float = 10000.0  # 최대 10km (과도한 지오메트리 DoS 방어)

# 쿼리 결과 개수 제한
MIN_LIMIT: int = 1
MAX_LIMIT: int = 100
DEFAULT_LIMIT: int = 50

# 처리율 제한 및 타임아웃
RATE_LIMIT_PER_MINUTE: int = int(os.getenv("RATE_LIMIT_PER_MINUTE", "60"))
DEFAULT_TIMEOUT_SEC: float = float(os.getenv("DEFAULT_TIMEOUT_SEC", "5.0"))

# TMAP 보행자 경로 API 설정 (SR-F-010~015)
TMAP_APP_KEY: str = os.getenv("TMAP_APP_KEY", "")
ROUTER_PROVIDER: str = os.getenv("ROUTER_PROVIDER", "fake")  # "fake" | "tmap"
TMAP_BASE_URL: str = os.getenv(
    "TMAP_BASE_URL", "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1"
)
TMAP_CONNECT_TIMEOUT_SEC: float = float(os.getenv("TMAP_CONNECT_TIMEOUT_SEC", "3.0"))
TMAP_READ_TIMEOUT_SEC: float = float(os.getenv("TMAP_READ_TIMEOUT_SEC", "5.0"))
TMAP_MAX_RETRIES: int = int(os.getenv("TMAP_MAX_RETRIES", "2"))
