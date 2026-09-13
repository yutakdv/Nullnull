"""The generated-client check is only deterministic while its generator is pinned exactly.

docs-contract regenerates packages/api-client/src/generated/openapi.ts and diffs it against what is
committed. That comparison means something only if `npx openapi-typescript@<version>` resolves to
one build. With a range - `^7.13.0` - a new 7.x release changes the output on a day nobody touched
anything, and the gate goes red for a drift that is not drift. The failure would look exactly like
the real one it exists to catch, which is the worse half: a check that cries wolf is a check people
learn to re-run until it passes.
"""
from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / 'packages/api-client/package.json'
WORKFLOW = ROOT / '.github/workflows/docs-contract.yml'
EXACT = re.compile(r'\d+\.\d+\.\d+')


class GeneratedClientPinTests(unittest.TestCase):
    def setUp(self):
        self.package = json.loads(PACKAGE.read_text(encoding='utf-8'))

    def test_the_generator_is_pinned_to_one_version(self):
        version = self.package['devDependencies']['openapi-typescript']
        # fullmatch, not search: "^7.13.0" CONTAINS "7.13.0", so a search-based assertion passes on
        # exactly the value it exists to refuse. Written that way first, and the negative control
        # was what said so - the assertion could not fail.
        self.assertIsNotNone(EXACT.fullmatch(version),
                             f'{version!r} is not an exact version. A range makes the '
                             'regenerate-and-diff gate red on a day nobody changed anything, and '
                             'that red is indistinguishable from a real drift')

    def test_the_workflow_reads_the_pin_instead_of_repeating_it(self):
        """Two pins are the same rule in two files, and one of them goes stale first."""
        workflow = WORKFLOW.read_text(encoding='utf-8')
        self.assertIn("require('./packages/api-client/package.json').devDependencies", workflow)
        self.assertNotRegex(workflow, r'openapi-typescript@\d+\.\d+\.\d+',
                            'the workflow must read the version, not carry its own copy')

    def test_the_workflow_generates_with_the_same_flags_as_the_workspace(self):
        """Different flags would make the diff always fail, which reads as permanent drift."""
        generate = self.package['scripts']['generate']
        workflow = WORKFLOW.read_text(encoding='utf-8')
        for flag in re.findall(r'--[a-z-]+(?:=\S+)?', generate):
            with self.subTest(flag=flag):
                self.assertIn(flag, workflow)


if __name__ == '__main__':
    unittest.main()
