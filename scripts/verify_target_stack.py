#!/usr/bin/env python3
"""Fail-closed validation for the M0 target-stack marker and Docker contract."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DIGEST_REFERENCE = re.compile(r"^[^@\s]+@sha256:[0-9a-f]{64}$")
FROM_LINE = re.compile(
    r"^\s*FROM\s+(?:--platform=\S+\s+)?(\S+)(?:\s+AS\s+(\S+))?\s*$",
    re.IGNORECASE,
)
COMPOSE_IMAGE_LINE = re.compile(r"^\s*image:\s*['\"]?([^'\"\s#]+)")
SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")


def load_json(path: Path, errors: list[str]) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        errors.append(f"Cannot read JSON {path.relative_to(ROOT)}: {error}")
        return {}
    if not isinstance(value, dict):
        errors.append(f"JSON root must be an object: {path.relative_to(ROOT)}")
        return {}
    return value


def check_package_scripts(
    path: Path, required_scripts: set[str], errors: list[str]
) -> None:
    package = load_json(path, errors)
    scripts = package.get("scripts")
    if not isinstance(scripts, dict):
        errors.append(f"Missing scripts object: {path.relative_to(ROOT)}")
        return
    missing = sorted(required_scripts - set(scripts))
    if missing:
        errors.append(
            f"Missing npm scripts in {path.relative_to(ROOT)}: {', '.join(missing)}"
        )


def check_dockerfile(
    path: Path, required_stages: set[str], errors: list[str]
) -> None:
    known_stages: set[str] = set()
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        errors.append(f"Cannot read {path.relative_to(ROOT)}: {error}")
        return

    for line_number, line in enumerate(lines, start=1):
        match = FROM_LINE.match(line)
        if not match:
            continue
        image, alias = match.groups()
        normalized_image = image.lower()
        if (
            normalized_image != "scratch"
            and normalized_image not in known_stages
            and not DIGEST_REFERENCE.fullmatch(image)
        ):
            errors.append(
                f"Unpinned external FROM in {path.relative_to(ROOT)}:{line_number}: "
                f"{image}"
            )
        if alias:
            known_stages.add(alias.lower())

    missing = sorted(required_stages - known_stages)
    if missing:
        errors.append(
            f"Missing Docker stages in {path.relative_to(ROOT)}: {', '.join(missing)}"
        )


def check_static_contract(errors: list[str]) -> None:
    marker = ROOT / ".nullnull-target-stack"
    try:
        marker_value = marker.read_text(encoding="utf-8").strip()
    except OSError as error:
        errors.append(f"Cannot read target-stack marker: {error}")
    else:
        if marker_value != "version=1":
            errors.append(".nullnull-target-stack must contain exactly: version=1")

    check_package_scripts(
        ROOT / "package.json",
        {"api:check", "security:scan", "infra:check"},
        errors,
    )
    check_package_scripts(
        ROOT / "apps/web/package.json",
        {"verify:ci", "test:e2e:integration"},
        errors,
    )
    check_dockerfile(
        ROOT / "apps/api/Dockerfile", {"test", "runtime"}, errors
    )
    check_dockerfile(
        ROOT / "apps/web/Dockerfile",
        {"test", "runtime", "e2e", "tooling"},
        errors,
    )

    wrapper_properties = ROOT / "apps/api/gradle/wrapper/gradle-wrapper.properties"
    try:
        properties = wrapper_properties.read_text(encoding="utf-8")
    except OSError as error:
        errors.append(f"Cannot read Gradle wrapper properties: {error}")
    else:
        checksum = next(
            (
                line.split("=", 1)[1].strip()
                for line in properties.splitlines()
                if line.startswith("distributionSha256Sum=")
            ),
            "",
        )
        if not SHA256.fullmatch(checksum):
            errors.append(
                "Gradle wrapper must set a 64-character distributionSha256Sum"
            )

    compose_path = ROOT / "compose.integration.yml"
    try:
        compose_lines = compose_path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        errors.append(f"Cannot read compose.integration.yml: {error}")
    else:
        for line_number, line in enumerate(compose_lines, start=1):
            match = COMPOSE_IMAGE_LINE.match(line)
            if match and not DIGEST_REFERENCE.fullmatch(match.group(1)):
                errors.append(
                    "Unpinned Compose image in "
                    f"compose.integration.yml:{line_number}: {match.group(1)}"
                )


def service_network_names(service: dict[str, Any]) -> set[str]:
    networks = service.get("networks", {})
    if isinstance(networks, dict):
        return set(networks)
    if isinstance(networks, list):
        return {value for value in networks if isinstance(value, str)}
    return set()


def check_compose_contract(path: Path, errors: list[str]) -> None:
    compose = load_json(path, errors)
    services = compose.get("services")
    networks = compose.get("networks")
    if not isinstance(services, dict) or not isinstance(networks, dict):
        errors.append("Normalized Compose config must contain services and networks")
        return

    required_services = {
        "postgres",
        "api-quality",
        "web-quality",
        "api-client-diff",
        "security-scan",
        "infra-plan",
        "api",
        "web",
        "e2e",
        "egress-denied",
    }
    missing_services = sorted(required_services - set(services))
    if missing_services:
        errors.append(
            f"Missing Compose integration services: {', '.join(missing_services)}"
        )

    internal_networks = {
        name
        for name, definition in networks.items()
        if isinstance(definition, dict) and definition.get("internal") is True
    }
    if "integration-internal" not in internal_networks:
        errors.append("Compose network integration-internal must set internal: true")

    for name in sorted(required_services & set(services)):
        service = services[name]
        if not isinstance(service, dict):
            errors.append(f"Compose service {name} must be an object")
            continue
        attached = service_network_names(service)
        if not attached:
            errors.append(f"Compose service {name} has no explicit network")
            continue
        non_internal = sorted(attached - internal_networks)
        if non_internal:
            errors.append(
                f"Compose service {name} can use non-internal networks: "
                f"{', '.join(non_internal)}"
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--compose-config",
        type=Path,
        help="Normalized JSON emitted by `docker compose config --format json`.",
    )
    args = parser.parse_args()

    errors: list[str] = []
    if args.compose_config:
        check_compose_contract(args.compose_config.resolve(), errors)
    else:
        check_static_contract(errors)

    if errors:
        for error in errors:
            print(f"target-stack error: {error}", file=sys.stderr)
        return 1

    mode = "compose" if args.compose_config else "static"
    print(f"target_stack_contract={mode}:valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
