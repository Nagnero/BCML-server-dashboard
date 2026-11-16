"""HTTPS POST 한 번. 재시도 정책은 없다(메인 루프가 15초마다 다시 부르는 것이 곧 재시도)."""

from __future__ import annotations

import json
import ssl
import urllib.error
import urllib.request
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Sent:
    receipt: dict  # 서버 영수증(accepted/duplicate/rejected 수). 로그용.


@dataclass(frozen=True, slots=True)
class Retry:
    reason: str  # 네트워크 오류, 타임아웃, 5xx, 401/403(토큰 설정 오류: 데이터는 지키고 고쳐지길 기다림)


@dataclass(frozen=True, slots=True)
class Reject:
    reason: str  # 400: 배치 구조가 틀림. 다시 보내도 같으므로 지우고 로그에 남긴다(독약 배치 방지).


SendResult = Sent | Retry | Reject


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """urllib 은 POST 의 302 를 GET 으로 바꿔 따라간다. 엉뚱한 페이지의 200 을 '전송됨'으로 읽지 않게 막는다."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def post_report(url: str, token: str, body: dict, timeout_s: float) -> SendResult:
    """토큰은 예외 메시지·로그에 절대 넣지 않는다."""
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    opener = urllib.request.build_opener(
        urllib.request.HTTPSHandler(context=ssl.create_default_context()), _NoRedirect())
    try:
        with opener.open(request, timeout=timeout_s) as response:
            return Sent(_json_object(response.read()))
    except urllib.error.HTTPError as e:
        with e:
            if e.code == 400:
                return Reject(f"HTTP 400: {e.read(500).decode('utf-8', errors='replace')}")
            return Retry(f"HTTP {e.code}")
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return Retry(f"{type(e).__name__}: {getattr(e, 'reason', e)}")


def _json_object(raw: bytes) -> dict:
    """2xx 면 서버가 받은 것이다. 영수증을 못 읽어도 다시 보내지 않는다."""
    try:
        parsed = json.loads(raw)
    except ValueError:
        return {}
    return parsed if isinstance(parsed, dict) else {}
