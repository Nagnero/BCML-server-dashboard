"""노드 사실 읽기. /proc 만 본다."""

from __future__ import annotations

import os
import re
from collections.abc import Callable
from datetime import datetime
from pathlib import Path

from .model import Sample

_DRIVER_VERSION = re.compile(r"Kernel Module(?: for \S+)?\s+(\d+(?:\.\d+)+)")


def read_boot_id() -> str:
    return Path("/proc/sys/kernel/random/boot_id").read_text(encoding="ascii").strip()


def read_uptime_s() -> float:
    return float(Path("/proc/uptime").read_text(encoding="ascii").split()[0])


def read_kernel_release() -> str:
    return os.uname().release


def read_driver_version() -> str | None:
    """nvidia-smi 가 실패할 때도 이 값은 남아서 원인 파악에 쓴다. 모듈이 없으면 None."""
    try:
        text = Path("/proc/driver/nvidia/version").read_text(encoding="ascii", errors="replace")
    except OSError:
        return None
    return parse_driver_version(text)


def parse_driver_version(text: str) -> str | None:
    """'NVRM version: NVIDIA UNIX x86_64 Kernel Module  580.126.09  ...' → '580.126.09'"""
    match = _DRIVER_VERSION.search(text)
    return match.group(1) if match else None


def cpu_utilization_pct(before: tuple[int, int], after: tuple[int, int]) -> float:
    """(busy, total) 누적값 두 개 사이의 사용률. 증가분이 없으면 0.0."""
    busy = after[0] - before[0]
    total = after[1] - before[1]
    if total <= 0:
        return 0.0
    return round(min(100.0, max(0.0, 100.0 * busy / total)), 1)


def parse_cpu_times(stat_text: str) -> tuple[int, int]:
    """/proc/stat 첫 줄 'cpu user nice system idle iowait irq softirq steal ...' → (busy, total)."""
    fields = [int(v) for v in stat_text.splitlines()[0].split()[1:9]]
    total = sum(fields)
    idle_and_iowait = fields[3] + fields[4]
    return total - idle_and_iowait, total


class CpuMeter:
    """/proc/stat 첫 줄의 직전 값과 비교한 사용률. 만들 때 한 번 읽어 두므로 첫 표본부터 값이 있다."""

    def __init__(self) -> None:
        self._last = self._read()

    @staticmethod
    def _read() -> tuple[int, int]:
        return parse_cpu_times(Path("/proc/stat").read_text(encoding="ascii"))

    def utilization_pct(self) -> float:
        now = self._read()
        pct = cpu_utilization_pct(self._last, now)
        self._last = now
        return pct


def collect_sample(cpu: CpuMeter, now: Callable[[], datetime]) -> Sample:
    observed_at = now()
    return Sample(
        observed_at=observed_at,
        boot_id=read_boot_id(),
        uptime_s=read_uptime_s(),
        kernel=read_kernel_release(),
        driver=read_driver_version(),
        cpu_util_pct=cpu.utilization_pct(),
    )
