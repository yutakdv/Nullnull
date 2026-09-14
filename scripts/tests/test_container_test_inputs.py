"""Negative controls for the container test-input check.

The case this exists for happened twice in this repository, and the second time the Dockerfile was
already carrying a comment about the first. Both had the same signature: api-quality green,
docker-integration dead with NoSuchFileException on the same commit. Every case below makes that
situation and asserts the check turns red, because a check nobody has seen fire is a belief.

The last case is the one that matters most for this particular check: a scan that compared against
an empty allowlist, or read no sources, must refuse rather than report a clean result.

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
CHECK = ROOT / "scripts" / "check_container_test_inputs.py"

DOCKERFILE = """FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace/apps/api
COPY apps/api/src ./src
COPY docs/api/openapi.yaml /workspace/docs/api/openapi.yaml

FROM build AS test

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/apps/api/build/libs/nullnull-api.jar /app/nullnull-api.jar
"""


class ContainerTestInputs(unittest.TestCase):

    def run_check(self, dockerfile: Path, source_root: Path) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(CHECK), "--dockerfile", str(dockerfile),
             "--source-root", str(source_root)],
            capture_output=True, text=True, check=False)

    def in_build_stage(self, copy: str) -> str:
        """Put a COPY inside the build stage. Appending puts it in `runtime`, which is a different
        thing entirely - the fixture for this check tripped over exactly what the check catches."""
        marker = "COPY docs/api/openapi.yaml /workspace/docs/api/openapi.yaml\n"
        assert DOCKERFILE.count(marker) == 1
        return DOCKERFILE.replace(marker, marker + copy)

    def fixture(self, directory: str, java: str, dockerfile: str = DOCKERFILE) -> tuple[Path, Path]:
        base = Path(directory)
        (base / "src").mkdir(parents=True, exist_ok=True)
        (base / "src" / "SomeTest.java").write_text(java, encoding="utf-8")
        path = base / "Dockerfile"
        path.write_text(dockerfile, encoding="utf-8")
        return path, base / "src"

    def test_a_path_the_image_carries_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            dockerfile, source = self.fixture(
                directory, 'Path.of("../../docs/api/openapi.yaml");\n')
            result = self.run_check(dockerfile, source)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("container_test_inputs=provided", result.stdout)

    def test_a_path_the_image_does_not_carry_is_caught(self):
        with tempfile.TemporaryDirectory() as directory:
            # The real second case: a test pinning a label to the document that decided it.
            dockerfile, source = self.fixture(
                directory, 'Path.of("../../docs/data/SOURCE_CATALOG.md");\n')
            result = self.run_check(dockerfile, source)
        self.assertEqual(result.returncode, 1)
        self.assertIn("container_test_inputs=missing", result.stderr)
        self.assertIn("SOURCE_CATALOG.md", result.stderr)
        self.assertIn("SomeTest.java", result.stderr)

    def test_a_file_under_a_copied_directory_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            # The fixtures corpus is copied as a directory, and tests open files several levels in.
            dockerfile, source = self.fixture(
                directory,
                'Path.of("../../packages/contracts/fixtures/candidates/saved.json");\n',
                self.in_build_stage(
                    "COPY packages/contracts/fixtures /workspace/packages/contracts/fixtures\n"))
            result = self.run_check(dockerfile, source)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_a_copy_that_only_the_runtime_stage_makes_does_not_count(self):
        with tempfile.TemporaryDirectory() as directory:
            # The runtime stage never runs a suite, so a file arriving only there does not help the
            # test that opens it - and a check crediting it would report clean on a red build.
            dockerfile, source = self.fixture(
                directory, 'Path.of("../../docs/data/SOURCE_CATALOG.md");\n',
                DOCKERFILE + "COPY docs/data/SOURCE_CATALOG.md /workspace/docs/data/SOURCE_CATALOG.md\n")
            result = self.run_check(dockerfile, source)
        self.assertEqual(result.returncode, 1, "the trailing COPY lands in the runtime stage")
        self.assertIn("SOURCE_CATALOG.md", result.stderr)

    def test_a_dockerfile_whose_test_stages_copy_nothing_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            dockerfile, source = self.fixture(
                directory, 'Path.of("../../docs/api/openapi.yaml");\n',
                "FROM eclipse-temurin:21-jre AS runtime\nWORKDIR /app\n")
            result = self.run_check(dockerfile, source)
        self.assertEqual(result.returncode, 1, "comparing against an empty allowlist is not a verdict")
        self.assertIn("container_test_inputs=blocked", result.stderr)

    def test_sources_that_open_nothing_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            dockerfile, source = self.fixture(directory, "class SomeTest {}\n")
            result = self.run_check(dockerfile, source)
        self.assertEqual(result.returncode, 1, "zero findings must not read as a clean scan")
        self.assertIn("container_test_inputs=blocked", result.stderr)

    def test_a_missing_dockerfile_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            _, source = self.fixture(directory, 'Path.of("../../docs/api/openapi.yaml");\n')
            result = self.run_check(Path(directory) / "absent", source)
        self.assertEqual(result.returncode, 1)
        self.assertIn("container_test_inputs=blocked", result.stderr)

    def test_this_repository_satisfies_the_check(self):
        result = subprocess.run(
            [sys.executable, str(CHECK)], cwd=ROOT, capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
