import time
from enum import Enum


class CircuitState(Enum):
    CLOSED = "CLOSED"
    OPEN = "OPEN"
    HALF_OPEN = "HALF_OPEN"


class CircuitBreakerOpenException(Exception):
    """서킷 브레이커가 OPEN 상태일 때 발생하는 예외"""

    def __init__(
        self,
        message: str = "외부 경로 공급자 장애로 인해 서킷 브레이커가 활성화되었습니다.",
    ):
        super().__init__(message)


class CircuitBreaker:
    """
    외부 공급자(TMAP 등) 장애 전파를 방지하기 위한 서킷 브레이커입니다.
    연속 실패 횟수가 failure_threshold에 도달하면 OPEN 상태로 전환되어
    recovery_timeout 초 동안 외부 호출을 즉시 차단합니다.
    """

    def __init__(
        self,
        failure_threshold: int = 5,
        recovery_timeout_sec: float = 30.0,
    ):
        self.failure_threshold = failure_threshold
        self.recovery_timeout_sec = recovery_timeout_sec
        self.state = CircuitState.CLOSED
        self.failure_count = 0
        self.last_failure_time = 0.0

    def can_execute(self) -> bool:
        now = time.time()
        if self.state == CircuitState.OPEN:
            if now - self.last_failure_time > self.recovery_timeout_sec:
                self.state = CircuitState.HALF_OPEN
                return True
            return False
        return True

    def record_success(self) -> None:
        self.failure_count = 0
        self.state = CircuitState.CLOSED

    def record_failure(self) -> None:
        self.failure_count += 1
        self.last_failure_time = time.time()
        if self.failure_count >= self.failure_threshold:
            self.state = CircuitState.OPEN
