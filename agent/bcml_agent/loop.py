"""에이전트 전체 흐름. 이 파일 하나만 읽으면 무엇이 언제 일어나는지 다 보인다."""

from __future__ import annotations

import logging
import socket
import time
from datetime import datetime, timezone

from . import __version__, collect, transport, wire
from .config import AgentConfig
from .transport import Reject, Retry, SendResult, Sent

log = logging.getLogger("bcml_agent")


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def next_batch(pending: list[dict], max_batch: int) -> list[dict]:
    """
    가장 최근 표본 1개 + 밀린 것을 오래된 순으로. pending 은 오래된 것이 앞이다.
    최신을 항상 넣는 이유: 밀린 데이터가 많아도 서버가 노드가 지금 살아 있음을 바로 알아야 한다.
    """
    if not pending:
        return []
    return [pending[-1], *pending[:-1][: max_batch - 1]]


def send_pending(cfg: AgentConfig, pending: list[dict], hostname: str) -> SendResult:
    """한 묶음을 보내고, 서버가 받았거나(2xx) 다시 보내도 소용없으면(400) pending 에서 지운다."""
    batch = next_batch(pending, cfg.max_batch)
    result = transport.post_report(
        cfg.server_url, cfg.token, wire.report(__version__, hostname, batch), cfg.http_timeout_s)
    match result:
        case Sent(receipt):
            _remove(pending, batch)
            log.info("sent %d sample(s): %s", len(batch), wire.receipt_from_wire(receipt))
        case Reject(reason):
            _remove(pending, batch)
            log.error("server rejected %d sample(s), dropped: %s", len(batch), reason)
        case Retry(reason):
            log.warning("not sent, %d sample(s) kept for the next cycle: %s", len(pending), reason)
    return result


def _remove(pending: list[dict], batch: list[dict]) -> None:
    sent = {id(item) for item in batch}
    pending[:] = [item for item in pending if id(item) not in sent]


def run_once(cfg: AgentConfig) -> bool:
    """표본 1개를 모아 한 번 보낸다. 수동 확인용(--once). 서버가 받았으면 True."""
    sample = wire.sample_to_wire(collect.collect_sample(collect.CpuMeter(), now_utc))
    log.info("collected %s", sample)
    return isinstance(send_pending(cfg, [sample], socket.gethostname()), Sent)


def run(cfg: AgentConfig) -> None:
    hostname = socket.gethostname()
    cpu = collect.CpuMeter()
    # 메모리에만 둔다: 에이전트가 재시작하면 밀린 표본이 사라진다. 디스크 버퍼(store.py, sqlite)는 M3.
    pending: list[dict] = []
    next_at = time.monotonic()
    while True:
        next_at += cfg.interval_s
        pending.append(wire.sample_to_wire(collect.collect_sample(cpu, now_utc)))
        del pending[: max(0, len(pending) - cfg.max_pending)]
        send_pending(cfg, pending, hostname)
        # 밀려도 주기를 따라잡되 몰아서 돌지 않는다.
        next_at = max(next_at, time.monotonic())
        time.sleep(max(0.0, next_at - time.monotonic()))
