"""Negative tests for the target-stack Docker and Compose contract verifier."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_target_stack
from verify_target_stack import (check_compose_contract, check_dockerfile,
                                 check_gradle_wrapper_pin, check_package_scripts)

PINNED_BASE = (
    'python:3.13-slim@sha256:'
    '9d2e5553305c7c7b0097999bb17187c69b921ccd6bc9d40e4bb5ebe652c00285'
)

REQUIRED_COMPOSE_SERVICES = {
    'postgres',
    'api-quality',
    'ai-quality',
    'web-quality',
    'api-client-diff',
    'security-scan',
    'infra-plan',
    'ai',
    'api',
    'web',
    'e2e',
    'egress-denied',
}


PINNED_WRAPPER = (
    'distributionBase=GRADLE_USER_HOME\n'
    'distributionSha256Sum=' + 'a' * 64 + '\n'
    'distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n'
    'validateDistributionUrl=true\n'
)


class GradleWrapperPinTests(unittest.TestCase):
    """BA-001-T1: the repository refuses a Gradle toolchain that is not pinned and verified."""

    def check(self, body: str) -> list[str]:
        errors: list[str] = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'gradle-wrapper.properties'
            path.write_text(body, encoding='utf-8')
            with mock.patch.object(verify_target_stack, 'ROOT', Path(directory)):
                check_gradle_wrapper_pin(path, errors)
        return errors

    def test_BA_001_T1_an_unpinned_gradle_distribution_is_refused(self):
        """BA-001-T1 고정되지 않았거나 검증이 꺼진 Gradle toolchain을 검사가 거부한다"""
        missing = self.check(PINNED_WRAPPER.replace('distributionSha256Sum=' + 'a' * 64 + '\n', ''))
        self.assertTrue(any('distributionSha256Sum' in e for e in missing), missing)
        unverified = self.check(PINNED_WRAPPER.replace('validateDistributionUrl=true',
                                                       'validateDistributionUrl=false'))
        self.assertTrue(any('validateDistributionUrl' in e for e in unverified), unverified)

    def test_a_pinned_and_verified_wrapper_is_accepted(self):
        """Without this the assertion above is satisfied by a checker that always complains."""
        self.assertEqual([], self.check(PINNED_WRAPPER))

    def test_a_checksum_that_is_not_64_hex_is_refused(self):
        """A truncated or non-hex sum would otherwise read as a pin while matching nothing."""
        for bad in ('a' * 63, 'z' * 64, ''):
            with self.subTest(sum=bad):
                body = PINNED_WRAPPER.replace('a' * 64, bad)
                self.assertTrue(any('distributionSha256Sum' in e for e in self.check(body)))

    def test_the_real_wrapper_this_repository_ships_satisfies_the_guard(self):
        """A guard nobody points at the real file can drift away from it without anyone noticing."""
        errors: list[str] = []
        check_gradle_wrapper_pin(ROOT / 'apps/api/gradle/wrapper/gradle-wrapper.properties', errors)
        self.assertEqual([], errors)


class PackageScriptContractTests(unittest.TestCase):
    """BA-001-T3's `task` half: the verifier refuses a package.json missing a required script.

    check_package_scripts shipped with its failure branch never exercised - the Docker `stage` and
    image `digest` halves of the same assertion each had a negative test and this one did not. A
    guard nobody has seen fire is the pattern this repository keeps finding; the assertion named
    three things and only two of them were proven.
    """

    def check(self, package: object, required: set[str]) -> list[str]:
        errors: list[str] = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'package.json'
            path.write_text(json.dumps(package), encoding='utf-8')
            with mock.patch.object(verify_target_stack, 'ROOT', Path(directory)):
                check_package_scripts(path, required, errors)
        return errors

    def test_BA_001_T3_a_missing_required_npm_script_fails(self):
        """BA-001-T3 필수 npm script가 없으면 target-stack 검증이 hard fail한다"""
        errors = self.check({'scripts': {'build': 'vite build'}}, {'build', 'typecheck'})
        self.assertTrue(any('Missing npm scripts' in e and 'typecheck' in e for e in errors), errors)

    def test_every_required_script_present_is_accepted(self):
        """Without this the assertion above is satisfied by a checker that always complains."""
        self.assertEqual([], self.check(
            {'scripts': {'build': 'vite build', 'typecheck': 'tsc -b'}}, {'build', 'typecheck'}))

    def test_a_package_without_a_scripts_object_is_refused_rather_than_read_as_empty(self):
        """An absent scripts object would otherwise subtract to "nothing missing" and pass."""
        self.assertTrue(any('Missing scripts object' in e for e in self.check({}, {'build'})))


class DockerfileContractTests(unittest.TestCase):
    def check(self, body: str, required_stages: set[str]) -> list[str]:
        errors: list[str] = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'Dockerfile'
            path.write_text(body, encoding='utf-8')
            # Error messages are relative to ROOT, so point it at the fixture tree.
            with mock.patch.object(verify_target_stack, 'ROOT', Path(directory)):
                check_dockerfile(path, required_stages, errors)
        return errors

    def test_missing_runtime_stage_fails(self):
        errors = self.check(
            f'FROM {PINNED_BASE} AS build\nFROM build AS test\n',
            {'test', 'runtime'},
        )
        self.assertTrue(
            any('Missing Docker stages' in e and 'runtime' in e for e in errors),
            errors,
        )

    def test_unpinned_external_base_image_fails(self):
        errors = self.check(
            'FROM python:3.13-slim AS build\n'
            'FROM build AS test\n'
            'FROM build AS runtime\n',
            {'test', 'runtime'},
        )
        self.assertTrue(
            any('Unpinned external FROM' in e for e in errors),
            errors,
        )

    def test_recommendation_service_dockerfile_satisfies_the_contract(self):
        errors: list[str] = []
        check_dockerfile(ROOT / 'apps/ai/Dockerfile', {'test', 'runtime'}, errors)
        self.assertEqual([], errors)


class StaticContractWiringTests(unittest.TestCase):
    def test_static_contract_checks_the_recommendation_dockerfile(self):
        # The rest of the static contract needs the B01 marker and apps/web, so only
        # the Dockerfile wiring is asserted here.
        errors: list[str] = []
        with mock.patch.object(verify_target_stack, 'check_dockerfile') as checked:
            verify_target_stack.check_static_contract(errors)
        requested = {
            (call.args[0], frozenset(call.args[1]))
            for call in checked.call_args_list
        }
        self.assertIn(
            (ROOT / 'apps/ai/Dockerfile', frozenset({'test', 'runtime'})),
            requested,
        )


class ComposeContractTests(unittest.TestCase):
    def check(self, config: dict) -> list[str]:
        errors: list[str] = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'compose-config.json'
            path.write_text(json.dumps(config), encoding='utf-8')
            check_compose_contract(path, errors)
        return errors

    @staticmethod
    def config(services: dict, networks: dict | None = None) -> dict:
        return {
            'services': services,
            'networks': networks or {'integration-internal': {'internal': True}},
        }

    @staticmethod
    def internal_services(names) -> dict:
        return {
            name: {'networks': {'integration-internal': None}} for name in names
        }

    def test_required_services_on_the_internal_network_pass(self):
        errors = self.check(
            self.config(self.internal_services(REQUIRED_COMPOSE_SERVICES))
        )
        self.assertEqual([], errors)

    def test_missing_recommendation_services_fail(self):
        services = self.internal_services(
            REQUIRED_COMPOSE_SERVICES - {'ai', 'ai-quality'}
        )
        errors = self.check(self.config(services))
        self.assertIn(
            'Missing Compose integration services: ai, ai-quality',
            errors,
        )

    def test_recommendation_service_outside_the_internal_network_fails(self):
        services = self.internal_services(REQUIRED_COMPOSE_SERVICES)
        services['ai'] = {'networks': {'public': None}}
        errors = self.check(
            self.config(
                services,
                {
                    'integration-internal': {'internal': True},
                    'public': {'internal': False},
                },
            )
        )
        self.assertIn(
            'Compose service ai can use non-internal networks: public',
            errors,
        )


if __name__ == '__main__':
    unittest.main()
