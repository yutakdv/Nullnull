"""Every oasdiff warn-ignore line must be a registered, justified exception.

`docs-contract` fails on WARN so a breaking contract change cannot merge silently.
An intentional correction is allowed only through this list, and this test is what
keeps the list from becoming a blanket skip: each ignored oasdiff message needs a
row in the registry carrying a reason, an approver and a tracking issue.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IGNORE = ROOT / "docs/api/oasdiff-warn-ignore.txt"
REGISTRY = ROOT / "docs/api/BREAKING_CHANGE_EXCEPTIONS.md"

ROW = re.compile(r"^\|(?P<message>[^|]+)\|(?P<rest>.*)\|\s*$")


def ignored_messages() -> list[str]:
    if not IGNORE.exists():
        return []
    lines = []
    for raw in IGNORE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line:
            lines.append(line)
    return lines


SEPARATOR = re.compile(r"^\|[\s|:-]+\|$")


def registry_rows() -> dict[str, list[str]]:
    """Data rows of the exception table: everything after the header separator."""
    rows: dict[str, list[str]] = {}
    if not REGISTRY.exists():
        return rows
    in_table = False
    for raw in REGISTRY.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if SEPARATOR.match(line):
            in_table = True
            continue
        if not in_table:
            continue
        match = ROW.match(line)
        if not match:
            continue
        cells = [cell.strip() for cell in match.group("rest").split("|")]
        rows[match.group("message").strip()] = cells
    return rows


class OasdiffExceptionsTest(unittest.TestCase):
    def test_registry_exists_when_anything_is_ignored(self):
        if ignored_messages():
            self.assertTrue(REGISTRY.exists(), f"{REGISTRY} must exist when the ignore list is non-empty")

    def test_every_ignored_message_is_registered(self):
        rows = registry_rows()
        for message in ignored_messages():
            self.assertIn(
                message,
                rows,
                f"oasdiff ignore line is not registered in BREAKING_CHANGE_EXCEPTIONS.md: {message}",
            )

    def test_every_registered_row_is_justified(self):
        for message, cells in registry_rows().items():
            self.assertGreaterEqual(
                len(cells), 3, f"{message}: registry row needs reason, approver and issue columns"
            )
            reason, approver, issue = cells[0], cells[1], cells[2]
            self.assertTrue(reason and reason != "-", f"{message}: reason is empty")
            self.assertTrue(approver and approver != "-", f"{message}: approver is empty")
            self.assertRegex(issue, r"#\d+", f"{message}: issue must reference a tracking issue")

    def test_no_orphan_registry_rows(self):
        ignored = set(ignored_messages())
        for message in registry_rows():
            self.assertIn(
                message,
                ignored,
                f"registry row has no matching ignore line (stale exception?): {message}",
            )


if __name__ == "__main__":
    unittest.main()
