"""A-044: the phrases the alarms quote belong to three files in two languages.

A CloudWatch metric filter can only match a phrase, and the phrase is written in TypeScript while the
line is printed by Java. Nothing in either language sees the other, so a rename on one side leaves a
filter that matches nothing - and a filter that matches nothing is indistinguishable from an incident
that never happened. `infra/test/staging.test.ts` proves the synthesized filters carry these phrases;
only this file proves the phrases are the ones the application prints.

The same asymmetry applies to the schedule's own constants: `FORECAST_MAIN` must be the class
`staging_operator.py` names for the same task, `FORECAST_DEMO_PLACES` must satisfy the input shape
that operator accepts, and the schedule's end must be that operator's EXPIRY rather than a second
date that drifts from it.
"""

from __future__ import annotations

import datetime as dt
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STAGING_TS = ROOT / "infra" / "src" / "staging.ts"
OPERATOR = ROOT / "scripts" / "aws" / "staging_operator.py"
JAVA = ROOT / "apps" / "api" / "src" / "main" / "java" / "io" / "nullnull"
OPS_ALARM = JAVA / "operations" / "application" / "OpsAlarm.java"
SOURCES_MIGRATION = ROOT / "apps" / "api" / "src" / "main" / "resources" / "db" / "migration" / "V007__sources.sql"
DEMO_REFRESH = JAVA / "catalog" / "infrastructure" / "kto" / "KtoDemoRefresh.java"
DEMO_COMMAND = JAVA / "catalog" / "infrastructure" / "kto" / "KtoDemoRefreshCommand.java"


def ts_const(name: str) -> str:
    """The value of `export const <name> = "..."` in staging.ts."""
    match = re.search(rf'export const {name} =\s*\n?\s*"([^"]*)"', STAGING_TS.read_text())
    assert match, f"{name} is not a string constant in staging.ts"
    return match.group(1)


def ts_string_list(name: str) -> list[str]:
    match = re.search(rf"export const {name} = \[(.*?)\];", STAGING_TS.read_text(), re.S)
    assert match, f"{name} is not a list constant in staging.ts"
    return re.findall(r'"([^"]+)"', match.group(1))


class OpsAlarmVocabulary(unittest.TestCase):
    def test_the_two_declarations_of_the_alarm_names_are_the_same_set(self):
        java = OPS_ALARM.read_text()
        enum = java[java.index("public enum Name {"): java.index("private final Level level;")]
        declared = set(re.findall(r"^\s{8}([A-Z][A-Z_]+)\(Level\.", enum, re.M))
        self.assertEqual(len(declared), 5, f"read the enum, not the javadoc: {sorted(declared)}")
        self.assertEqual(declared, set(ts_string_list("OPS_ALARM_NAMES")))

    def test_the_filter_quotes_the_phrase_the_java_renders(self):
        # phrase() is TOKEN + " name=" + name(); the filters are built from the same two halves.
        java = OPS_ALARM.read_text()
        self.assertIn('static final String TOKEN = "ops.alarm";', java)
        self.assertIn('return TOKEN + " name=" + name();', java)
        self.assertIn('`"ops.alarm name=${name}"`', STAGING_TS.read_text())


class ForecastPhrases(unittest.TestCase):
    def test_the_success_phrase_is_the_line_the_refresh_prints(self):
        java = DEMO_REFRESH.read_text()
        self.assertIn('"KTO_DEMO_REFRESH_DONE mode=" + name(mode)', java)
        # name(Mode) lower-cases it, so a filter quoting "mode=FORECAST" would match nothing, ever.
        self.assertIn("return mode.name().toLowerCase(Locale.ROOT);", java)
        self.assertIn("public enum Mode { FORECAST, DETAIL }", java)
        self.assertEqual(ts_const("FORECAST_DONE_PHRASE"), "KTO_DEMO_REFRESH_DONE mode=forecast")
        # The alarm's second term. The count is on the same line, so one event carries both.
        self.assertIn(' + " failed=" + count(Status.FAILED)', java)

    def test_the_failure_phrase_is_the_message_the_command_throws(self):
        prefix = re.search(r'PREFIX = "([^"]+)"', DEMO_COMMAND.read_text())
        assert prefix
        self.assertTrue(
            prefix.group(1).startswith(ts_const("DEMO_REFRESH_FAILED_PHRASE")),
            f'{prefix.group(1)!r} does not start with the quoted phrase',
        )

    def test_the_empty_refresh_terms_are_what_a_forecast_run_prints_and_a_detail_run_never_does(self):
        java = DEMO_REFRESH.read_text()
        tag, term = ts_const("FORECAST_EVIDENCE_TAG"), ts_const("FORECAST_EMPTY_TERM")
        self.assertIn(f'"{tag} contentId=" + place.contentId() + " " + evidence', java)
        # The two ways a forecast refresh stores nothing, both still REFRESHED with failed=0.
        self.assertIn('.map(set -> "coverage=" + set.points().size()', java)
        self.assertIn(f'.orElse("{term}")', java)
        # The filter is forecast-only because the detail evidence has no coverage at all.
        detail = java[java.index("private Outcome detail("): java.index("private Outcome forecast(")]
        self.assertNotIn("coverage=", detail)
        # The alarm quotes the INT-04 place, which has to be one the schedule actually refreshes.
        places = ts_const("FORECAST_DEMO_PLACES").split(",")
        self.assertTrue(any(p.startswith(ts_const("INT04_CONTENT_ID") + ":") for p in places), places)


def operator_expiry() -> dt.datetime:
    expiry = re.search(
        r"EXPIRY = dt\.datetime\((\d+), (\d+), (\d+), (\d+), (\d+), (\d+), tzinfo=dt\.timezone\.utc\)",
        OPERATOR.read_text(),
    )
    assert expiry, "EXPIRY is no longer a literal datetime in staging_operator.py"
    return dt.datetime(*(int(g) for g in expiry.groups()), tzinfo=dt.timezone.utc)


class ScheduleConstants(unittest.TestCase):
    def test_the_schedule_ends_when_the_operator_expires(self):
        end = re.search(r'FORECAST_SCHEDULE_END = new Date\("([^"]+)"\)', STAGING_TS.read_text())
        assert end
        self.assertEqual(
            dt.datetime.fromisoformat(end.group(1).replace("Z", "+00:00")), operator_expiry()
        )

    def test_the_seoul_live_collection_ends_when_the_operator_expires(self):
        # A-069 moved the end once and this pair had no witness: leaving the Java constant behind stops
        # the Seoul collection at the old date with every gate green, and nothing alarms (SNS unsubscribed).
        scheduler = JAVA / "live" / "infrastructure" / "SeoulLiveRefreshScheduler.java"
        end = re.search(r'JUDGING_END = Instant\.parse\("([^"]+)"\)', scheduler.read_text())
        assert end, "JUDGING_END is no longer a literal Instant in SeoulLiveRefreshScheduler.java"
        self.assertEqual(
            dt.datetime.fromisoformat(end.group(1).replace("Z", "+00:00")), operator_expiry()
        )

    def test_the_shell_expiry_date_is_the_operator_expiry_date(self):
        # common.sh spells the date twice, a default and the value it insists on; the shell scripts die
        # with unexpected-expiry-date when either differs from the other, and neither was held to EXPIRY.
        common = (ROOT / "scripts" / "aws" / "common.sh").read_text()
        default = re.search(r'NULLNULL_EXPIRY_DATE="\$\{NULLNULL_EXPIRY_DATE:-([0-9-]+)\}"', common)
        insisted = re.search(r'"\$NULLNULL_EXPIRY_DATE" == \'([0-9-]+)\' \]\] \|\| fail \'unexpected-expiry-date\'', common)
        assert default and insisted, "common.sh no longer spells the expiry date the way this test reads it"
        expected = operator_expiry().date().isoformat()
        self.assertEqual(default.group(1), expected)
        self.assertEqual(insisted.group(1), expected)
        at = re.search(r'NULLNULL_EXPIRY_AT="\$\{NULLNULL_EXPIRY_DATE\}T([0-9:]+)Z"', common)
        assert at, "common.sh no longer derives the end instant from the expiry date"
        self.assertEqual(at.group(1), operator_expiry().strftime("%H:%M:%S"))

    def test_a_writing_shell_script_stops_at_the_operator_end_instant(self):
        # The shell guard compared the UTC date only, so a writing script (restore-drill creates an RDS
        # instance) still ran for nine hours after the operator refused. Measured at the edge, with date and
        # aws faked on PATH; the guard never reaches a real account.
        import os, subprocess, tempfile
        end = operator_expiry()
        cases = [
            ((end - dt.timedelta(seconds=1)).strftime("%Y-%m-%dT%H:%M:%SZ"), True),
            (end.strftime("%Y-%m-%dT%H:%M:%SZ"), False),
            ((end + dt.timedelta(hours=5)).strftime("%Y-%m-%dT%H:%M:%SZ"), False),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp)
            (fake / "date").write_text('#!/bin/sh\necho "$FAKE_NOW"\n')
            (fake / "aws").write_text(
                '#!/bin/sh\necho "arn:aws:sts::111111111111:assumed-role/nullnull-stg-operator/test"\n')
            for tool in ("date", "aws"):
                (fake / tool).chmod(0o755)
            for now, allowed in cases:
                with self.subTest(now=now):
                    env = {k: v for k, v in os.environ.items() if not k.startswith("NULLNULL_")}
                    env.update({"PATH": f"{fake}:{env.get('PATH', '')}", "FAKE_NOW": now,
                                "NULLNULL_AWS_ACCOUNT_ID": "111111111111", "NULLNULL_AWS_AUTH": "profile",
                                "AWS_PROFILE": "test", "AWS_REGION": "ap-northeast-2"})
                    result = subprocess.run(
                        ["bash", "-c", f'source "{ROOT}/scripts/aws/common.sh"; assert_operator_contract; echo contract=ok'],
                        capture_output=True, text=True, env=env, check=False)
                    if allowed:
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.assertIn("contract=ok", result.stdout)
                    else:
                        self.assertNotEqual(0, result.returncode)
                        self.assertIn("reason=staging-expired", result.stderr)

    def test_the_scheduled_mains_are_the_ones_the_operator_runs_with_the_same_approvals(self):
        operator = OPERATOR.read_text()
        ts = STAGING_TS.read_text()
        for task, const, approval in (
            ("kto-demo-forecast", "FORECAST_MAIN", "NULLNULL_KTO_FORECAST_SMOKE_APPROVED"),
            ("kto-demo-detail", "DETAIL_MAIN", "NULLNULL_KTO_SMOKE_APPROVED"),
        ):
            with self.subTest(task=task):
                found = re.search(rf"'{task}': \('([^']+)',\s*\n?\s*'([^']+)'", operator)
                assert found, f"OPS_TASKS no longer spells {task} this way"
                self.assertEqual(found.group(1), ts_const(const))
                self.assertEqual(found.group(2), approval)
                # The schedule passes that same variable, next to that same main.
                call = re.search(rf'\n\s*{const},\n\s*"([A-Z_]+)",', ts)
                assert call, f"no schedule is built from {const}"
                self.assertEqual(call.group(1), approval)

    def assert_each_run_renews_what_lapses_before_the_next(self, name, rate, renew, life):
        """Seconds, all three. A run renews what lapses within `renew` of its own start (KtoDemoRefresh).

        Whatever the run finds must still be fresh when the NEXT run fetches, or the evidence has a gap
        (a detail gap leaves the forecast NO_VERIFIED_KTO_MAPPING; a forecast gap leaves optimization
        without evidence). The next run fetches `rate` after this one's tick, plus however late its
        call comes after its own tick, so `renew - rate` is the margin that lateness has to stay under.
        Lateness is the scheduler's delivery, which maxEventAge bounds (it retries within that age and
        drops an older invocation rather than deliver it late), then the Fargate start and the places
        ahead on the list, which nothing here bounds and which take minutes. So this holds the margin
        against the delivery bound only: an hour and some minutes fits inside a six-hour margin.

        The rule this replaces was `rate + renew <= life` for detail (5 + 2 = 7) and `rate == renew`
        for the forecast (12 = 12), and both are the boundary itself: the snapshot the last run fetched
        lapses exactly `renew` after the next run's tick, so whether that run renewed it came down to
        which of the two runs started faster after its tick (#361). Staging showed the forecast side
        doing exactly that - refreshed, then current, every other run.
        """
        max_event_age = re.search(r"maxEventAge: cdk\.Duration\.hours\((\d+)\)", STAGING_TS.read_text())
        assert max_event_age, "the schedule's maxEventAge is no longer a literal hour count"
        delivery_bound = int(max_event_age.group(1)) * 3600
        self.assertGreater(
            renew - rate, delivery_bound,
            f"{name}: the margin a run's lateness must stay under is no longer than the scheduler's delivery bound",
        )
        # And a rerun right after a fetch leaves it alone, so a repeated schedule costs no calls.
        self.assertLess(renew, life, f"{name}: a just-fetched snapshot would be renewed again at once")

    def test_every_detail_run_renews_what_would_lapse_before_the_next(self):
        # KTO_KOR_SERVICE_2's stale_after_seconds, the life a forecast request's mapping has.
        life = re.search(r"'pending-c2', 1, (\d+),", SOURCES_MIGRATION.read_text())
        assert life, "the KorService2 registry row no longer reads this way"
        renew = re.search(
            r"DETAIL_RENEW_BEFORE = Duration\.ofDays\((\d+)\)", DEMO_REFRESH.read_text()
        )
        assert renew, "DETAIL_RENEW_BEFORE is no longer a literal day count"
        rate = re.search(r"DETAIL_SCHEDULE_RATE_DAYS = (\d+)", STAGING_TS.read_text())
        assert rate
        self.assert_each_run_renews_what_lapses_before_the_next(
            "detail", int(rate.group(1)) * 86400, int(renew.group(1)) * 86400, int(life.group(1))
        )

    def test_every_forecast_run_renews_what_would_lapse_before_the_next(self):
        # KTO_CONCENTRATION_FORECAST's stale_after_seconds, the life of a forecast set.
        life = re.search(
            r"\('KTO_CONCENTRATION_FORECAST',.*?'pending-c2', 1, (\d+),", SOURCES_MIGRATION.read_text(), re.S
        )
        assert life, "the forecast registry row no longer reads this way"
        renew = re.search(
            r"FORECAST_RENEW_BEFORE = Duration\.ofHours\((\d+)\)", DEMO_REFRESH.read_text()
        )
        assert renew, "FORECAST_RENEW_BEFORE is no longer a literal hour count"
        rate = re.search(r"FORECAST_SCHEDULE_RATE_HOURS = (\d+)", STAGING_TS.read_text())
        assert rate
        self.assert_each_run_renews_what_lapses_before_the_next(
            "forecast", int(rate.group(1)) * 3600, int(renew.group(1)) * 3600, int(life.group(1))
        )

    def test_the_demo_places_are_a_shape_the_operator_would_accept(self):
        places = re.search(r"'places': r'([^']+)'", OPERATOR.read_text())
        assert places
        value = ts_const("FORECAST_DEMO_PLACES")
        self.assertRegex(value, f"^{places.group(1)}$")
        self.assertEqual(len(set(value.split(","))), len(value.split(",")), "duplicate-places")


class DeployRoleCanCreateTheSchedule(unittest.TestCase):
    """The template can declare a resource the deploy role may not create.

    That failure is invisible to every check that stops at synthesis: `npm run check` builds the
    template offline and the gate never calls AWS, so the first sign would be a CloudFormation
    rollback in the owner's own deploy. The policy and the stack are two files with no link, so the
    link is here.
    """

    def setUp(self):
        import json

        self.policy = json.loads((ROOT / "infra" / "iam" / "cfn-execution.json").read_text())
        self.ts = STAGING_TS.read_text()

    def allowed(self, action: str) -> list[dict]:
        found = []
        for statement in self.policy["Statement"]:
            actions = statement.get("Action", [])
            actions = [actions] if isinstance(actions, str) else actions
            if statement["Effect"] == "Allow" and action in actions:
                found.append(statement)
        return found

    def test_the_scheduler_actions_are_allowed_where_the_stack_declares_a_schedule(self):
        if "new scheduler.Schedule(" not in self.ts:
            self.skipTest("the stack declares no schedule")
        from fnmatch import fnmatch

        # Every schedule the stack builds, by the name the helper is given.
        names = re.findall(r'demoRefresh\(\s*"[^"]+",\s*"([^"]+)"', self.ts)
        # Every call site, counted independently of the pattern above (the definition reads
        # "demoRefresh = (", so it is not one of them).
        self.assertEqual(len(names), self.ts.count("demoRefresh("), "a schedule this test cannot name")
        self.assertTrue(names, "the schedules are unnamed, so no policy can name them either")
        for action in ("scheduler:CreateSchedule", "scheduler:UpdateSchedule", "scheduler:DeleteSchedule"):
            statements = self.allowed(action)
            self.assertTrue(statements, f"the deploy role cannot {action}")
            resources = [
                r
                for s in statements
                for r in ([s["Resource"]] if isinstance(s["Resource"], str) else s["Resource"])
            ]
            for name in names:
                target = f"arn:aws:scheduler:ap-northeast-2:${{Account}}:schedule/default/{name}"
                self.assertTrue(
                    any(fnmatch(target, pattern) for pattern in resources),
                    f"{action} is allowed, but not on {target}: {resources}",
                )

    def test_the_schedules_own_role_may_be_handed_to_the_scheduler(self):
        if "new scheduler.Schedule(" not in self.ts:
            self.skipTest("the stack declares no schedule")
        statements = self.allowed("iam:PassRole")
        services = [
            service
            for s in statements
            for service in (
                lambda v: [v] if isinstance(v, str) else v
            )(s.get("Condition", {}).get("StringEquals", {}).get("iam:PassedToService", []))
        ]
        self.assertIn("scheduler.amazonaws.com", services)


if __name__ == "__main__":
    unittest.main()
