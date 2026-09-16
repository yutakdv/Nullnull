#!/usr/bin/env python3
"""A test deletes the rows it made, not the table.

The required gate runs every suite against ONE PostgreSQL (`NULLNULL_TEST_DATABASE=external`).
Locally `TestcontainersConfiguration` hands each distinct `@SpringBootTest` configuration its own
container, so a test class sees an empty database whatever anyone else did. That difference is the
whole subject of this check: an unscoped `DELETE FROM places` is correct in the local world and a
loaded weapon in the gate's.

Two things go wrong, and they land on different people:

  * It DIES. `places` is referenced without cascade by ten tables - `place_localizations`,
    `place_external_refs`, `crowd_snapshots`, `trip_candidates`, `post_places`, `trip_items`,
    `place_hours_observations`, `place_relations`, and its own `canonical_place_id`. The cascade is
    absent on purpose: a place must not vanish from under a candidate. So one row left by any other
    class kills the DELETE, and the failure is reported against the class that tried to CLEAN UP,
    not the one that left the row.
  * Or it SUCCEEDS, and takes someone else's fixtures with it.

Measured: `origin/main` passes the whole integration suite against a shared database. The same
suite on a branch that added three integration classes failed 77 of 393 - not because those classes
were wrong, but because adding classes changed the order, and an order that had been holding this
together stopped holding. Every one of the 77 was some other class's unscoped DELETE meeting rows
that had been left behind for months.

So the rule is not "clean up more". It is: **the statement that deletes must name what it deletes.**
A class that only removes its own ids cannot be broken by what ran before it and cannot break what
runs after.

TRUNCATE is refused for the same reason and more bluntly - it takes the table regardless. So is an
unscoped UPDATE, which can be worse than a delete: `UPDATE places SET status = 'ACTIVE',
canonical_place_id = NULL` was written to undo one test's own fixture and quietly RESURRECTS every
place another test had deprecated. A delete at least fails loudly when something references the row.

There is a third face this check cannot see, recorded here so the reader knows the boundary. A test
can name its rows by a VALUE rather than by an id, and a value is only a name while it is unique:
the paste importer resolves a line only when exactly one place matches it, so another class's
leftover `경복궁` turns a resolved item into an unresolved token, and the test fails with correct
code. Scoping a WHERE does not help - the fixture has to make its own names unique (a per-run tag).
The rule is the same one: a statement that does not identify its rows is a statement about everyone's.

No acceptance ID leads this docstring. No card owns this check and borrowing one is the shape #195
describes. It runs because the gate runs it.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# A DELETE, TRUNCATE or UPDATE in the SQL view below. The tail runs to the `;` that ends the Java
# statement, because that is the only boundary that means anything: what matters is whether a WHERE
# follows the table name in the same statement, no matter how many fragments the SQL was built from.
STATEMENT = re.compile(
    r"""(?P<verb>DELETE\s+FROM|TRUNCATE(?:\s+TABLE)?|UPDATE)\s+(?P<table>[a-z_][a-z0-9_]*)(?P<rest>[^;]*)""",
    re.IGNORECASE)

SCOPED = re.compile(r"\bWHERE\b", re.IGNORECASE)

# "Where does this statement end" got four different answers here, each too simple, and each one
# reported a correctly scoped statement as a defect:
#
#   1. a single quote - `UPDATE t SET c = 'x' WHERE id = ?` - single quotes open SQL string
#      literals and are ORDINARY inside a Java one;
#   2. the closing double quote of the first fragment, when the SQL continues in `" + "`;
#   3. an escaped quote, when the SQL embeds jsonb: `'{\"perDay\":10}'::jsonb" + " WHERE code = ?"`;
#   4. a spliced Java expression, when a value is concatenated INTO the SQL text:
#      `policy_hash = '" + "b".repeat(64) + "'," + " ... WHERE id = ?"`. The joiner only bridged
#      seams whose two sides were both literals, so the tail stopped before the WHERE.
#
# Each fix widened a regex, and the next shape arrived anyway. So the question is answered once, in
# `sql_view`: everything outside a Java string literal stops being text. A check that cries wolf is
# worse than no check - the next real finding is read as another false one - and three of these four
# were found by the person they were reported against, not by these controls.

# The read that makes the same mistake. `SELECT count(*) FROM places` in a shared database is not a
# statement about this test - it is a statement about every test that ran before it. Measured in the
# same failing run as the deletes: 11 of the 77 failures were this, reading `expected: 1 but was:
# 216`. The delete and the count are one defect wearing two faces, so one check reports both.
#
# Only a bare table name counts. `SELECT count(*) FROM " + SCHEMA + "." + table` is a deliberate
# sweep over a schema this test owns (FlywayMigrationIT does exactly that), and the concatenation is
# what tells the two apart.
AGGREGATE_READ = re.compile(
    r"""SELECT\s+(?:count|sum|min|max|bool_and|bool_or|array_agg|string_agg)\s*\("""
    r"""[^)]*\)\s+FROM\s+(?P<table>[a-z_][a-z0-9_]*)(?P<rest>[^;]*)""",
    re.IGNORECASE)

# Everything outside a Java string literal becomes this. It is deliberately NOT a space: a space
# would let `DELETE FROM " + table + " WHERE ..."` read as a delete from a table called `where`,
# and that line is `OwnedRows`, the helper every class uses to delete exactly its own rows. A
# sentinel matches neither `\s` nor `[a-z_]`, so a statement whose TABLE NAME is computed stays
# unjudged - which is the behaviour this check has always had, and the same property keeps the
# schema sweep in `FlywayMigrationIT` (`FROM " + SCHEMA + "." + table`) out of the aggregate rule.
OUTSIDE = "\0"


def sql_view(source: str) -> str:
    """The SQL this file executes, with every Java construct between the fragments erased.

    Four times a widened regex was the answer to "where does this statement end", and a fifth shape
    arrived each time (see the note above `SCOPED`). The shapes are not related to each other; what
    they have in common is that the scanner was reading JAVA and guessing at the SQL. So read the
    Java once, properly: string literals - including text blocks - keep their contents, and
    everything else becomes a sentinel. A `;` survives because it is the only real statement
    boundary, and comments are handled HERE rather than by a regex pass, because a regex that
    strips `//` without knowing about literals eats the rest of any line holding a `http://` URL.

    The view is the same LENGTH as the source with newlines at the same offsets, so the line number
    this check reports is exact. That number is the only thing a reader uses to find the statement;
    when it was off by the height of a block comment, it pointed at a closing brace and the reader
    concluded the check was wrong about the file.
    """
    out: list[str] = []
    index, length = 0, len(source)
    while index < length:
        char = source[index]
        if source.startswith('"""', index):                 # text block: contents are SQL, verbatim
            out.append(OUTSIDE * 3)
            index += 3
            while index < length and not source.startswith('"""', index):
                out.append(source[index])
                index += 1
            out.append(OUTSIDE * min(3, length - index))
            index += 3
        elif char == '"':
            out.append(OUTSIDE)
            index += 1
            while index < length and source[index] != '"':
                if source[index] == "\\" and index + 1 < length:
                    out.append("  ")                        # `\"` in jsonb SQL is not a delimiter
                    index += 2
                    continue
                out.append(source[index])
                index += 1
            if index < length:
                out.append(OUTSIDE)
                index += 1
        elif source.startswith("//", index):
            while index < length and source[index] != "\n":
                out.append(OUTSIDE)
                index += 1
        elif source.startswith("/*", index):
            end = source.find("*/", index + 2)
            end = length if end < 0 else end + 2
            out.append("".join("\n" if c == "\n" else OUTSIDE for c in source[index:end]))
            index = end
        else:
            out.append(char if char in ";\n" else OUTSIDE)
            index += 1
    return "".join(out)


def unscoped_statements(path: Path) -> list[tuple[int, str, str]]:
    """(line, what, table) for every statement in this file that names no rows."""
    source = sql_view(path.read_text(encoding="utf-8"))
    found = []
    for match in STATEMENT.finditer(source):
        if SCOPED.search(match.group("rest")):
            continue
        line = source.count("\n", 0, match.start()) + 1
        found.append((line, match.group("verb").upper(), match.group("table")))
    for match in AGGREGATE_READ.finditer(source):
        if SCOPED.search(match.group("rest")) or match.group("table").startswith("pg_"):
            # PostgreSQL's own views describe the server, not this test's rows. JobIsolationIT reads
            # pg_stat_activity to count connections, and scoping that to "rows this class inserted"
            # is not a sentence that means anything.
            continue
        line = source.count("\n", 0, match.start()) + 1
        found.append((line, "AGGREGATE OVER", match.group("table")))
    return sorted(found)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="*", type=Path,
                        default=[Path("apps/api/src/integrationTest"), Path("apps/api/src/test")],
                        help="test source roots to scan")
    arguments = parser.parse_args()

    missing = [root for root in arguments.root if not root.is_dir()]
    if missing:
        for root in missing:
            print(f"test_row_ownership=blocked source root does not exist: {root}", file=sys.stderr)
        return 1

    files = []
    for root in arguments.root:
        files.extend(sorted(root.rglob("*.java")))
    if not files:
        # A scan that read nothing is not a clean one.
        print("test_row_ownership=blocked no .java files under the given roots", file=sys.stderr)
        return 1

    problems: list[str] = []
    scanned = 0
    for path in files:
        for line, verb, table in unscoped_statements(path):
            scanned += 1
            problems.append(f"{path}:{line}: {verb} {table} names no rows")

    if problems:
        for problem in problems:
            print(f"test_row_ownership=unscoped {problem}", file=sys.stderr)
        print(f"test_row_ownership=unscoped {len(problems)} statement(s). The gate runs every suite "
              "against one database, so a statement that names no rows is a statement about every "
              "test that ran before this one. A delete either dies on their rows - and the failure "
              "is reported against THIS class - or takes their fixtures with it. An aggregate reads "
              "their rows and asserts about them. Name the ids this class inserted.", file=sys.stderr)
        return 1

    print(f"test_row_ownership=scoped files={len(files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
