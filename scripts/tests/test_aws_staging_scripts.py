"""Static and local-only tests for the AWS staging handoff scripts.

These tests never call AWS. Live acceptance remains blocked until the CDK stacks exist and the
operator runs the explicit staging scripts against the approved account.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
AWS_SCRIPTS = ROOT / "scripts" / "aws"

# `common.sh` decides what an operator address looks like with this same shape. Keeping the two in
# agreement means this rejects exactly what the scripts would have accepted as a recipient.
CONTACT = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_WORD = re.compile(r"[A-Za-z0-9]+")


def account_ids(text: str) -> list[str]:
    """Twelve-digit AWS account ids, found by token rather than by a run of digits.

    A sha256 in a release manifest can hold twelve digits in a row, but as a token it is sixty-four
    characters, so it is not one of these. `arn:aws:iam::123456789012:role/x` splits on the colons.
    """
    return [word for word in _WORD.findall(text) if len(word) == 12 and word.isdigit()]


class ReleaseManifestValidatorTest(unittest.TestCase):
    def valid_manifest(self) -> dict[str, object]:
        digest = "sha256:" + "a" * 64
        return {
            "releaseVersion": "v0.1.0-rc.1",
            "gitSha": "b" * 40,
            "apiImageDigest": digest,
            "aiImageDigest": digest,
            "aiCatalogVersion": "KTO_KOR_SERVICE_2:4",
            "webArtifactSha256": digest,
            "openApiSha256": digest,
            "eventSchemaSha256": digest,
            "flywayChecksums": ["V001:" + "c" * 64],
            "cdkAssemblySha256": digest,
            "buildRunId": "12345",
            "approvedByRoles": ["BE_AI_DRI", "FE_DRI"],
            "sourceState": "clean",
        }

    def run_validator(self, manifest: dict[str, object], *, expected_sha: str | None = None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "release.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            environment = os.environ.copy()
            if expected_sha is not None:
                environment["NULLNULL_EXPECTED_GIT_SHA"] = expected_sha
            return subprocess.run(
                ["node", str(AWS_SCRIPTS / "validate-release-manifest.mjs"), str(path)],
                capture_output=True,
                text=True,
                check=False,
                env=environment,
            )

    def test_valid_manifest_passes_without_printing_content(self) -> None:
        result = self.run_validator(self.valid_manifest())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "release_manifest=pass\n")

    def test_floating_latest_is_rejected(self) -> None:
        manifest = self.valid_manifest()
        manifest["releaseVersion"] = "latest"
        result = self.run_validator(manifest)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("floating-latest-forbidden", result.stderr)

    def test_secret_like_material_is_rejected(self) -> None:
        manifest = self.valid_manifest()
        manifest["KTO_SERVICE_KEY"] = "must-not-appear"
        result = self.run_validator(manifest)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("secret-like-field-or-value", result.stderr)

    def test_overlay_source_must_carry_hash_and_paths(self) -> None:
        manifest = self.valid_manifest()
        manifest["sourceState"] = "overlay"
        self.assertIn("invalid-sourceOverlaySha256", self.run_validator(manifest).stderr)
        manifest["sourceOverlaySha256"] = "sha256:" + "e" * 64
        manifest["sourceOverlayPaths"] = ["apps/api/src/main/java/io/nullnull/MigrationRunner.java"]
        self.assertEqual(self.run_validator(manifest).returncode, 0)
        manifest["sourceOverlayPaths"] = ["../outside"]
        self.assertIn("invalid-sourceOverlayPaths", self.run_validator(manifest).stderr)

    def test_clean_source_cannot_carry_overlay_fields_or_be_omitted(self) -> None:
        manifest = self.valid_manifest()
        manifest["sourceOverlaySha256"] = "sha256:" + "e" * 64
        self.assertIn("clean-source-has-overlay-fields", self.run_validator(manifest).stderr)
        manifest = self.valid_manifest()
        del manifest["sourceState"]
        self.assertIn("invalid-source-state", self.run_validator(manifest).stderr)

    def test_expected_git_sha_must_match(self) -> None:
        result = self.run_validator(self.valid_manifest(), expected_sha="d" * 40)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("git-sha-mismatch", result.stderr)


class OidcTrustValidatorTest(unittest.TestCase):
    # Immutable subjects (owner/repository IDs) - what GitHub issues for this repository.
    PREFIX = "repo:yutakdv@98016178/Nullnull@1348534580:environment:"

    def run_validator(self, subject, *, string_like: bool = False, kind: str | None = None):
        condition_key = "StringLike" if string_like else "StringEquals"
        condition = {
            condition_key: {
                "token.actions.githubusercontent.com:sub": subject,
                "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
            }
        }
        policy = {
            "Version": "2012-10-17",
            "Statement": [{
                "Effect": "Allow",
                "Principal": {
                    "Federated": (
                        "arn:aws:iam::" + "1" * 12 + ":oidc-provider/"
                        "token.actions.githubusercontent.com"
                    )
                },
                "Action": "sts:AssumeRoleWithWebIdentity",
                "Condition": condition,
            }],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "policy.json"
            path.write_text(json.dumps(policy), encoding="utf-8")
            return subprocess.run(
                ["node", str(AWS_SCRIPTS / "validate-oidc-trust.mjs"), str(path), *([kind] if kind else [])],
                capture_output=True,
                text=True,
                check=False,
            )

    def test_exact_deploy_subjects_pass_in_any_order(self) -> None:
        subjects = [self.PREFIX + "staging-infra", self.PREFIX + "staging"]
        result = self.run_validator(subjects)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_publish_role_has_only_the_build_environment(self) -> None:
        self.assertEqual(self.run_validator(self.PREFIX + "staging-build", kind="publish").returncode, 0)
        self.assertNotEqual(self.run_validator(self.PREFIX + "staging-build").returncode, 0)

    def test_legacy_name_only_subject_is_rejected(self) -> None:
        # GitHub never issues this form for the repository, so trusting it trusts a recycled name.
        result = self.run_validator(["repo:yutakdv/Nullnull:environment:staging", self.PREFIX + "staging-infra"])
        self.assertNotEqual(result.returncode, 0)

    def test_subset_or_extra_subject_is_rejected(self) -> None:
        self.assertNotEqual(self.run_validator(self.PREFIX + "staging").returncode, 0)
        extra = [self.PREFIX + "staging", self.PREFIX + "staging-infra", self.PREFIX + "production"]
        self.assertNotEqual(self.run_validator(extra).returncode, 0)

    def test_wildcard_subject_is_rejected(self) -> None:
        result = self.run_validator(self.PREFIX + "*", string_like=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("trust-is-not-exact-staging-subject", result.stderr)
        self.assertNotEqual(self.run_validator([self.PREFIX + "staging*", self.PREFIX + "staging-infra"]).returncode, 0)


class CatalogVersionDerivationTest(unittest.TestCase):
    """prepare-release derives apps/ai's NULLNULL_CATALOG_VERSION from the migrations, never from input."""

    def module(self):
        import importlib.util, sys
        sys.path.insert(0, str(AWS_SCRIPTS))
        spec = importlib.util.spec_from_file_location("nullnull_prepare_release", AWS_SCRIPTS / "prepare-release.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def migrations(self, directory, *texts):
        paths = []
        for index, text in enumerate(texts, start=1):
            path = Path(directory) / f"V{index:03d}__m.sql"
            path.write_text(text, encoding="utf-8")
            paths.append(path)
        return paths

    def test_the_repository_migrations_give_the_revision_the_api_reads(self) -> None:
        migrations = sorted((ROOT / "apps/api/src/main/resources/db/migration").glob("V*.sql"),
                            key=lambda f: int(f.name[1:].split("__")[0]))
        self.assertRegex(self.module().catalog_version(migrations), r"^KTO_KOR_SERVICE_2:[1-9][0-9]*$")

    def test_semicolons_in_strings_and_comments_do_not_split_statements(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self.migrations(directory,
                "-- a note; with a semicolon\nUPDATE source_registry SET x = 'a; b', current_revision = 7 "
                "WHERE code = 'KTO_KOR_SERVICE_2';")
            self.assertEqual("KTO_KOR_SERVICE_2:7", self.module().catalog_version(paths))

    def test_an_unreadable_revision_change_stops_the_release(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            # A readable change first, so only the shape guard - not "no revision found" - can refuse.
            paths = self.migrations(directory,
                "UPDATE source_registry SET current_revision = 4 WHERE code = 'KTO_KOR_SERVICE_2';",
                "UPDATE source_registry SET current_revision = 5 WHERE code IN ('KTO_KOR_SERVICE_2');")
            with self.assertRaisesRegex(SystemExit, "unrecognised statement"):
                self.module().catalog_version(paths)


class ShellSafetyContractTest(unittest.TestCase):
    def test_all_shell_scripts_parse(self) -> None:
        scripts = sorted(AWS_SCRIPTS.glob("*.sh"))
        self.assertGreaterEqual(len(scripts), 7)
        for script in scripts:
            with self.subTest(script=script.name):
                result = subprocess.run(["bash", "-n", str(script)], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_deploy_and_rollback_wrappers_stop_at_the_operator_gate(self) -> None:
        # Behavior, not text: the wrapper must hand its arguments to the operator, whose plan-hash gate
        # refuses before any AWS call. (What a rollback deploys is tested on the operator itself.)
        environment = {**os.environ, "NULLNULL_AWS_AUTH": "profile"}
        for name in ("staging-deploy.sh", "staging-rollback.sh"):
            with self.subTest(script=name), tempfile.TemporaryDirectory() as directory:
                plan = Path(directory) / "plan.json"
                plan.write_text("{}", encoding="utf-8")
                cases = [
                    (["--execute"], "reason=execute-requires-saved-plan"),
                    (["--plan", str(plan), "--execute", "--kind", "app"], "reason=reviewed-plan-does-not-match"),
                    (["--plan", str(plan), "--execute", "--kind", "app", "--approved-diff-sha256", "0" * 64],
                     "reason=reviewed-plan-does-not-match"),
                ]
                for arguments, reason in cases:
                    result = subprocess.run(["bash", str(AWS_SCRIPTS / name), *arguments],
                                            capture_output=True, text=True, env=environment, check=False)
                    self.assertEqual(1, result.returncode, result.stderr)
                    self.assertIn(reason, result.stderr)


class ContactAndAccountIdContractTest(unittest.TestCase):
    """A-037: the operator address and the AWS account id are injected from protected settings and
    are never written into anything a commit would carry.

    This carries no acceptance ID on purpose. What it serves is a decision (A-037), not a
    `BA-xxx-Tn` clause, and `BA-006-T2` is about secrets reaching a bundle, an image layer or a log
    - a different question with its own check. Putting an ID here would make the aggregator count a
    clause this does not prove.

    It matches by SHAPE, unlike `scripts/check_secret_exposure.py`, which searches for the actual
    values and argues in its own docstring that patterns are wrong for that job. Both are right,
    because they run in different places: that one runs where the secrets exist (local, staging) and
    scans build outputs, so it can compare against a value; this one runs in a gate that holds no
    secrets at all, so the only thing it can ask is whether anything of that shape was written down.
    Same pairing as `check_browser_bundle_inputs`, which proves a credential was never handed over
    rather than scanning for its value.

    The previous version searched for one specific address, which meant writing that address into
    the repository in order to look for it - paying with the very data it guarded - and it could
    fire for no other value, not for the account id, and nowhere outside `scripts/aws/`.
    """

    def scan_paths(self) -> list[Path]:
        """Everything in this deliverable that a commit would carry into Git.

        `infra/` does not exist yet. It is named here so that its CDK context joins the scan by
        coming into existence, rather than by someone remembering to add it later.
        """
        roots = (AWS_SCRIPTS, ROOT / "docs" / "operations", ROOT / "infra")
        return [path
                for root in roots if root.exists()
                for path in sorted(root.rglob("*")) if path.is_file() and not {"node_modules", "dist", "cdk.out", "__pycache__"}.intersection(path.relative_to(root).parts)]

    def readable_files(self) -> list[tuple[Path, str]]:
        # latin-1 maps every byte, so no file is skipped and nothing is dropped; an address and an
        # account id are ASCII under either decoding. Skipping a file the scanner could not decode
        # is how it quietly stops covering whatever was added last.
        return [(path, path.read_bytes().decode("latin-1")) for path in self.scan_paths()]

    def test_no_contact_shaped_value_is_written_into_the_deliverable(self) -> None:
        for path, text in self.readable_files():
            # The count, never the match. A check that prints what it guards is its own defect, and
            # CI logs are read by more people than the protected settings are.
            self.assertEqual(0, len(CONTACT.findall(text)), f"contact-shaped value in {path}")

    def test_no_account_id_shaped_value_is_written_into_the_deliverable(self) -> None:
        for path, text in self.readable_files():
            self.assertEqual(0, len(account_ids(text)), f"12-digit account id in {path}")

    def test_the_scan_reaches_both_existing_roots_rather_than_an_empty_scope(self) -> None:
        # An empty scan is not a clean one. Without this, renaming or moving a directory turns both
        # checks above green while asking nothing at all.
        scanned = self.scan_paths()
        documents = ROOT / "docs" / "operations"
        self.assertTrue(any(AWS_SCRIPTS in path.parents for path in scanned), "scripts/aws unscanned")
        self.assertTrue(any(documents in path.parents for path in scanned), "docs/operations unscanned")


if __name__ == "__main__":
    unittest.main()
