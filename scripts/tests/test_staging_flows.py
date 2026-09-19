"""scripts/aws/staging-flows.mjs against a local stub API: the base flow CD runs, and the opt-in steps.

No network beyond 127.0.0.1 and no AWS. The stub answers in the contract's shapes (docs/api/openapi.yaml) for the
operations the script calls. It keeps a trip's version and ETag, refuses request bodies the closed schemas refuse,
answers Location the way OptimizationController does (relative to the context path), ranks proposals the way the
policy does (quietest open day first), and moves the one item when a decision or a revert says so - so the script's
before/after reads have something real to disagree with, and each knob below breaks exactly one thing.
"""

from __future__ import annotations

import json
import re
import subprocess
import threading
import unittest
import uuid
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[2]
FLOWS = ROOT / "scripts" / "aws" / "staging-flows.mjs"
# Far ahead, so the script's "not in the past (KST)" rule never trips. ITEM_DAY is busy, BETTER_DAY quiet, and the
# day between them (MIDDLE_DAY) sits in the trip's range too.
ITEM_DAY, MIDDLE_DAY, BETTER_DAY = "2099-03-02", "2099-03-03", "2099-03-04"
PLACE_ID = "01a0b825-4f15-7e7b-b30c-87cf71861c9c"
UUID_PATH = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
CREATE_KEYS = {"scope", "targetItemId", "inputTripVersion", "includeCandidates", "objective"}
DECISION_KEYS = {"proposalId", "decision"}


def place(name, source="KTO_KOR_SERVICE_2"):
    """A PlaceSummary as the contract closes it: provenance is sourceAttribution, null for no external source."""
    attribution = None if source is None else {
        "source": source, "sourceDisplayName": "한국관광공사", "sourceRegistryVersion": 4,
        "attribution": "출처: ⓒ한국관광공사", "officialUrl": None, "licenseUrl": None, "license": None}
    return {"id": PLACE_ID, "name": name, "categoryCode": "A0201", "regionCode": "1", "sourceAttribution": attribution}


def kst_day(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(timezone(timedelta(hours=9))).date()


class StubApi:
    """Mutable server state plus the knobs a test turns. Defaults: every prerequisite met, a well-formed server."""

    def __init__(self, **knobs):
        self.capability = "READY"
        self.search_status = 200
        self.search_items = [place("경복궁")]
        self.forecast_state = "FORECAST"
        self.values = {ITEM_DAY: 90.0, MIDDLE_DAY: 70.0, BETTER_DAY: 40.0}
        self.hours: dict[str, str] = {}  # date -> CLOSED | OPENING_HOURS_UNKNOWN; absent means open
        self.run_outcome = "READY"  # READY, STUCK, EXPIRED, or FAILED:<code>
        self.proposal_breaker = None  # damages the proposals a READY run carries
        self.bump_before_decision = False
        self.keep_moves = False
        self.revert_restores = True
        self.foreign_origin_status = 403
        self.edge_open = False  # what an anonymous /api/v1/health/live gets: the API (open) or the gate's 503
        self.evaluator_unanswered = False  # getCandidateTripMatches' fallback when apps/ai did not answer
        self.apply_moves = True
        self.apply_bumps_version = True
        self.keep_etag_offset = 0
        self.revert_window_hours = 24
        self.keep_run_status = "KEPT"
        self.reverted_availability = "REVERTED"
        for name, value in knobs.items():
            assert hasattr(self, name), name
            setattr(self, name, value)
        self.requests: list[tuple[str, str]] = []
        self.session_live = False
        self.trips: dict[str, dict] = {}
        self.runs: dict[str, dict] = {}
        self.decisions: dict[str, dict] = {}
        self.lock = threading.Lock()

    def days(self, trip):
        day, end, out = date.fromisoformat(trip["startDate"]), date.fromisoformat(trip["endDate"]), []
        while day <= end:
            out.append(day.isoformat())
            day += timedelta(days=1)
        return out

    def detail(self, trip):
        return {"id": trip["id"], "title": trip["title"], "startDate": trip["startDate"], "endDate": trip["endDate"],
                "timezone": trip["timezone"], "version": trip["version"],
                "days": [{"date": d, "items": [dict(i) for i in trip["items"] if i["date"] == d]} for d in self.days(trip)]}

    def proposals(self, run):
        """Policy-shaped: open days other than the item's, relief over 25, quietest first, then earliest, at most 3."""
        trip = self.trips[run["tripId"]]
        item = next(i for i in trip["items"] if i["id"] == run["itemId"])
        before = self.values.get(item["date"])
        ranked = sorted((self.values[d], d) for d in self.days(trip)
                        if d != item["date"] and d in self.values and d not in self.hours and before - self.values[d] > 25)
        return [{"id": str(uuid.uuid4()), "rank": rank, "summary": "move",
                 "changes": [{"operation": "MOVE", "itemId": item["id"],
                              "before": {"placeId": item["placeId"], "date": item["date"], "position": 0},
                              "after": {"placeId": item["placeId"], "date": day, "position": 0}}],
                 "metrics": {"comparisonEligible": True, "crowdDelta": value - before, "crowdComparison": None},
                 "validation": {"allConstraintsPreserved": True, "checks": []},
                 "dataProvenance": [{"source": "KTO_CONCENTRATION_FORECAST"}]}
                for rank, (value, day) in enumerate(ranked[:3], start=1)]


class Handler(BaseHTTPRequestHandler):
    server_version = "stub"

    def log_message(self, *args):
        pass

    @property
    def api(self) -> StubApi:
        return self.server.api  # type: ignore[attr-defined]

    def send(self, status, body=None, headers=None):
        payload = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        if body is not None:
            self.send_header("content-type", "application/problem+json" if status >= 400 else "application/json")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header("content-length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def body(self):
        length = int(self.headers.get("content-length") or 0)
        return json.loads(self.rfile.read(length)) if length else None

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def do_PATCH(self):
        self.route("PATCH")

    def do_DELETE(self):
        self.route("DELETE")

    @staticmethod
    def run_view(run):
        view = {k: v for k, v in run.items() if k not in ("polls", "itemId")}
        view.setdefault("revertAvailability", "NOT_APPLICABLE")
        return view

    def advance(self, run):
        """QUEUED, then RUNNING, then the configured outcome: two polls, as a real worker needs a moment."""
        api = self.api
        if run["status"] not in ("QUEUED", "RUNNING"):
            return
        run["polls"] += 1
        if run["polls"] == 1 or api.run_outcome == "STUCK":
            run["status"] = "RUNNING"
            return
        if api.run_outcome == "EXPIRED":
            run["status"] = "EXPIRED"
        elif api.run_outcome.startswith("FAILED:"):
            run["status"] = "FAILED"
            run["failure"] = {"code": api.run_outcome.split(":", 1)[1], "message": "stub", "retryable": False}
        else:
            run["proposals"] = api.proposals(run)
            if api.proposal_breaker:
                api.proposal_breaker(run["proposals"])
            run["status"] = "READY"
            if api.bump_before_decision:
                api.trips[run["tripId"]]["version"] += 1

    def route(self, method):
        api = self.api
        url = urlparse(self.path)
        path = url.path
        with api.lock:
            api.requests.append((method, path))
        body = self.body() if method in ("POST", "PATCH") else None
        if (method, path) == ("GET", "/api/v1/health/ready"):
            return self.send(200, {"status": "READY", "checks": [{"name": "database", "status": "READY"},
                                                                {"name": "recommendation", "status": "READY"}]})
        if (method, path) == ("GET", "/api/v1/health/live") and not self.headers.get("cookie"):
            if api.edge_open:
                return self.send(200, {"status": "UP"})
            payload = json.dumps({"status": 503, "title": "Staging verification pending"}).encode()
            self.send_response(503)
            self.send_header("content-type", "application/problem+json")
            self.send_header("content-length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return None
        if (method, path) == ("POST", "/api/v1/demo/sessions"):
            if self.headers.get("origin") == "https://attacker.invalid":
                return self.send(api.foreign_origin_status, {"code": "FORBIDDEN"})
            api.session_live = True
            return self.send(201, {"csrfToken": "c" * 43}, {
                "set-cookie": "__Host-nullnull_session=s1; Secure; HttpOnly; Path=/; SameSite=Lax",
                "cache-control": "private, no-store"})
        if not (api.session_live and "__Host-nullnull_session=s1" in (self.headers.get("cookie") or "")):
            return self.send(401, {"code": "UNAUTHENTICATED"})
        if (method, path) == ("GET", "/api/v1/me"):
            return self.send(200, {"kind": "ANONYMOUS"})
        if (method, path) == ("GET", "/api/v1/demo/readiness"):
            return self.send(200, {"overall": "DEGRADED", "checkedAt": "2099-01-01T00:00:00Z",
                                   "capabilities": [{"name": "optimization", "status": api.capability}]})
        if (method, path) == ("DELETE", "/api/v1/session"):
            api.session_live = False
            return self.send(202, {})
        # searchPlaces declares only sessionCookie; every other unsafe operation here also declares csrfToken.
        if method != "GET" and path != "/api/v1/places/search" and not self.headers.get("x-csrf-token"):
            return self.send(403, {"code": "CSRF_TOKEN_REQUIRED"})
        if (method, path) == ("POST", "/api/v1/trips"):
            trip = {"id": str(uuid.uuid4()), "title": body.get("title") or "trip", "startDate": body["startDate"],
                    "endDate": body["endDate"], "timezone": body["timezone"], "version": 1, "items": []}
            api.trips[trip["id"]] = trip
            return self.send(201, api.detail(trip), {"etag": '"1"'})
        if (method, path) == ("POST", "/api/v1/places/search"):
            if api.search_status == 503:
                return self.send(503, {"code": "SOURCE_UNAVAILABLE"})
            return self.send(200, {"items": api.search_items, "page": {"nextCursor": None}})
        if method == "GET" and re.fullmatch(r"/api/v1/places/[^/]+/crowd-forecast", path):
            query = parse_qs(url.query)
            if not query.get("from") or not query.get("to"):
                return self.send(400, {"code": "VALIDATION_FAILED"})
            start, end = kst_day(query["from"][0]), kst_day(query["to"][0])
            points = [{"state": "FORECAST", "value": value, "label": "KTO",
                       "provenance": {"targetAt": f"{day}T00:00:00+09:00"}}
                      for day, value in api.values.items() if start <= date.fromisoformat(day) < end]
            return self.send(200, {"placeId": path.split("/")[4], "state": api.forecast_state,
                                   "points": points if api.forecast_state == "FORECAST" else []})
        match = re.fullmatch(r"/api/v1/trips/([^/]+)/candidates/([^/]+)/matches", path)
        if match and method == "GET":
            trip = api.trips[match.group(1)]
            if api.evaluator_unanswered:
                return self.send(200, {"candidateId": match.group(2), "state": "UNKNOWN", "slots": []})
            slots = [{"date": d, "suggestedTime": None, "eligible": d not in api.hours, "reasonCode": api.hours.get(d)}
                     for d in api.days(trip)]
            return self.send(200, {"candidateId": match.group(2), "state": "SIMILAR", "slots": slots})
        match = re.fullmatch(r"/api/v1/trips/([^/]+)(/candidates|/items|/optimizations)?", path)
        if match:
            trip = api.trips.get(match.group(1))
            if trip is None:
                return self.send(404, {"code": "NOT_FOUND"})
            etag = f'"{trip["version"]}"'
            if method == "GET":
                return self.send(200, api.detail(trip), {"etag": etag})
            if match.group(2) == "/candidates":
                return self.send(201, {"candidate": {"id": str(uuid.uuid4()), "placeId": body["placeId"]},
                                       "duplicate": False, "tripScheduleChanged": False})
            if self.headers.get("if-match") != etag:
                return self.send(409, {"code": "TRIP_CHANGED"})
            if method == "PATCH":
                trip["title"] = body["title"]
                trip["version"] += 1
                return self.send(200, api.detail(trip), {"etag": f'"{trip["version"]}"'})
            if method == "DELETE":
                del api.trips[trip["id"]]
                return self.send(204)
            if match.group(2) == "/items":
                item = {"id": str(uuid.uuid4()), "placeId": body["placeId"], "date": body["date"], "position": body["position"]}
                trip["items"].append(item)
                trip["version"] += 1
                return self.send(201, {"trip": api.detail(trip), "changedItemIds": [item["id"]]}, {"etag": f'"{trip["version"]}"'})
            if match.group(2) == "/optimizations":
                if set(body) - CREATE_KEYS or body.get("scope") != "ITEM" or body.get("inputTripVersion") != trip["version"]:
                    return self.send(400, {"code": "INVALID_REQUEST"})
                run = {"id": str(uuid.uuid4()), "tripId": trip["id"], "status": "QUEUED", "polls": 0,
                       "inputTripVersion": trip["version"], "itemId": body["targetItemId"], "proposals": [],
                       "decisions": [], "failure": None}
                api.runs[run["id"]] = run
                # OptimizationController answers relative to the context path, without /api/v1.
                return self.send(202, self.run_view(run), {"location": f"/optimizations/{run['id']}"})
        match = re.fullmatch(r"/api/v1/optimizations/([^/]+)(/decisions)?", path)
        if match:
            run = api.runs.get(match.group(1))
            if run is None:
                return self.send(404, {"code": "NOT_FOUND"})
            if method == "GET":
                self.advance(run)
                return self.send(200, self.run_view(run), {"retry-after": "1"} if run["status"] in ("QUEUED", "RUNNING") else {})
            trip = api.trips[run["tripId"]]
            if set(body) - DECISION_KEYS:
                return self.send(400, {"code": "INVALID_REQUEST"})
            if self.headers.get("if-match") != f'"{trip["version"]}"' or run["status"] != "READY":
                return self.send(409, {"code": "TRIP_CHANGED"})
            proposal = next(p for p in run["proposals"] if p["id"] == body["proposalId"])
            decided = datetime(2099, 1, 1, tzinfo=timezone.utc)
            decision = {"id": str(uuid.uuid4()), "runId": run["id"], "proposalId": proposal["id"],
                        "decision": body["decision"], "decidedAt": decided.isoformat().replace("+00:00", "Z")}
            item = next(i for i in trip["items"] if i["id"] == run["itemId"])
            decided_against = trip["version"]
            if body["decision"] == "KEEP":
                run["status"] = api.keep_run_status
                if api.keep_moves:
                    item["date"] = proposal["changes"][0]["after"]["date"]
                    trip["version"] += 1
                etag_out = f'"{decided_against + api.keep_etag_offset}"'
            else:
                if api.apply_moves:
                    item["date"] = proposal["changes"][0]["after"]["date"]
                if api.apply_bumps_version:
                    trip["version"] += 1
                decision.update({"resultingTripVersion": trip["version"], "beforeRevisionId": str(uuid.uuid4()),
                                 "afterRevisionId": str(uuid.uuid4()),
                                 "revertUntil": (decided + timedelta(hours=api.revert_window_hours)).isoformat().replace("+00:00", "Z")})
                run["status"] = "APPLIED"
                run["revertAvailability"] = "AVAILABLE"
                etag_out = f'"{trip["version"]}"'
            run["decisions"].append(decision)
            api.decisions[decision["id"]] = decision
            return self.send(200, decision, {"etag": etag_out})
        match = re.fullmatch(r"/api/v1/optimization-decisions/([^/]+)/revert", path)
        if match and method == "POST":
            applied = api.decisions[match.group(1)]
            run = api.runs[applied["runId"]]
            trip = api.trips[run["tripId"]]
            if self.headers.get("if-match") != f'"{trip["version"]}"':
                return self.send(409, {"code": "TRIP_CHANGED"})
            if api.revert_restores:
                item = next(i for i in trip["items"] if i["id"] == run["itemId"])
                proposal = next(p for p in run["proposals"] if p["id"] == applied["proposalId"])
                item["date"] = proposal["changes"][0]["before"]["date"]
            trip["version"] += 1
            revert = {"id": str(uuid.uuid4()), "runId": run["id"], "proposalId": applied["proposalId"],
                      "decision": "REVERT", "resultingTripVersion": trip["version"], "revertedDecisionId": applied["id"],
                      "beforeRevisionId": str(uuid.uuid4()), "afterRevisionId": str(uuid.uuid4()),
                      "decidedAt": "2099-01-01T01:00:00Z"}
            run["decisions"].append(revert)
            run["status"] = "REVERTED"
            run["revertAvailability"] = api.reverted_availability
            return self.send(200, revert, {"etag": f'"{trip["version"]}"'})
        return self.send(404, {"code": "NOT_FOUND"})


class StagingFlowsScript(unittest.TestCase):
    def run_flows(self, api: StubApi, *extra: str):
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.api = api  # type: ignore[attr-defined]
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}"
            result = subprocess.run(["node", str(FLOWS), "--url", url, "--local", *extra],
                                    capture_output=True, text=True, timeout=120, check=False)
        finally:
            server.shutdown()
            server.server_close()
        return result, api.requests

    def optimize(self, *extra: str):
        return ["--optimize-item", "--item-day", ITEM_DAY, "--better-day", BETTER_DAY, "--optimize-timeout-seconds", "5", *extra]

    def assert_outcome(self, result, code, *lines):
        self.assertEqual(code, result.returncode, result.stdout + result.stderr)
        for line in lines:
            self.assertIn(line, result.stdout)

    def test_without_a_flag_the_cd_flow_sends_and_prints_what_it_did_before(self):
        # CD passes only --url. The whole request sequence is pinned, not just "no /optimizations": a step that
        # leaked out of its flag and stopped early would add only a second readiness read and a second search, and
        # a path filter would stay green (measured: it did). The stub advertises every prerequisite, so a leak goes on.
        result, requests = self.run_flows(StubApi(search_items=[]))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertRegex(result.stdout.strip().splitlines()[-1], r"^staging_flows=pass checks=18 failures=0$")
        self.assertNotIn("not_run", result.stdout)
        self.assertNotIn("NOT-RUN", result.stdout)
        self.assertEqual([
            ("GET", "/api/v1/health/ready"), ("POST", "/api/v1/demo/sessions"), ("POST", "/api/v1/demo/sessions"),
            ("GET", "/api/v1/me"), ("GET", "/api/v1/demo/readiness"), ("POST", "/api/v1/trips"),
            ("POST", "/api/v1/trips"), ("GET", "/api/v1/trips/{id}"), ("PATCH", "/api/v1/trips/{id}"),
            ("PATCH", "/api/v1/trips/{id}"), ("POST", "/api/v1/places/search"), ("DELETE", "/api/v1/trips/{id}"),
            ("DELETE", "/api/v1/session"), ("GET", "/api/v1/me"),
        ], [(m, UUID_PATH.sub("{id}", p)) for m, p in requests])

    def test_with_the_catalog_open_the_cd_flow_reads_provenance_where_the_contract_puts_it(self):
        # The next release opens the catalog (infra/staging.config.json), so CD takes this branch for the first time.
        # PlaceSummary carries its provenance as sourceAttribution (eccbdca aligned the check with the contract).
        result, _ = self.run_flows(StubApi())
        self.assert_outcome(result, 0, "pass catalog.provenance sourceAttribution=present", "pass item.scheduled")
        result, _ = self.run_flows(StubApi(search_items=[place("경복궁", source=None)]))
        self.assert_outcome(result, 1, "FAIL catalog.provenance sourceAttribution=missing")

    def test_by_default_one_run_is_kept_and_another_applied_then_reverted(self):
        api = StubApi()
        result, requests = self.run_flows(api, *self.optimize())
        self.assert_outcome(result, 0, *[f"pass optimization.{line}" for line in [
            "item-scheduled", "keep.queued", "keep.ready", "keep.proposal-shape", f"keep.better-day-first first={BETTER_DAY}",
            "keep.unchanged-before-decision", "keep.decided", "keep.no-new-version", "keep.left-trip", "keep.run-kept",
            "apply.ready", "apply.unchanged-before-decision", "apply.new-version", "apply.revert-window",
            "apply.moved-item", "apply.reverted", "apply.restored-item", "apply.run-reverted", "trip-deleted"]])
        self.assertRegex(result.stdout, r"(?m)^staging_flows=pass checks=\d+ failures=0 not_run=0$")
        self.assertLess(result.stdout.index("keep.run-kept"), result.stdout.index("apply.queued"))
        self.assertEqual({}, api.trips, "every trip the flows made is deleted")
        polled = {p for m, p in requests if m == "GET" and "optimizations" in p}
        self.assertTrue(polled and all(p.startswith("/api/v1/optimizations/") for p in polled), polled)

    def test_one_decision_alone_can_be_asked_for(self):
        result, _ = self.run_flows(StubApi(), *self.optimize("--decision", "keep"))
        self.assert_outcome(result, 0, "pass optimization.keep.run-kept")
        self.assertNotIn("optimization.apply", result.stdout)
        result, _ = self.run_flows(StubApi(), *self.optimize("--decision", "apply"))
        self.assert_outcome(result, 0, "pass optimization.apply.run-reverted")
        self.assertNotIn("optimization.keep", result.stdout)

    def test_a_missing_prerequisite_is_not_run_and_never_a_pass(self):
        cases = [
            (dict(capability="UNAVAILABLE"), "NOT-RUN optimization reason=capability-off capability=UNAVAILABLE", False),
            (dict(search_status=503), "NOT-RUN optimization reason=catalog-closed code=SOURCE_UNAVAILABLE", False),
            (dict(search_items=[place("경복궁 광화문")]), "NOT-RUN optimization reason=place-not-found items=1", False),
            (dict(forecast_state="UNAVAILABLE"), "NOT-RUN optimization reason=forecast-missing state=UNAVAILABLE", False),
            (dict(forecast_state="STALE"), "NOT-RUN optimization reason=forecast-stale state=STALE", False),
            (dict(values={ITEM_DAY: 90.0}), "NOT-RUN optimization reason=forecast-no-coverage", False),
            (dict(values={ITEM_DAY: 60.0, MIDDLE_DAY: 50.0, BETTER_DAY: 35.0}),
             "NOT-RUN optimization reason=forecast-delta-too-small delta=25", False),
            (dict(hours={BETTER_DAY: "CLOSED"}), "NOT-RUN optimization reason=better-day-closed hours=CLOSED", True),
            (dict(hours={BETTER_DAY: "OPENING_HOURS_UNKNOWN"}),
             "NOT-RUN optimization reason=better-day-hours-unknown hours=UNKNOWN", True),
            (dict(values={ITEM_DAY: 90.0, MIDDLE_DAY: 30.0, BETTER_DAY: 40.0}),
             f"NOT-RUN optimization reason=better-day-not-the-quietest-open-day quietest={MIDDLE_DAY}", True),
            (dict(run_outcome="FAILED:DATA_INSUFFICIENT"),
             "NOT-RUN optimization.keep.decision reason=run-failed code=DATA_INSUFFICIENT", True),
        ]
        for knobs, line, made_a_trip in cases:
            with self.subTest(line=line):
                api = StubApi(**knobs)
                result, requests = self.run_flows(api, *self.optimize())
                self.assert_outcome(result, 3, line, "staging_flows_counts_as_pass=false")
                self.assertRegex(result.stdout, r"(?m)^staging_flows=incomplete checks=\d+ failures=0 not_run=1$")
                self.assertEqual({}, api.trips, "a step that stops still deletes its trip")
                # The base flow creates its trip (and the csrf probe tries one) before the step begins.
                step_trips = [p for m, p in requests if (m, p) == ("POST", "/api/v1/trips")][2:]
                self.assertEqual(made_a_trip, bool(step_trips))
                if "DATA_INSUFFICIENT" not in line:
                    self.assertFalse([p for m, p in requests if m == "POST" and p.endswith("/optimizations")], requests)

    def test_a_run_a_one_stop_trip_with_its_prerequisites_cannot_produce_is_a_failure(self):
        for outcome, line in [("FAILED:INTERNAL_ERROR", "status=FAILED code=INTERNAL_ERROR"),
                              ("FAILED:ROUTE_UNAVAILABLE", "status=FAILED code=ROUTE_UNAVAILABLE"),
                              ("FAILED:RECOMMENDATION_UNAVAILABLE", "status=FAILED code=RECOMMENDATION_UNAVAILABLE"),
                              ("FAILED:NO_IMPROVEMENT", "status=FAILED code=NO_IMPROVEMENT"),
                              ("EXPIRED", "status=EXPIRED"), ("STUCK", "status=RUNNING")]:
            with self.subTest(outcome=outcome):
                api = StubApi(run_outcome=outcome)
                result, _ = self.run_flows(api, *self.optimize("--optimize-timeout-seconds", "2"))
                self.assert_outcome(result, 1, f"FAIL optimization.keep.ready {line}")
                self.assertRegex(result.stdout, r"(?m)^staging_flows=failed ")
                self.assertEqual({}, api.trips)

    def test_a_proposal_that_breaks_its_contract_fails_the_shape_check(self):
        def breaker(field):
            def damage(proposals):
                p = proposals[0]
                if field == "crowdDelta":
                    p["metrics"]["crowdDelta"] = 5.0
                elif field == "comparisonEligible":
                    p["metrics"]["comparisonEligible"] = False
                elif field == "before":
                    p["changes"][0]["before"]["date"] = MIDDLE_DAY
                elif field == "twoChanges":
                    p["changes"].append(dict(p["changes"][0]))
                elif field == "placeId":
                    p["changes"][0]["after"]["placeId"] = str(uuid.uuid4())
                elif field == "provenance":
                    p["dataProvenance"] = []
                elif field == "constraints":
                    p["validation"]["allConstraintsPreserved"] = False
            return damage
        for field in ["crowdDelta", "comparisonEligible", "before", "twoChanges", "placeId", "provenance", "constraints"]:
            with self.subTest(field=field):
                result, _ = self.run_flows(StubApi(proposal_breaker=breaker(field)), *self.optimize("--decision", "keep"))
                self.assert_outcome(result, 1, "FAIL optimization.keep.proposal-shape")

    def test_a_server_that_misbehaves_around_the_decision_fails(self):
        def middle_first(proposals):
            proposals[0]["changes"][0]["after"]["date"] = MIDDLE_DAY
        for knobs, mode, line in [
            (dict(proposal_breaker=middle_first), "keep", f"FAIL optimization.keep.better-day-first first={MIDDLE_DAY}"),
            (dict(bump_before_decision=True), "keep", "FAIL optimization.keep.unchanged-before-decision"),
            (dict(keep_moves=True), "keep", "FAIL optimization.keep.left-trip"),
            (dict(revert_restores=False), "apply", "FAIL optimization.apply.restored-item"),
        ]:
            with self.subTest(line=line):
                api = StubApi(**knobs)
                result, _ = self.run_flows(api, *self.optimize("--decision", mode))
                self.assert_outcome(result, 1, line)
                self.assertEqual({}, api.trips)

    def test_every_effect_the_decision_claims_is_checked_against_the_server(self):
        for knobs, mode, line in [
            (dict(apply_moves=False), "apply", "FAIL optimization.apply.moved-item"),
            (dict(apply_bumps_version=False), "apply", "FAIL optimization.apply.new-version"),
            (dict(revert_window_hours=23), "apply", "FAIL optimization.apply.revert-window"),
            (dict(reverted_availability="AVAILABLE"), "apply", "FAIL optimization.apply.run-reverted"),
            (dict(keep_etag_offset=1), "keep", "FAIL optimization.keep.no-new-version"),
            (dict(keep_run_status="READY"), "keep", "FAIL optimization.keep.run-kept"),
        ]:
            with self.subTest(line=line):
                api = StubApi(**knobs)
                result, _ = self.run_flows(api, *self.optimize("--decision", mode))
                self.assert_outcome(result, 1, line)
                self.assertEqual({}, api.trips)

    def test_an_evaluator_that_did_not_answer_is_a_failure_not_unknown_hours(self):
        # Spring answers getCandidateTripMatches with {UNKNOWN, []} when apps/ai did not answer; unknown hours would
        # still come back as one slot per day. Reading the fallback as unknown hours sends the operator to re-import.
        for step in (self.optimize(), ["--survey"]):
            with self.subTest(step=step[0]):
                api = StubApi(evaluator_unanswered=True)
                result, _ = self.run_flows(api, *step)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                self.assertRegex(result.stdout, r"FAIL (optimization|survey)\.hours-read status=200 state=UNKNOWN slots=0 days=\d+")
                self.assertEqual({}, api.trips)

    def test_the_edge_state_can_be_expected_open_once_judging_has_opened_it(self):
        result, requests = self.run_flows(StubApi(edge_open=True), "--expect-edge", "open")
        self.assert_outcome(result, 0, "pass edge.public-api-open status=200")
        self.assertIn(("GET", "/api/v1/health/live"), requests)
        result, _ = self.run_flows(StubApi(edge_open=False), "--expect-edge", "open")
        self.assert_outcome(result, 1, "FAIL edge.public-api-open status=503")
        result, _ = self.run_flows(StubApi(edge_open=False), "--expect-edge", "closed")
        self.assert_outcome(result, 0, "pass edge.public-api-closed status=503")
        result, _ = self.run_flows(StubApi(edge_open=True), "--expect-edge", "closed")
        self.assert_outcome(result, 1, "FAIL edge.public-api-closed status=200")
        result, requests = self.run_flows(StubApi(), "--expect-edge", "ajar")
        self.assertEqual(2, result.returncode)
        self.assertIn("staging_flows=failed reason=expect-edge-must-be-open-or-closed", result.stderr)
        self.assertEqual([], requests)

    def test_a_failure_outranks_a_step_that_did_not_run(self):
        result, _ = self.run_flows(StubApi(foreign_origin_status=201, capability="UNAVAILABLE"), *self.optimize())
        self.assert_outcome(result, 1, "FAIL session.foreign-origin-refused", "NOT-RUN optimization reason=capability-off")
        self.assertRegex(result.stdout, r"(?m)^staging_flows=failed checks=\d+ failures=1 not_run=1$")
        self.assertNotIn("counts_as_pass", result.stdout)

    def test_the_survey_finds_the_busiest_and_the_quietest_open_days(self):
        today = datetime.now(timezone(timedelta(hours=9))).date()
        days = [(today + timedelta(days=n)).isoformat() for n in range(1, 30)]
        values = {d: 50.0 for d in days}
        values[days[3]], values[days[10]], values[days[12]] = 95.0, 20.0, 10.0
        api = StubApi(values=values, hours={days[12]: "CLOSED"})
        result, _ = self.run_flows(api, "--survey")
        self.assert_outcome(result, 0, f"info survey date={days[3]} value=95 hours=OPEN",
                            f"info survey date={days[12]} value=10 hours=CLOSED",
                            f"pass survey.pair-found item_day={days[3]} better_day={days[10]} delta=75")
        self.assertEqual(29, result.stdout.count("info survey date="))
        self.assertEqual({}, api.trips)
        result, _ = self.run_flows(StubApi(values={d: 50.0 for d in days}), "--survey")
        self.assert_outcome(result, 3, "NOT-RUN survey reason=no-open-pair-over-25 delta=0")

    def test_a_malformed_request_for_a_step_stops_before_any_request(self):
        for extra, reason in [
            (["--optimize-item", "--item-day", "2099-3-2", "--better-day", BETTER_DAY], "item-day-must-be-yyyy-mm-dd"),
            (["--optimize-item", "--item-day", ITEM_DAY, "--better-day", "2099-04-05"], "days-must-be-1-to-29-days-apart"),
            (["--optimize-item", "--item-day", ITEM_DAY, "--better-day", ITEM_DAY], "days-must-be-1-to-29-days-apart"),
            (["--optimize-item", "--item-day", "2020-01-01", "--better-day", "2020-01-02"], "days-must-not-be-in-the-past-kst"),
            (["--optimize-item", "--item-day", ITEM_DAY, "--better-day", BETTER_DAY, "--decision", "revert"],
             "decision-must-be-both-keep-or-apply"),
            (["--optimize-item", "--item-day", ITEM_DAY, "--better-day", BETTER_DAY, "--optimize-timeout-seconds", "0"],
             "optimize-timeout-seconds-must-be-1-to-900"),
            (["--survey", "--optimize-item", "--item-day", ITEM_DAY, "--better-day", BETTER_DAY],
             "survey-and-optimize-item-are-separate-runs"),
        ]:
            with self.subTest(reason=reason):
                result, requests = self.run_flows(StubApi(), *extra)
                self.assertEqual(2, result.returncode, result.stdout + result.stderr)
                self.assertIn(f"staging_flows=failed reason={reason}", result.stderr)
                self.assertEqual([], requests)


if __name__ == "__main__":
    unittest.main()
