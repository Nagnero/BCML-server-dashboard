import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from bcml_agent import collect, loop
from bcml_agent.config import AgentConfig
from bcml_agent.transport import Reject, Retry, Sent


class FakeServer:
    """실제 소켓으로 응답하는 서버. 받은 요청을 기록하고, 정해 둔 상태 코드를 돌려준다."""

    def __init__(self, status: int, body: bytes = b"{}", headers: dict | None = None):
        self.requests: list[dict] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                outer.requests.append({
                    "path": self.path,
                    "authorization": self.headers.get("Authorization"),
                    "body": json.loads(self.rfile.read(length)),
                })
                self.send_response(status)
                for name, value in (headers or {}).items():
                    self.send_header(name, value)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                outer.requests.append({"path": self.path, "method": "GET"})
                self.send_response(200)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{}")

            def log_message(self, *args):
                pass

        self.httpd = HTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.httpd.server_port}/api/v1/agent/report"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


def samples(n: int) -> list[dict]:
    return [{"observed_at": f"2026-11-15T02:10:{i:02d}.000Z"} for i in range(n)]


class DeliveryTest(unittest.TestCase):
    def serve(self, status: int, body: bytes = b"{}", headers: dict | None = None) -> FakeServer:
        server = FakeServer(status, body, headers)
        self.addCleanup(server.close)
        return server

    def send(self, url: str, pending: list[dict], max_batch: int = 240):
        cfg = AgentConfig(server_url=url, token="test-token", max_batch=max_batch, http_timeout_s=2.0)
        return loop.send_pending(cfg, pending, "ws4")

    def test_accepted_report_is_removed_from_pending(self):
        server = self.serve(202, b'{"accepted_samples": 2, "duplicate_samples": 0, "rejected": 0}')
        pending = samples(2)

        result = self.send(server.url, pending)

        self.assertIsInstance(result, Sent)
        self.assertEqual(pending, [])
        self.assertEqual(server.requests[0]["authorization"], "Bearer test-token")
        self.assertEqual(server.requests[0]["body"]["hostname"], "ws4")

    def test_400_drops_the_batch_so_it_cannot_block_the_queue_forever(self):
        server = self.serve(400, b'{"error": "unsupported schema: 1"}')
        pending = samples(2)

        result = self.send(server.url, pending)

        self.assertIsInstance(result, Reject)
        self.assertIn("unsupported schema", result.reason)
        self.assertEqual(pending, [])

    def test_auth_failures_and_server_errors_keep_everything_for_the_next_cycle(self):
        for status in (401, 403, 500, 503):
            with self.subTest(status=status):
                server = self.serve(status)
                pending = samples(2)

                result = self.send(server.url, pending)

                self.assertIsInstance(result, Retry)
                self.assertEqual(pending, samples(2))

    def test_unreachable_server_keeps_everything(self):
        server = self.serve(202)
        url = server.url
        server.close()
        pending = samples(2)

        result = self.send(url, pending)

        self.assertIsInstance(result, Retry)
        self.assertEqual(pending, samples(2))

    def test_redirect_is_not_followed_and_keeps_everything(self):
        server = self.serve(302, headers={"Location": "/login"})
        pending = samples(1)

        result = self.send(server.url, pending)

        self.assertIsInstance(result, Retry)
        self.assertEqual(pending, samples(1))
        self.assertEqual(len(server.requests), 1)

    def test_token_is_not_in_any_failure_reason(self):
        server = self.serve(401, b"Bearer test-token is wrong")

        result = self.send(server.url, samples(1))

        self.assertNotIn("test-token", result.reason)

    def test_backlog_goes_newest_first_then_oldest_and_the_rest_waits(self):
        server = self.serve(202)
        pending = samples(5)

        self.send(server.url, pending, max_batch=3)

        sent = [s["observed_at"][17:19] for s in server.requests[0]["body"]["samples"]]
        self.assertEqual(sent, ["04", "00", "01"])
        self.assertEqual(pending, samples(5)[2:4])


class CollectTest(unittest.TestCase):
    def test_cpu_utilization_is_busy_share_of_the_interval(self):
        before = collect.parse_cpu_times("cpu  100 0 100 700 100 0 0 0 0 0\ncpu0 1 2 3\n")
        after = collect.parse_cpu_times("cpu  150 0 125 775 150 0 0 0 0 0\n")

        self.assertEqual(collect.cpu_utilization_pct(before, after), 37.5)
        self.assertEqual(collect.cpu_utilization_pct(after, after), 0.0)

    def test_driver_version_is_read_from_the_nvrm_line(self):
        text = ("NVRM version: NVIDIA UNIX x86_64 Kernel Module  580.126.09  Wed Jan  7 22:59:56 UTC 2026\n"
                "GCC version:  gcc version 13.3.0 (Ubuntu 13.3.0-6ubuntu2~24.04)\n")
        open_module = "NVRM version: NVIDIA UNIX Open Kernel Module for x86_64  580.126.16  Release Build\n"

        self.assertEqual(collect.parse_driver_version(text), "580.126.09")
        self.assertEqual(collect.parse_driver_version(open_module), "580.126.16")
        self.assertIsNone(collect.parse_driver_version("no such line"))


if __name__ == "__main__":
    unittest.main()
