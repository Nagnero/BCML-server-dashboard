"""설정 읽기(경계). 여기서 검사하고, 안쪽 코드는 AgentConfig 를 믿는다."""

from __future__ import annotations

import configparser
import stat
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

DEFAULT_PATH = Path("/etc/bcml-agent/agent.ini")
SECTION = "agent"


@dataclass(frozen=True, slots=True)
class AgentConfig:
    server_url: str  # https://<도메인>/api/v1/agent/report
    token: str = field(repr=False)  # token_file 에서 읽는다. ini 에는 경로만 있다. 로그에 찍히지 않게 repr 제외.
    interval_s: float = 15.0
    max_batch: int = 240  # 한 요청에 최신 표본 + 밀린 항목 최대 239개(약 1시간 분)
    max_pending: int = 50_000  # 넘으면 가장 오래된 것부터 버린다(약 8.7일)
    http_timeout_s: float = 10.0


def load(path: Path = DEFAULT_PATH) -> AgentConfig:
    """
    agent.ini 예:
        [agent]
        server_url = https://monitor.example.org/api/v1/agent/report
        token_file = /etc/bcml-agent/token
        interval_s = 15

    틀리면 ValueError. 토큰이 평문 HTTP 로 나가지 않게 https 만 받는다.
    로컬 시험에서만 allow_insecure_http = true 를 직접 적어 http 를 연다.
    """
    parser = configparser.ConfigParser(interpolation=None)
    try:
        with open(path, encoding="utf-8") as f:
            parser.read_file(f)
    except (OSError, configparser.Error) as e:
        raise ValueError(f"cannot read config {path}: {e}") from e
    if not parser.has_section(SECTION):
        raise ValueError(f"{path}: missing [{SECTION}] section")
    section = parser[SECTION]

    try:
        allow_http = section.getboolean("allow_insecure_http", fallback=False)
        interval_s = section.getfloat("interval_s", fallback=15.0)
    except ValueError as e:
        raise ValueError(f"{path}: {e}") from e
    if not 1.0 <= interval_s <= 3600.0:
        raise ValueError(f"{path}: interval_s must be between 1 and 3600, got {interval_s}")

    server_url = section.get("server_url", "").strip()
    url = urlsplit(server_url)
    if url.scheme not in ("https", "http") or not url.netloc:
        raise ValueError(f"{path}: server_url must be an https:// URL, got {server_url!r}")
    if url.scheme == "http" and not allow_http:
        raise ValueError(f"{path}: server_url is http://, refusing to send the token unencrypted "
                         "(set allow_insecure_http = true for local testing only)")

    token_file = section.get("token_file", "").strip()
    if not token_file:
        raise ValueError(f"{path}: token_file is missing")
    return AgentConfig(server_url=server_url, token=_read_token(Path(token_file)), interval_s=interval_s)


def _read_token(path: Path) -> str:
    try:
        mode = path.stat().st_mode
        token = path.read_text(encoding="utf-8").strip()
    except OSError as e:
        raise ValueError(f"cannot read token file {path}: {e.strerror}") from e
    if mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise ValueError(f"token file {path} is readable by group or others, expected mode 0600")
    if not token:
        raise ValueError(f"token file {path} is empty")
    if any(c.isspace() for c in token):
        raise ValueError(f"token file {path} must hold a single token with no whitespace")
    return token
