import json
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from bcml_agent import wire
from bcml_agent.model import Receipt, Sample

CONTRACT = Path(__file__).resolve().parents[2] / "contract" / "agent-report" / "v1"


def contract(name: str) -> dict:
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


HEARTBEAT_SAMPLE = Sample(
    observed_at=datetime(2026, 11, 15, 2, 10, 2, 31000, tzinfo=timezone.utc),
    boot_id="0b6a3f9e-5c1d-4f7a-9a52-3e8d2c7b1f40",
    uptime_s=1203344.18,
    kernel="6.8.0-48-generic",
    driver=None,
    cpu_util_pct=41.7,
)


class WireContractTest(unittest.TestCase):
    def test_report_matches_the_contract_example(self):
        body = wire.report("0.1.0", "ws2", [wire.sample_to_wire(HEARTBEAT_SAMPLE)])

        self.assertEqual(json.loads(json.dumps(body)), contract("minimal-heartbeat.json"))

    def test_observed_at_is_sent_in_utc_whatever_zone_the_sample_carries(self):
        kst = timezone(timedelta(hours=9))
        sample = Sample(
            observed_at=HEARTBEAT_SAMPLE.observed_at.astimezone(kst),
            boot_id=HEARTBEAT_SAMPLE.boot_id,
            uptime_s=1.0,
            kernel="k",
            driver=None,
            cpu_util_pct=0.0,
        )

        self.assertEqual(wire.sample_to_wire(sample)["observed_at"], "2026-11-15T02:10:02.031Z")

    def test_receipt_example_is_read_field_for_field(self):
        example = contract("receipt.json")

        receipt = wire.receipt_from_wire(example)

        self.assertEqual(receipt, Receipt(accepted_samples=1, duplicate_samples=0, rejected=0))
        self.assertEqual(set(example), set(Receipt.__dataclass_fields__))

    def test_receipt_with_extra_or_missing_fields_still_reads(self):
        receipt = wire.receipt_from_wire({"accepted_samples": 2, "accepted_kernel_lines": 5})

        self.assertEqual(receipt, Receipt(accepted_samples=2, duplicate_samples=0, rejected=0))


if __name__ == "__main__":
    unittest.main()
