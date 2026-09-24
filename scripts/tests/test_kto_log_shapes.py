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
        "failure=KTO_TRANSPORT_FAILED (HttpTimeoutException)",
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


JAVA = ROOT / "apps/api/src/main/java/io/nullnull"


def enum_constants(path, name):
    """An enum's constant names, comments removed first: a javadoc between constants holds commas too."""
    source = re.sub(r"/\*.*?\*/|//[^\n]*", "", path.read_text(encoding="utf-8"), flags=re.S)
    declared = re.search(r"enum " + name + r"\s*\{([^;}]*)", source)
    return [constant.strip().split("(")[0].strip() for constant in declared.group(1).split(",")
            if constant.strip()] if declared else []


def written_failure_codes():
    """The codes the KTO mains write out rather than take from an enum, found by the four ways they write one."""
    codes = set()
    for path in KTO.glob("*.java"):
        if "Probe" in path.name:
            continue
        source = re.sub(r"/\*.*?\*/|//[^\n]*", "", path.read_text(encoding="utf-8"), flags=re.S)
        codes |= set(re.findall(r'failure\("([A-Z_]+)"\)', source))
        codes |= set(re.findall(r'failed: ([A-Z_]+)"', source))
        codes |= set(re.findall(r'Outcome\.failed\([a-z]+, "([A-Z_]+)"', source))
        codes |= set(re.findall(r', "([A-Z_]+)", (?:true|false)\)', source))
        codes |= set(re.findall(r'(?:return |\? )"([A-Z_]+)"', source)) - {"KTO_SMOKE_OK", "KTO_SMOKE_CACHED"}
        codes |= set(re.findall(r'"([A-Z_]+) \("', source))
    return codes


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
        # KtoDemoRefresh.places catches IllegalArgumentException around its own checks and KtoPlaceRequest's, whose
        # message names the field ("contentId must be a positive KTO identifier").
        source = (KTO / "KtoDemoRefresh.java").read_text(encoding="utf-8")
        reasons = set(re.findall(r'new IllegalArgumentException\("([^"]+)"\)', source))
        self.assertEqual(3, len(reasons), reasons)
        request = next(JAVA.rglob("KtoPlaceRequest.java")).read_text(encoding="utf-8")
        fields = re.findall(r'normalized\([a-zA-Z]+, "([a-zA-Z]+)"\)', request)
        self.assertEqual(["contentId", "contentTypeId"], fields)
        self.assertIn('field + " must be a positive KTO identifier"', request)
        reasons |= {field + " must be a positive KTO identifier" for field in fields}
        self.assertEqual({reason.replace(" ", "_") for reason in reasons}, set(ops.KTO_DEMO_REFUSED_REASONS))
        for reason in ops.KTO_DEMO_REFUSED_REASONS:
            line = "KTO_DEMO_REFRESH_REFUSED reason=" + reason
            self.assertTrue(ops.OPS_LOG_LINE.match(line), line)

    def test_the_operator_names_exactly_the_failure_codes_the_mains_print(self):
        # Equal both ways: a code Java prints and the list lacks is held back from the output, and a list word
        # Java never prints is one the allowlist takes from any line of that shape.
        gateway = enum_constants(next(JAVA.rglob("KtoGatewayException.java")), "Code")
        refused = enum_constants(next(JAVA.rglob("OperationsContext.java")), "Code")
        written = written_failure_codes()
        self.assertEqual(9, len(gateway), gateway)
        self.assertEqual(5, len(refused), refused)
        self.assertEqual(9, len(written), sorted(written))
        self.assertEqual(set(gateway) | set(refused) | written, set(ops.KTO_FAILURE_CODES))

    def test_an_uncaught_failure_is_echoed_in_both_forms_the_jvm_writes(self):
        # PropertiesLauncher invokes the main by reflection, so staging writes "Caused by: " under an
        # InvocationTargetException; a main run directly writes the first form.
        for prefix in ['Exception in thread "main" ', "Caused by: "]:
            for main, code in [("smoke", "CACHED_SNAPSHOT"), ("canonical ingest", "OPERATIONS_TARGET_NOT_CONFIRMED"),
                               ("forecast smoke", "NO_VERIFIED_KTO_MAPPING"), ("demo refresh", "PLACE_FAILED"),
                               ("smoke", "KTO_INTERNAL_FAILURE (NullPointerException)"),
                               ("demo refresh", "UNEXPECTED_FAILURE (CompletionException)")]:
                line = f"{prefix}java.lang.IllegalStateException: KTO {main} failed: {code}"
                with self.subTest(line=line):
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
                     # Each passed before (Codex review of #381): a free prefix before the failure, an invented
                     # code, a refusal reason that is not one of the parser's messages.
                     "serviceKey=abc123 Exception: KTO smoke failed: KTO_TRANSPORT_FAILED",
                     smoke + " title=Gyeongbokgung Palace Exception: KTO smoke failed: KTO_TRANSPORT_FAILED",
                     "Caused by: java.lang.IllegalStateException: KTO smoke failed: PALACE_NAME",
                     "Caused by: java.lang.IllegalStateException: KTO smoke failed: KTO_TRANSPORT_FAILED (a b)",
                     "at io.nullnull.Main Caused by: java.lang.IllegalStateException: KTO smoke failed: PLACE_FAILED",
                     "KTO_DEMO_REFRESH_PLACE mode=detail contentId=126508 contentTypeId=12 status=FAILED "
                     "failure=PALACE_NAME",
                     "KTO_DEMO_REFRESH_REFUSED reason=key:ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                     "KTO_INTRO_PROBE_RESULT verdict=OBSERVED fields=title,addr1"]:
            with self.subTest(line=line[:60]):
                self.assertFalse(ops.OPS_LOG_LINE.match(line), line)


if __name__ == "__main__":
    unittest.main()
