import os
import tempfile
import unittest
from pathlib import Path

from bcml_agent import config


class ConfigTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)
        self.token_file = self.dir / "token"
        self.token_file.write_text("test-token-value\n", encoding="utf-8")
        os.chmod(self.token_file, 0o600)

    def write_ini(self, body: str) -> Path:
        path = self.dir / "agent.ini"
        path.write_text(body.replace("TOKEN_FILE", str(self.token_file)), encoding="utf-8")
        return path

    def test_https_url_and_private_token_file_load(self):
        cfg = config.load(self.write_ini(
            "[agent]\nserver_url = https://monitor.example.org/api/v1/agent/report\ntoken_file = TOKEN_FILE\n"))

        self.assertEqual(cfg.server_url, "https://monitor.example.org/api/v1/agent/report")
        self.assertEqual(cfg.token, "test-token-value")
        self.assertEqual(cfg.interval_s, 15.0)

    def test_token_never_appears_in_the_config_repr(self):
        cfg = config.load(self.write_ini(
            "[agent]\nserver_url = https://monitor.example.org/r\ntoken_file = TOKEN_FILE\n"))

        self.assertNotIn("test-token-value", repr(cfg))

    def test_http_url_is_refused_by_default(self):
        with self.assertRaisesRegex(ValueError, "allow_insecure_http"):
            config.load(self.write_ini(
                "[agent]\nserver_url = http://localhost:8080/api/v1/agent/report\ntoken_file = TOKEN_FILE\n"))

    def test_http_url_is_refused_when_the_flag_is_explicitly_false(self):
        with self.assertRaisesRegex(ValueError, "allow_insecure_http"):
            config.load(self.write_ini(
                "[agent]\nserver_url = http://localhost:8080/r\ntoken_file = TOKEN_FILE\n"
                "allow_insecure_http = false\n"))

    def test_http_url_loads_only_with_the_explicit_flag(self):
        cfg = config.load(self.write_ini(
            "[agent]\nserver_url = http://localhost:8080/api/v1/agent/report\ntoken_file = TOKEN_FILE\n"
            "allow_insecure_http = true\ninterval_s = 5\n"))

        self.assertEqual(cfg.server_url, "http://localhost:8080/api/v1/agent/report")
        self.assertEqual(cfg.interval_s, 5.0)

    def test_urls_that_are_neither_http_nor_https_are_refused(self):
        for url in ("ftp://example.org/r", "monitor.example.org/r", "https://", ""):
            with self.subTest(url=url), self.assertRaisesRegex(ValueError, "server_url"):
                config.load(self.write_ini(
                    f"[agent]\nserver_url = {url}\ntoken_file = TOKEN_FILE\nallow_insecure_http = true\n"))

    def test_token_file_readable_by_others_is_refused(self):
        os.chmod(self.token_file, 0o644)

        with self.assertRaisesRegex(ValueError, "0600"):
            config.load(self.write_ini("[agent]\nserver_url = https://m.example.org/r\ntoken_file = TOKEN_FILE\n"))

    def test_empty_token_file_is_refused(self):
        self.token_file.write_text("\n", encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "empty"):
            config.load(self.write_ini("[agent]\nserver_url = https://m.example.org/r\ntoken_file = TOKEN_FILE\n"))

    def test_missing_token_file_is_refused(self):
        self.token_file.unlink()

        with self.assertRaisesRegex(ValueError, "token file"):
            config.load(self.write_ini("[agent]\nserver_url = https://m.example.org/r\ntoken_file = TOKEN_FILE\n"))

    def test_missing_config_file_or_section_is_refused(self):
        with self.assertRaisesRegex(ValueError, "cannot read config"):
            config.load(self.dir / "absent.ini")
        with self.assertRaisesRegex(ValueError, r"\[agent\]"):
            config.load(self.write_ini("[other]\nserver_url = https://m.example.org/r\n"))

    def test_interval_outside_the_allowed_range_is_refused(self):
        for interval in ("0", "-5", "86400", "soon"):
            with self.subTest(interval=interval), self.assertRaises(ValueError):
                config.load(self.write_ini(
                    "[agent]\nserver_url = https://m.example.org/r\ntoken_file = TOKEN_FILE\n"
                    f"interval_s = {interval}\n"))


if __name__ == "__main__":
    unittest.main()
