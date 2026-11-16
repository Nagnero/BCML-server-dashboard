"""에이전트 안의 도메인 타입. 전송 형식(wire.py)과 분리해 둔다."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True, slots=True)
class Sample:
    observed_at: datetime  # UTC, tz-aware
    boot_id: str  # /proc/sys/kernel/random/boot_id
    uptime_s: float  # /proc/uptime 첫 값
    kernel: str  # os.uname().release
    driver: str | None  # /proc/driver/nvidia/version 의 모듈 버전. 모듈이 없으면 None.
    cpu_util_pct: float


@dataclass(frozen=True, slots=True)
class Receipt:
    """서버 영수증. 로그용."""

    accepted_samples: int
    duplicate_samples: int
    rejected: int
