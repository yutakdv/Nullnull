import importlib.util
import json
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "aws" / "cloudfront_access_report.py"
spec = importlib.util.spec_from_file_location("cloudfront_access_report", SCRIPT)
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class CloudFrontAccessReportTest(unittest.TestCase):
    def test_summary_keeps_identifiers_and_raw_paths_out_of_output(self):
        events = [{"message": json.dumps({
            "date": "2026-09-23", "time": "04:42:11", "c-ip": "203.0.113.18",
            "cs-uri-stem": "/posts/sensitive-id", "sc-status": 200,
            "cs(User-Agent)": "secret-agent/1", "c-country": "KR",
            "x-edge-result-type": "Miss",
        })}]
        result = report.summarize(events)
        encoded = json.dumps(result)
        self.assertEqual(result["distinctIpEstimate"], 1)
        self.assertEqual(result["byRouteGroup"], {"post page": 1})
        for value in ("203.0.113.18", "sensitive-id", "secret-agent"):
            self.assertNotIn(value, encoded)


if __name__ == "__main__":
    unittest.main()
