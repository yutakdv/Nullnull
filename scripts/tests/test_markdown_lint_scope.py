"""Every tracked markdown file is linted, or is named as a deliberate exception.

The glob list enumerated docs/ subdirectories one at a time, so a new subdirectory joined the
repository without joining the lint - docs/superpowers/ did exactly that, and apps/api/README.md and
apps/ai/README.md were never covered at all although CLAUDE.md points at them as the canon for how
the build actually runs. The symptom is neither a red nor a green: the check passes while looking at
a smaller set than anyone believes, which is the coverage form of "a check that passes without
proving".

So the scope is compared against `git ls-files` rather than trusted. Exceptions are two root working
artifacts, listed here by name: a handoff ledger and a session report, neither of which is a
contract or design document.
"""
from __future__ import annotations

import json
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / '.markdownlint-cli2.jsonc'
# Working artifacts, not specifications. Named rather than pattern-matched so that adding one is a
# decision somebody makes on purpose.
EXPECTED_EXCEPTIONS = {'HANDOFF-PROMPT.md', 'NIGHT_WORK_REPORT.md'}


def config() -> dict:
    text = CONFIG.read_text(encoding='utf-8')
    return json.loads(re.sub(r'^\s*//.*$', '', text, flags=re.M))


class MarkdownLintScopeTests(unittest.TestCase):
    def setUp(self):
        # -z, because without it git quotes non-ASCII names with octal escapes
        # ("docs/contest/2026-\352\264\200...") and comparing that string to a real path never
        # matches. This repository has Korean filenames, so a check written to catch drift would
        # invent one instead - a guard firing on nothing, which is rule 7② in reverse.
        self.tracked = {
            name for name in subprocess.run(
                ['git', '-C', str(ROOT), 'ls-files', '-z', '*.md'],
                capture_output=True, text=True, check=True).stdout.split('\0') if name
        }

    def test_every_tracked_markdown_is_linted_or_named_as_an_exception(self):
        linted = set()
        for pattern in config()['globs']:
            linted |= {str(p.relative_to(ROOT)) for p in ROOT.glob(pattern)}
        unseen = self.tracked - linted - EXPECTED_EXCEPTIONS
        self.assertEqual(set(), unseen,
                         'these markdown files are tracked and never linted; add them to globs or '
                         'name them in EXPECTED_EXCEPTIONS with a reason')

    def test_the_exception_list_matches_the_config(self):
        """An exception dropped from the config while still listed here would read as covered."""
        ignores = set(config()['ignores'])
        self.assertTrue(EXPECTED_EXCEPTIONS <= ignores,
                        f'{EXPECTED_EXCEPTIONS - ignores} is excepted here but not ignored in the config')

    def test_no_exception_is_kept_for_a_file_that_no_longer_exists(self):
        """A stale exception silently widens what may go unlinted."""
        for name in EXPECTED_EXCEPTIONS:
            with self.subTest(name=name):
                self.assertIn(name, self.tracked)


if __name__ == '__main__':
    unittest.main()
