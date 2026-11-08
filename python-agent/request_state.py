"""Bounded, process-local request state; durable ticket uniqueness stays in Java."""

from contextlib import contextmanager
from dataclasses import dataclass, field
from threading import Lock
from time import monotonic
from typing import Any, Callable

from tools import UserContext


class RequestConflict(ValueError):
    """The same user/request ID was reused with different original input."""


class RequestCapacityError(RuntimeError):
    """All retained request slots are still within their retention period."""


@dataclass
class RequestState:
    session_id: str
    message: str
    context: UserContext
    last_used: float
    active: int = 0
    response: Any = None
    lock: Any = field(default_factory=Lock)


class RequestStateCache:
    def __init__(self, ttl_seconds: float, max_entries: int,
                 clock: Callable[[], float] = monotonic):
        if ttl_seconds <= 0 or max_entries < 1:
            raise ValueError("request cache TTL and capacity must be positive")
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self._clock = clock
        self._guard = Lock()
        self._entries: dict[tuple[int, str], RequestState] = {}

    @contextmanager
    def lease(self, user_id: int, request_id: str, session_id: str, message: str):
        key = (user_id, request_id)
        with self._guard:
            now = self._clock()
            expired = [existing for existing, state in self._entries.items()
                       if state.active == 0 and now - state.last_used >= self.ttl_seconds]
            for existing in expired:
                del self._entries[existing]
            state = self._entries.get(key)
            if state is None:
                # Retain unexpired unknown facts instead of silently discarding them
                # under load. Active and queued requests are never expired or evicted.
                if len(self._entries) >= self.max_entries:
                    raise RequestCapacityError("request state cache is full")
                state = RequestState(session_id, message, UserContext(user_id, request_id), now)
                self._entries[key] = state
            elif state.session_id != session_id or state.message != message:
                raise RequestConflict("request ID is bound to different input")
            state.active += 1
        try:
            with state.lock:
                yield state
        finally:
            with self._guard:
                state.active -= 1
                state.last_used = self._clock()
