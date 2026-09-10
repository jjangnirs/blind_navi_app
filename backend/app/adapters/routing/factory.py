import logging

from app.adapters.routing.fake_adapter import FakePedestrianRouter
from app.adapters.routing.port import PedestrianRouter
from app.adapters.routing.tmap_adapter import TmapPedestrianRouter
from app.core.config import ROUTER_PROVIDER, TMAP_APP_KEY

logger = logging.getLogger(__name__)

# 싱글톤 인스턴스 보관
_default_router: PedestrianRouter | None = None


def get_pedestrian_router() -> PedestrianRouter:
    """
    설정된 공급자(ROUTER_PROVIDER)에 맞는 PedestrianRouter 인스턴스를 반환합니다.
    - "tmap": TMAP_APP_KEY가 설정되어 있을 때 실제 TmapPedestrianRouter 사용
    - "fake" (또는 미설정): 모의 FakePedestrianRouter 사용
    """
    global _default_router
    if _default_router is not None:
        return _default_router

    provider = ROUTER_PROVIDER.strip().lower()
    if provider == "tmap" and TMAP_APP_KEY:
        logger.info("Initializing TmapPedestrianRouter adapter")
        _default_router = TmapPedestrianRouter()
    else:
        logger.info(
            "Initializing FakePedestrianRouter adapter (provider=%s, key_set=%s)",
            provider,
            bool(TMAP_APP_KEY),
        )
        _default_router = FakePedestrianRouter()

    return _default_router


def set_pedestrian_router(router: PedestrianRouter | None) -> None:
    """테스트 또는 동적 교체를 위해 라우터 인스턴스를 설정/초기화합니다."""
    global _default_router
    _default_router = router
