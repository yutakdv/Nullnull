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


class ScheduleConstants(unittest.TestCase):
    def test_the_schedule_ends_when_the_operator_expires(self):
        expiry = re.search(
            r"EXPIRY = dt\.datetime\((\d+), (\d+), (\d+), (\d+), (\d+), (\d+), tzinfo=dt\.timezone\.utc\)",
            OPERATOR.read_text(),
        )
        assert expiry, "EXPIRY is no longer a literal datetime in staging_operator.py"
        operator_end = dt.datetime(*(int(g) for g in expiry.groups()), tzinfo=dt.timezone.utc)
        end = re.search(r'FORECAST_SCHEDULE_END = new Date\("([^"]+)"\)', STAGING_TS.read_text())
        assert end
        self.assertEqual(
            dt.datetime.fromisoformat(end.group(1).replace("Z", "+00:00")), operator_end
        )

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

    def test_the_detail_cadence_renews_before_the_registry_stales_the_snapshot(self):
        # KTO_KOR_SERVICE_2's stale_after_seconds, the life a forecast request's mapping has.
        life = re.search(r"'pending-c2', 1, (\d+),", SOURCES_MIGRATION.read_text())
        assert life, "the KorService2 registry row no longer reads this way"
        renew = re.search(
            r"DETAIL_RENEW_BEFORE = Duration\.ofDays\((\d+)\)", DEMO_REFRESH.read_text()
        )
        assert renew
        rate = re.search(r"DETAIL_SCHEDULE_RATE_DAYS = (\d+)", STAGING_TS.read_text())
        assert rate
        # A run renews what lapses within DETAIL_RENEW_BEFORE, so the next run has to come while the
        # current snapshot still has that much life left.
        self.assertLessEqual(
            (int(rate.group(1)) + int(renew.group(1))) * 86400, int(life.group(1))
        )

    def test_the_cadence_is_the_window_the_refresh_renews_in(self):
        # KtoDemoRefresh renews the sets that lapse within FORECAST_RENEW_BEFORE. A schedule slower than
        # that leaves a set stale before the next run; a faster one spends KTO quota on sets that are
        # not near lapsing. Neither file can see the other, and only this compares them.
        renew = re.search(
            r"FORECAST_RENEW_BEFORE = Duration\.ofHours\((\d+)\)", DEMO_REFRESH.read_text()
        )
        assert renew, "FORECAST_RENEW_BEFORE is no longer a literal hour count"
        rate = re.search(r"FORECAST_SCHEDULE_RATE_HOURS = (\d+)", STAGING_TS.read_text())
        assert rate
        self.assertEqual(int(rate.group(1)), int(renew.group(1)))

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
