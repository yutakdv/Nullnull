"""Negative controls for the build-output secret scan.

No acceptance ID leads these docstrings. They used to lead with BA-006-T2, which made
run_script_tests.py publish them as JUnit testcase names carrying that ID - so a card clause
about this repository's build outputs was satisfied by fixtures that only prove the check
fires. No card owns this check while no gate runs it, and borrowing one is the shape #195
describes.

Every case here makes the thing the check exists to catch and asserts it turns red, then asserts the
clean case is green. The two that matter most are the chunk-boundary case - a secret split across
two reads would be missed by a scanner that reads a file in blocks and forgets the tail - and the
one asserting the check never prints the value it found, because a guard that leaks what it guards
into a CI log is worse than no guard.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECK = ROOT / "scripts" / "check_secret_exposure.py"
SECRET = "kto-service-key-0123456789abcdef0123456789abcdef"
VARIABLE = "NULLNULL_TEST_SECRET"


class SecretExposureScan(unittest.TestCase):

    def run_check(self, *arguments: str, secret: str | None = SECRET) -> subprocess.CompletedProcess:
        environment = dict(os.environ)
        environment.pop(VARIABLE, None)
        if secret is not None:
            environment[VARIABLE] = secret
        return subprocess.run(
            [sys.executable, str(CHECK), *arguments, "--secret-env", VARIABLE],
            capture_output=True, text=True, env=environment, check=False)

    def test_a_clean_bundle_passes(self):
        """빌드 산출물에 runtime secret이 없으면 통과한다"""
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "assets"
            bundle.mkdir()
            (bundle / "index.js").write_text("export const apiBase = '/api/v1';\n")
            result = self.run_check(str(bundle))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("secret_exposure=clean", result.stdout)

    def test_a_secret_in_the_bundle_is_caught(self):
        """frontend bundle에 들어간 secret을 검사가 잡는다"""
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "assets"
            bundle.mkdir()
            # What a VITE_-prefixed variable does on its own: inlined into the bundle at build time.
            (bundle / "index.js").write_text(f"const key='{SECRET}';\n")
            result = self.run_check(str(bundle))
        self.assertEqual(result.returncode, 1)
        self.assertIn("secret_exposure=leaked", result.stderr)
        self.assertIn(VARIABLE, result.stderr)

    def test_a_secret_in_a_log_is_caught(self):
        """log에 남은 secret을 검사가 잡는다"""
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "api.log"
            log.write_text(f'{{"header":"Authorization: {SECRET}"}}\n')
            result = self.run_check(str(log))
        self.assertEqual(result.returncode, 1)
        self.assertIn("secret_exposure=leaked", result.stderr)

    def test_a_secret_in_an_image_layer_is_caught(self):
        """image layer에 남은 secret을 검사가 잡는다"""
        with tempfile.TemporaryDirectory() as directory:
            # A layer export is a tar, so the scan has to read bytes rather than decoded text.
            layer = Path(directory) / "layer.tar"
            layer.write_bytes(b"\x00\x01binary\x00" + SECRET.encode() + b"\x00\xff")
            result = self.run_check(str(layer))
        self.assertEqual(result.returncode, 1)
        self.assertIn("secret_exposure=leaked", result.stderr)

    def test_a_secret_split_across_two_reads_is_still_found(self):
        """읽기 경계에 걸친 secret도 찾는다"""
        with tempfile.TemporaryDirectory() as directory:
            big = Path(directory) / "bundle.js"
            chunk = 1 << 20
            # Straddle the boundary: half the secret ends the first read, half begins the second.
            head = b"a" * (chunk - len(SECRET) // 2)
            big.write_bytes(head + SECRET.encode() + b"b" * 16)
            result = self.run_check(str(big))
        self.assertEqual(result.returncode, 1, "a secret on the chunk boundary must not be missed")
        self.assertIn("secret_exposure=leaked", result.stderr)

    def test_the_check_never_prints_the_secret_it_found(self):
        """검사는 찾은 secret 값을 출력하지 않는다"""
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "index.js"
            bundle.write_text(f"const key='{SECRET}';\n")
            result = self.run_check(str(bundle))
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(SECRET, result.stdout)
        self.assertNotIn(SECRET, result.stderr)

    def test_a_scan_with_nothing_to_look_for_is_refused(self):
        """찾을 secret이 없는 실행은 통과가 아니라 실패다"""
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "index.js"
            bundle.write_text("clean\n")
            result = self.run_check(str(bundle), secret=None)
        self.assertEqual(result.returncode, 1, "an empty scan must not read as a clean one")
        self.assertIn("secret_exposure=blocked", result.stderr)

    def test_a_secret_too_short_to_be_one_is_refused(self):
        """값이 사실상 비어 있는 변수는 secret으로 세지 않는다"""
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / "index.js"
            bundle.write_text("clean\n")
            result = self.run_check(str(bundle), secret="x")
        self.assertEqual(result.returncode, 1)
        self.assertIn("secret_exposure=blocked", result.stderr)

    def test_a_missing_target_is_refused(self):
        """존재하지 않는 대상을 겨눈 실행은 통과가 아니라 실패다"""
        result = self.run_check(str(ROOT / "does-not-exist"))
        self.assertEqual(result.returncode, 1)
        self.assertIn("secret_exposure=blocked", result.stderr)

    def test_an_empty_target_directory_is_refused(self):
        """파일이 0건인 디렉터리를 겨눈 실행은 통과가 아니라 실패다"""
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_check(directory)
        self.assertEqual(result.returncode, 1, "a directory with no files is not a clean scan")
        self.assertIn("secret_exposure=blocked", result.stderr)


if __name__ == "__main__":
    unittest.main()
