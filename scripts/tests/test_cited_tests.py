"""Negative controls for the cited-test check.

The case this exists for was real and was found by hand: a javadoc said the two declarations of a
vocabulary "are checked against each other by OptimizationFailureVocabularyIT", and that class did
not exist. It had been written two slices earlier. Running the check for the first time turned up
two more, both suffix swaps - a comment naming ...Test where the class is ...IT, and the reverse.
Those are worse than they look: the check IS there, so a reader who greps the cited name, finds
nothing, and concludes nothing is verifying it has been misled in the safe-seeming direction.

No acceptance ID leads these docstrings. No card owns this check and borrowing one is the shape
#195 describes.
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECK = ROOT / "scripts" / "check_cited_tests.py"


class CitedTestsCheck(unittest.TestCase):

    def run_check(self, *roots: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(CHECK), *roots],
                              capture_output=True, text=True, check=False)

    def tree(self, directory: str, files: dict[str, str]) -> str:
        for name, body in files.items():
            path = Path(directory) / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding="utf-8")
        return directory

    def test_a_comment_naming_a_class_that_exists_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            self.tree(directory, {
                "Thing.java": "/** Pinned by ThingVocabularyIT. */\nclass Thing {}\n",
                "ThingVocabularyIT.java": "class ThingVocabularyIT {}\n"})
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("cited_tests=resolved", result.stdout)

    def test_a_comment_naming_a_class_that_does_not_exist_is_caught(self):
        with tempfile.TemporaryDirectory() as directory:
            # The real case: a sentence that reads as a guarantee, naming a device nobody built.
            self.tree(directory, {
                "Thing.java": "/** Pinned by ThingVocabularyIT. */\nclass Thing {}\n"})
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 1)
        self.assertIn("ThingVocabularyIT", result.stderr)

    def test_a_suffix_swap_is_caught(self):
        with tempfile.TemporaryDirectory() as directory:
            # Both real findings were this: the check exists under the other suffix, so a reader who
            # greps the cited name concludes nothing verifies it.
            self.tree(directory, {
                "Thing.java": "/** Pinned by ThingCoverageIT. */\nclass Thing {}\n",
                "ThingCoverageTest.java": "class ThingCoverageTest {}\n"})
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 1)
        self.assertIn("ThingCoverageIT", result.stderr)

    def test_a_screaming_case_word_ending_in_it_is_not_a_citation(self):
        with tempfile.TemporaryDirectory() as directory:
            # COMMIT was the first false positive. So were AUDIT and EXIT.
            self.tree(directory, {
                "Thing.java": "// The COMMIT happens here, after the AUDIT and before EXIT.\nclass Thing {}\n"})
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_name_only_in_code_is_not_scanned(self):
        with tempfile.TemporaryDirectory() as directory:
            # Code cannot be wrong about a type - the compiler refused already. Scanning it would
            # report every legitimate reference in a file that does not declare the class.
            self.tree(directory, {
                "Thing.java": "class Thing { AbsentHelperIT helper; }\n"})
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_an_annotation_is_not_a_citation(self):
        with tempfile.TemporaryDirectory() as directory:
            # What actually broke the gate: seven files gained a comment explaining that each
            # distinct @SpringBootTest configuration gets its own container, and every one was
            # reported as citing a class nobody built. The @ is the tell - it names an annotation.
            self.tree(directory, {
                "Thing.java": "/** Every {@code @SpringBootTest} configuration gets one. */\n"
                              "class Thing {}\n"})
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_spring_annotation_named_without_its_at_sign_is_not_a_citation(self):
        with tempfile.TemporaryDirectory() as directory:
            # Prose drops the @ as often as it keeps it, and this repository will never declare
            # SpringBootTest, so naming it is a statement about the framework.
            self.tree(directory, {
                "Thing.java": "// A SpringBootTest context is expensive to build.\nclass Thing {}\n"})
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_root_with_no_java_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 1, "a scan that read nothing is not a clean one")
        self.assertIn("cited_tests=blocked", result.stderr)

    def test_a_missing_root_is_refused(self):
        result = self.run_check(str(ROOT / "does-not-exist"))
        self.assertEqual(result.returncode, 1)
        self.assertIn("cited_tests=blocked", result.stderr)

    def test_this_repository_satisfies_the_check(self):
        result = self.run_check(str(ROOT / "apps/api/src"))
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
