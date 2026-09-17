"""Negative controls for the .env.example parity check.

docs/operations/ENVIRONMENT.md claimed, in prose, that CI compares the example files against the
real configuration binding to find missing and obsolete variables. No such check existed, and none
ever had. Two variables had been sitting in apps/api/.env.example that nothing in the repository
reads, which is exactly what the sentence promised to catch.

The asymmetry is the point: check_cited_tests.py closed the same gap for comments that name a
*class*, because a class name is checkable. A sentence claiming "CI verifies this" names no
artefact, so nothing could look at it. This file is the artefact that sentence should have named.

The check runs in ONE direction only - a key in an example file must have a binding. The reverse
(documented in ENVIRONMENT.md, read by nobody) was measured at 13+ names plus five VITE_*, almost
all of them legitimate, and a check whose first run is twenty legitimate findings teaches people to
ignore it.

No acceptance ID leads these docstrings. No card owns this check, and borrowing one is the shape
#195 describes.
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECK = ROOT / "scripts" / "check_env_example_parity.py"


class EnvExampleParityCheck(unittest.TestCase):

    def run_check(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(CHECK), *args],
                              capture_output=True, text=True, check=False)

    def tree(self, directory: str, files: dict[str, str]) -> str:
        for name, body in files.items():
            path = Path(directory) / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding="utf-8")
        return directory

    # --- a binding can live in four different shapes; all four must count ---

    def test_a_key_bound_by_a_yaml_placeholder_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            self.tree(directory, {
                ".env.example": "SERVER_PORT=8080\n",
                "src/application.yaml": "server:\n  port: ${SERVER_PORT:8080}\n"})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("env_example_parity=resolved", result.stdout)

    def test_a_key_bound_by_a_java_value_annotation_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            # Half of this repository's twelve are bound ONLY in Java, not in any yaml file. A check
            # that scans resources alone reports every one of them as unbound.
            self.tree(directory, {
                ".env.example": "APP_COOKIE_SECURE=\n",
                "src/Thing.java": 'class Thing { Thing(@Value("${APP_COOKIE_SECURE:true}") boolean s) {} }\n'})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_key_bound_only_by_relaxed_binding_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            # NULLNULL_TEST_DATABASE has no placeholder anywhere; it reaches Spring as the dotted
            # property nullnull.test.database through relaxed binding. Requiring a ${...} would call
            # it unbound and would be wrong.
            self.tree(directory, {
                ".env.example": "NULLNULL_TEST_DATABASE=external\n",
                "src/Thing.java": '@ConditionalOnProperty(name = "nullnull.test.database")\nclass Thing {}\n'})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_name_that_merely_starts_with_a_configured_prefix_is_not_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            # This check shipped, briefly, unable to fail for most of the repository. It treated
            # "the dotted name falls under some @ConfigurationProperties prefix" as a binding, and
            # one of the declared prefixes is the bare string `nullnull`, so every NULLNULL_* name
            # passed. A planted variable that nothing reads stayed green and reported itself bound
            # "under prefix nullnull". A prefix says which properties a class MAY bind; it never
            # says which ones exist.
            self.tree(directory, {
                ".env.example": "NULLNULL_MUTATION_PROBE=\n",
                "src/Thing.java": '@ConfigurationProperties("nullnull")\nclass Thing {}\n'})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src")
        self.assertEqual(result.returncode, 1, "a prefix is not evidence that a property exists")
        self.assertIn("NULLNULL_MUTATION_PROBE", result.stderr)

    def test_a_key_bound_by_a_pydantic_alias_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            # AI_PROVIDER lives in apps/api/.env.example and is read by apps/ai. Binding is looked
            # for repository-wide, not per app: the operator has one .env.local and whichever
            # process reads it is the one that binds.
            self.tree(directory, {
                ".env.example": "AI_PROVIDER=NONE\n",
                "src/settings.py": 'ai_provider: str = Field(default="NONE", alias="AI_PROVIDER")\n'})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src")
        self.assertEqual(result.returncode, 0, result.stderr)

    # --- the finding the check exists for ---

    def test_a_key_nothing_reads_is_caught(self):
        with tempfile.TemporaryDirectory() as directory:
            self.tree(directory, {
                ".env.example": "SERVER_PORT=8080\nSEOUL_BASE_URL=\n",
                "src/application.yaml": "server:\n  port: ${SERVER_PORT:8080}\n"})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src")
        self.assertEqual(result.returncode, 1)
        self.assertIn("SEOUL_BASE_URL", result.stderr)
        self.assertNotIn("SERVER_PORT", result.stderr)

    def test_the_finding_names_the_file_and_line(self):
        with tempfile.TemporaryDirectory() as directory:
            self.tree(directory, {
                ".env.example": "# a comment\nSERVER_PORT=8080\nSEOUL_BASE_URL=\n",
                "src/application.yaml": "server:\n  port: ${SERVER_PORT:8080}\n"})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src")
        self.assertEqual(result.returncode, 1)
        self.assertIn(":3", result.stderr, "the operator has to be able to find the line")

    # --- the allowlist, and the guard that stops it rotting ---

    def test_an_allowlisted_key_passes_and_its_reason_is_recorded(self):
        with tempfile.TemporaryDirectory() as directory:
            self.tree(directory, {
                ".env.example": "SEOUL_BASE_URL=\n",
                "allow.txt": "SEOUL_BASE_URL reserved B10 Live, no reader until that card\n",
                "src/application.yaml": "unrelated: true\n"})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src",
                                    "--allow", f"{directory}/allow.txt")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_an_allowlist_entry_without_a_reason_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            # A bare name records nothing. The reason is the whole value of the row: one kind of
            # entry is temporary (a future card will delete it) and one is permanent (the framework
            # consumes it). Mixed together, nobody deletes the temporary ones.
            self.tree(directory, {
                ".env.example": "SEOUL_BASE_URL=\n",
                "allow.txt": "SEOUL_BASE_URL\n",
                "src/application.yaml": "unrelated: true\n"})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src",
                                    "--allow", f"{directory}/allow.txt")
        self.assertEqual(result.returncode, 1)
        self.assertIn("reason", result.stderr.lower())

    def test_an_allowlist_entry_for_a_key_that_is_now_bound_is_caught(self):
        with tempfile.TemporaryDirectory() as directory:
            # The reason this check does not rot. When BA-084 finally reads AI_API_KEY, the row
            # saying "no reader yet" becomes a lie, and a lie nobody is told about is how the
            # ENVIRONMENT.md sentence survived for months.
            self.tree(directory, {
                ".env.example": "SEOUL_BASE_URL=\n",
                "allow.txt": "SEOUL_BASE_URL reserved B10 Live, no reader until that card\n",
                "src/application.yaml": "seoul:\n  base-url: ${SEOUL_BASE_URL:}\n"})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src",
                                    "--allow", f"{directory}/allow.txt")
        self.assertEqual(result.returncode, 1)
        self.assertIn("SEOUL_BASE_URL", result.stderr)

    def test_an_allowlist_entry_for_a_key_no_example_declares_is_caught(self):
        with tempfile.TemporaryDirectory() as directory:
            # The other half of rot: the variable was deleted from the example file and the excuse
            # outlived it.
            self.tree(directory, {
                ".env.example": "SERVER_PORT=8080\n",
                "allow.txt": "GONE_AWAY reserved for a card that shipped\n",
                "src/application.yaml": "server:\n  port: ${SERVER_PORT:8080}\n"})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src",
                                    "--allow", f"{directory}/allow.txt")
        self.assertEqual(result.returncode, 1)
        self.assertIn("GONE_AWAY", result.stderr)

    # --- a scan that read nothing is not a clean one ---

    def test_a_missing_example_file_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            self.tree(directory, {"src/application.yaml": "unrelated: true\n"})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src")
        self.assertEqual(result.returncode, 1)
        self.assertIn("env_example_parity=blocked", result.stderr)

    def test_an_example_file_with_no_keys_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            self.tree(directory, {
                ".env.example": "# only comments live here\n",
                "src/application.yaml": "unrelated: true\n"})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/src")
        self.assertEqual(result.returncode, 1)
        self.assertIn("env_example_parity=blocked", result.stderr)

    def test_a_source_root_that_does_not_exist_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            self.tree(directory, {".env.example": "SERVER_PORT=8080\n"})
            result = self.run_check(f"{directory}/.env.example", "--source-root", f"{directory}/absent")
        self.assertEqual(result.returncode, 1)
        self.assertIn("env_example_parity=blocked", result.stderr)

    # --- and the case that makes this run in the required gate ---

    def test_this_repository_satisfies_the_check(self):
        result = self.run_check(
            str(ROOT / "apps/api/.env.example"),
            str(ROOT / "apps/ai/.env.example"),
            "--source-root", str(ROOT / "apps"),
            "--source-root", str(ROOT / "scripts"),
            "--allow", str(ROOT / "scripts" / "env-example-allow.txt"))
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
