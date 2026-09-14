"""Negative controls for the test-row-ownership check.

The case is measured, not hypothetical: `origin/main` passed the whole integration suite against one
shared database, and a branch that added three integration classes failed 77 of 393 on the same
suite. None of the three new classes was wrong. Adding them changed the order, and the order was
the only thing holding the unscoped deletes apart.

The repository check at the end is the one that will fail first when someone reintroduces the
pattern, so the controls above it have to establish that the check speaks at all.

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
CHECK = ROOT / "scripts" / "check_test_row_ownership.py"

# A scoped UPDATE whose SQL embeds jsonb, written the way this repository writes it.
ESCAPED_JSONB_SQL = r'''jdbc.update("UPDATE source_registry SET quota_policy = '{\"perDay\":10}'::jsonb"
        + " WHERE code = ?", source);
'''


class TestRowOwnership(unittest.TestCase):

    def run_check(self, root: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(CHECK), root],
                              capture_output=True, text=True, check=False)

    def tree(self, directory: str, body: str) -> str:
        (Path(directory) / "SomeIT.java").write_text(body, encoding="utf-8")
        return directory

    def test_a_delete_that_names_rows_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            self.tree(directory, 'jdbc.update("DELETE FROM places WHERE id = ?", id);\n')
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("test_row_ownership=scoped", result.stdout)

    def test_an_unscoped_delete_is_caught(self):
        with tempfile.TemporaryDirectory() as directory:
            # The exact statement that failed 77 tests in the gate.
            self.tree(directory, 'jdbc.update("DELETE FROM places");\n')
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 1)
        self.assertIn("test_row_ownership=unscoped", result.stderr)
        self.assertIn("places", result.stderr)

    def test_a_truncate_is_caught(self):
        with tempfile.TemporaryDirectory() as directory:
            # Blunter than the DELETE and refused for the same reason.
            self.tree(directory, 'jdbc.execute("TRUNCATE TABLE trip_candidates");\n')
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 1)
        self.assertIn("trip_candidates", result.stderr)

    def test_a_scoped_delete_in_a_text_block_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            self.tree(directory, 'jdbc.update("""\n    DELETE FROM place_hours_windows\n'
                                 '    WHERE place_id = ?\n    """, id);\n')
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_an_unscoped_delete_in_a_text_block_is_caught(self):
        with tempfile.TemporaryDirectory() as directory:
            self.tree(directory, 'jdbc.update("""\n    DELETE FROM place_hours_windows\n    """);\n')
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 1)
        self.assertIn("place_hours_windows", result.stderr)

    def test_a_delete_only_described_in_a_comment_is_not_a_finding(self):
        with tempfile.TemporaryDirectory() as directory:
            # Prose explaining why a blanket delete was REMOVED must not be reported as one.
            self.tree(directory, '// This used to be DELETE FROM places, which killed the gate.\n'
                                 'jdbc.update("DELETE FROM places WHERE id = ?", id);\n')
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_sql_literal_before_the_where_does_not_hide_it(self):
        with tempfile.TemporaryDirectory() as directory:
            # The false positive this check actually produced, found by the person it was reported
            # against. The tail used to stop at the first single quote, so the WHERE after a SQL
            # string literal was never seen and a correctly scoped statement was called a defect.
            self.tree(directory,
                      'jdbc.update("UPDATE optimization_proposals SET summary = \'edited\'"\n'
                      '        + " WHERE id = ?", proposal);\n')
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_an_escaped_quote_inside_the_sql_does_not_hide_the_where(self):
        with tempfile.TemporaryDirectory() as directory:
            # The third route to the same false positive: SQL that embeds jsonb writes an
            # escaped quote inside the Java literal, and the tail stopped there - before it
            # even reached the concatenation. Three shapes in one day, each a different way of
            # answering "where does this statement end" too simply.
            self.tree(directory, ESCAPED_JSONB_SQL)
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_the_reported_line_survives_a_block_comment(self):
        with tempfile.TemporaryDirectory() as directory:
            # Stripping a block comment used to remove its newlines, so every line number after one
            # was wrong - and the number is the only thing a reader uses to find the statement. It
            # pointed at a closing brace, and the reader concluded the check was wrong about the file.
            self.tree(directory, "/*\n * four\n * line\n */\n" + 'jdbc.update("DELETE FROM places");\n')
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 1)
        self.assertIn(":5:", result.stderr)

    def test_a_root_with_no_java_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 1, "a scan that read nothing is not a clean one")
        self.assertIn("test_row_ownership=blocked", result.stderr)

    def test_a_missing_root_is_refused(self):
        result = self.run_check(str(ROOT / "does-not-exist"))
        self.assertEqual(result.returncode, 1)
        self.assertIn("test_row_ownership=blocked", result.stderr)

    def test_this_repository_satisfies_the_check(self):
        result = subprocess.run([sys.executable, str(CHECK)], cwd=ROOT,
                                capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
