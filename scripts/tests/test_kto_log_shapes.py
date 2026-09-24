"""The operator echoes a KTO ops task's line only in the exact shape its main prints (#375).

`OPS_LOG_LINE` used to pass any `KTO_<WORD> ` line of up to 400 characters, so `KTO_OTHER KTO_SERVICE_KEY=abc123`
or `KTO_PROVIDER title=...` went through: that no key or provider text reached the operator's output held only
because the mains happened not to print one. Each shape below is written from the code that builds it (the file is
named beside it), with the values the real line carries - UUIDs, 64-hex payload hashes, `Instant` strings. The
source is read rather than run, so this stays a script test with no JVM.

A shape missing here is dropped from the operator's output, and a success gate that reads it (the smoke's
`KTO_SMOKE_OK`) then fails loudly - so every tag the ops-task mains print must have a sample below, and the probe
mains, which are not ops tasks, must stay out of OPS_TASKS.
"""

import importlib.util
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KTO = ROOT / "apps/api/src/main/java/io/nullnull/catalog/infrastructure/kto"

spec = importlib.util.spec_from_file_location("nullnull_aws_operator_kto", ROOT / "scripts/aws/staging_operator.py")
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)

PLACE = "0199a1f0-0000-7000-8000-000000000001"
SNAPSHOT = "0199a1f0-0000-7000-8000-000000000002"
RUN = "0199a1f0-0000-7000-8000-000000000003"
SET = "0199a1f0-0000-7000-8000-000000000004"
HASH = "0" * 63 + "f"
AT = "2026-09-24T03:15:30.123456Z"
STALE = "2026-10-01T03:15:30.123456Z"
SNAPSHOT_FIELDS = (f"source=KTO_KOR_SERVICE_2 contentId=126508 contentTypeId=12 snapshotId={SNAPSHOT} "
                   f"collectorRunId={RUN} sourceRegistryVersion=4 payloadHash={HASH} fetchedAt={AT}")

# Tag -> the lines its builder prints. Settings lines come from KtoSmokeEnvironment.sources: an allowed name and one
# of four origins.
SAMPLES = {
    # KtoSmokeMain (kto-smoke)
    "KTO_SMOKE_SETTINGS": ["KTO_SMOKE_SETTINGS KTO_SERVICE_KEY <- process env",
                           "KTO_SMOKE_SETTINGS SPRING_DATASOURCE_PASSWORD <- process env (overrides .env.local)",
                           "KTO_SMOKE_SETTINGS NULLNULL_ENV <- .env.local", "KTO_SMOKE_SETTINGS KTO_BASE_URL <- absent"],
    "KTO_SMOKE_OK": [f"KTO_SMOKE_OK {SNAPSHOT_FIELDS} called=true"],
    "KTO_SMOKE_CACHED": [f"KTO_SMOKE_CACHED {SNAPSHOT_FIELDS} called=false"],
    # KtoCanonicalIngestMain (kto-ingest)
    "KTO_CANONICAL_INGEST_SETTINGS": ["KTO_CANONICAL_INGEST_SETTINGS APP_CONTEST_PROFILE <- process env"],
    "KTO_CANONICAL_INGEST_OK": [f"KTO_CANONICAL_INGEST_OK placeId={PLACE} contentId=126508 contentTypeId=12 "
                                f"sourceRegistryVersion=4 snapshotId={SNAPSHOT}"],
    # KtoForecastSmokeMain (kto-forecast-smoke): no coverage, or a stored set.
    "KTO_FORECAST_SMOKE_SETTINGS": ["KTO_FORECAST_SMOKE_SETTINGS KTO_FORECAST_BASE_URL <- absent"],
    "KTO_FORECAST_SMOKE_OK": [
        f"KTO_FORECAST_SMOKE_OK source=KTO_CONCENTRATION_FORECAST placeId={PLACE} coverage=0",
        f"KTO_FORECAST_SMOKE_OK source=KTO_CONCENTRATION_FORECAST placeId={PLACE} coverage=30 snapshotSetId={SET} "
        f"collectorRunId={RUN} sourceRegistryVersion=2 forecastIssueId=kto-tats-{HASH[:32]} payloadHash={HASH} "
        f"fetchedAt={AT}"],
    # KtoDemoRefreshCommand and KtoDemoRefresh (kto-demo-detail, kto-demo-forecast)
    "KTO_DEMO_REFRESH_SETTINGS": ["KTO_DEMO_REFRESH_SETTINGS KTO_SERVICE_KEY <- process env"],
    "KTO_DEMO_REFRESH_REFUSED": ["KTO_DEMO_REFRESH_REFUSED reason=a_contentId_appears_more_than_once"],
    "KTO_DEMO_REFRESH_QUOTA": [
        "KTO_DEMO_REFRESH_QUOTA mode=detail source=KTO_KOR_SERVICE_2 places=12 planned_calls=12 per_day=1000 "
        "planned_ratio=0.0120 renew_before=PT144H",
        "KTO_DEMO_REFRESH_QUOTA mode=forecast source=KTO_CONCENTRATION_FORECAST places=12 planned_calls=0 "
        "per_day=1000 planned_ratio=0.0000 renew_before=PT18H"],
    "KTO_DEMO_REFRESH_PLACE": [
        f"KTO_DEMO_REFRESH_PLACE mode=detail contentId=126508 contentTypeId=12 status=REFRESHED placeId={PLACE}",
        f"KTO_DEMO_REFRESH_PLACE mode=forecast contentId=126508 contentTypeId=12 status=CURRENT placeId={PLACE}",
        "KTO_DEMO_REFRESH_PLACE mode=forecast contentId=126508 contentTypeId=12 status=FAILED "
        "failure=NO_CANONICAL_PLACE",
        f"KTO_DEMO_REFRESH_PLACE mode=forecast contentId=126508 contentTypeId=12 status=FAILED placeId={PLACE} "
        "failure=NO_VERIFIED_KTO_MAPPING",
        "KTO_DEMO_REFRESH_PLACE mode=detail contentId=126508 contentTypeId=12 status=FAILED "
        "failure=PROVIDER_ERROR (HttpTimeoutException)",
        "KTO_DEMO_REFRESH_PLACE mode=detail contentId=126508 contentTypeId=12 status=FAILED "
        "failure=UNEXPECTED_FAILURE (CompletionException)"],
    "KTO_DEMO_REFRESH_EVIDENCE": [
        f"KTO_DEMO_REFRESH_EVIDENCE contentId=126508 snapshotId={SNAPSHOT} collectorRunId={RUN} payloadHash={HASH} "
        f"fetchedAt={AT} staleAt={STALE}",
        f"KTO_DEMO_REFRESH_EVIDENCE contentId=126508 snapshotSetId={SET}",
        "KTO_DEMO_REFRESH_EVIDENCE contentId=126508 coverage=0",
        f"KTO_DEMO_REFRESH_EVIDENCE contentId=126508 coverage=30 snapshotSetId={SET} collectorRunId={RUN} "
        f"forecastIssueId=kto-tats-{HASH[:32]} payloadHash={HASH} fetchedAt={AT} staleAt={STALE}"],
    "KTO_DEMO_REFRESH_DONE": [
        "KTO_DEMO_REFRESH_DONE mode=detail source=KTO_KOR_SERVICE_2 places=12 refreshed=11 current=0 failed=1 "
        "calls=12 per_day=1000 calls_ratio=0.0120"],
    # KtoEngTextRefreshMain (kto-eng-text-refresh), pinned by #367 and fed back in EnglishTextTaskRegressions.
    "KTO_ENG_TEXT_REFRESH_SETTINGS": ["KTO_ENG_TEXT_REFRESH_SETTINGS KTO_ENG_BASE_URL <- process env"],
    "KTO_ENG_TEXT_REFRESH": [f"KTO_ENG_TEXT_REFRESH placeId={PLACE} outcome=UPDATED",
                             f"KTO_ENG_TEXT_REFRESH placeId={PLACE} failure=KTO_RESPONSE_REJECTED"],
    "KTO_ENG_TEXT_REFRESH_DONE": ["KTO_ENG_TEXT_REFRESH_DONE links=3 attempted=3 failed=0"],
}

# KTO_ words in the package that name a setting, a source or a constant rather than begin a printed line.
NOT_TAGS = {"KTO_SERVICE_KEY", "KTO_BASE_URL", "KTO_FORECAST_BASE_URL", "KTO_ENG_BASE_URL", "KTO_MOBILE_APP",
            "KTO_MOBILE_OS", "KTO_ALLOWED_HOST", "KTO_KOR_SERVICE_2", "KTO_CONCENTRATION_FORECAST",
            "KTO_RELATED_PLACES", "KTO_RELATIVE_CONCENTRATION_INDEX", "KTO_CONTENT_TYPE"}


def printed_tags():
    tags = set()
    for path in KTO.glob("*.java"):
        if "Probe" not in path.name:
            tags |= set(re.findall(r'"(KTO_[A-Z0-9_]+)', path.read_text(encoding="utf-8")))
    return tags - NOT_TAGS


class KtoLogShapeTests(unittest.TestCase):

    def test_every_line_a_kto_ops_main_prints_is_echoed(self):
        for tag, lines in SAMPLES.items():
            for line in lines:
                with self.subTest(tag=tag):
                    self.assertTrue(line.startswith(tag + " "), line)
                    self.assertTrue(ops.OPS_LOG_LINE.match(line), line)

    def test_every_tag_the_kto_ops_mains_print_has_a_shape_here(self):
        tags = printed_tags()
        self.assertGreater(len(tags), 10, "the package scan found too little to mean anything")
        self.assertEqual(set(SAMPLES), tags)
        # The probes print provider field names and verdicts; they stay out because nothing runs them as a task.
        mains = {main for main, _, _ in ops.OPS_TASKS.values()}
        self.assertFalse([main for main in mains if "Probe" in main], mains)

    def test_each_reason_the_demo_place_list_refuses_with_is_echoed(self):
        source = (KTO / "KtoDemoRefresh.java").read_text(encoding="utf-8")
        reasons = re.findall(r'new IllegalArgumentException\("([^"]+)"\)', source)
        self.assertEqual(4, len(reasons), reasons)
        for reason in reasons:
            line = "KTO_DEMO_REFRESH_REFUSED reason=" + reason.replace(" ", "_")
            self.assertTrue(ops.OPS_LOG_LINE.match(line), line)

    def test_a_kto_line_carrying_anything_else_is_dropped(self):
        smoke = SAMPLES["KTO_SMOKE_OK"][0]
        for line in ["KTO_OTHER KTO_SERVICE_KEY=abc123",
                     "KTO_PROVIDER title=Gyeongbokgung Palace",
                     "KTO_ANY KEY=abcdef",
                     smoke + " title=Gyeongbokgung Palace",
                     smoke.replace("called=true", "called=false"),
                     smoke.replace(f"payloadHash={HASH}", "payloadHash=serviceKey-abc"),
                     "KTO_SMOKE_SETTINGS KTO_SERVICE_KEY <- abc123",
                     "KTO_SMOKE_SETTINGS KTO_SERVICE_KEY=abc123 <- process env",
                     "KTO_DEMO_REFRESH_PLACE mode=detail contentId=126508 contentTypeId=12 status=FAILED "
                     "failure=Gyeongbokgung Palace",
                     'KTO_DEMO_REFRESH_REFUSED reason=For_input_string:_"12a"',
                     "KTO_DEMO_REFRESH_EVIDENCE contentId=126508 title=Gyeongbokgung",
                     "KTO_ENG_PROBE_FIELD title=Gyeongbokgung Palace",
                     "KTO_INTRO_PROBE_RESULT verdict=OBSERVED fields=title,addr1"]:
            with self.subTest(line=line[:60]):
                self.assertFalse(ops.OPS_LOG_LINE.match(line), line)


if __name__ == "__main__":
    unittest.main()
