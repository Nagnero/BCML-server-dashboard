"""전송 형식 v1. 필드 이름이 정해지는 유일한 곳(에이전트 쪽).

서버 쪽 짝은 server/.../observation/ReportJson.java. 둘 다 contract/agent-report/v1/*.json 예제와 비교하는
테스트가 있어서(agent/tests/test_wire_contract.py, server ReportContractTest) 한쪽만 바꾸면 빌드가 깨진다.
"""

from __future__ import annotations

from datetime import timezone

from .model import Receipt, Sample

SCHEMA = 1


def sample_to_wire(s: Sample) -> dict:
    # 밀리초로 고정한다. 재전송 때 같은 문자열이 가야 서버의 (node_id, observed_at) 키가 중복을 거른다.
    observed_at = s.observed_at.astimezone(timezone.utc).isoformat(timespec="milliseconds")
    return {
        "observed_at": observed_at.replace("+00:00", "Z"),
        "boot_id": s.boot_id,
        "uptime_s": s.uptime_s,
        "kernel": s.kernel,
        "driver": s.driver,
        "cpu_util_pct": s.cpu_util_pct,
    }


def report(agent_version: str, hostname: str, samples: list[dict]) -> dict:
    return {"schema": SCHEMA, "agent_version": agent_version, "hostname": hostname, "samples": samples}


def receipt_from_wire(body: dict) -> Receipt:
    """서버가 필드를 더 보내도 무시한다. 빠진 숫자는 0 으로 읽는다(영수증은 로그용이라 전송 성공 여부를 바꾸지 않는다)."""
    return Receipt(
        accepted_samples=int(body.get("accepted_samples", 0)),
        duplicate_samples=int(body.get("duplicate_samples", 0)),
        rejected=int(body.get("rejected", 0)),
    )
