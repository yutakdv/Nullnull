#!/usr/bin/env python3
"""Validate Nullnull's canonical documentation and machine contracts.

The script intentionally uses only the standard library so it works before the
target stack is scaffolded. Redocly and AJV remain the normative OpenAPI/JSON
Schema validators in CI; this adds repository-specific traceability checks they
cannot know about.
"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[1]
OPENAPI_PATH = ROOT / "docs/api/openapi.yaml"
SOURCE_CATALOG_PATH = ROOT / "docs/data/SOURCE_CATALOG.md"
INVENTORY_PATH = ROOT / "docs/product/FUNCTIONAL_INVENTORY.md"
FIGMA_PATH = ROOT / "docs/design/FIGMA_HANDOFF.md"
COMPONENT_PATH = ROOT / "docs/design/COMPONENT_CATALOG.md"
FIGMA_CHANGE_PATH = ROOT / "docs/design/FIGMA_CHANGE_REQUESTS.md"
PM_AUDIT_PATH = ROOT / "docs/project/PM_CONSISTENCY_AUDIT.md"
EVENT_SCHEMA_PATH = ROOT / "docs/contracts/events.schema.json"
EVENT_EXAMPLE_PATH = ROOT / "docs/contracts/events.example.json"
INTEGRATION_WORKFLOW_PATH = ROOT / ".github/workflows/integration.yml"
INTEGRATION_SCRIPT_PATH = ROOT / "scripts/integration-test.sh"
TARGET_STACK_VERIFIER_PATH = ROOT / "scripts/verify_target_stack.py"
COMPOSE_PATH = ROOT / "compose.integration.yml"
CONTEST_MATRIX_PATH = ROOT / "docs/contest/COMPETITION_COMPLIANCE_MATRIX.md"
EVIDENCE_LEDGER_PATH = ROOT / "docs/contest/EVIDENCE_LEDGER_TEMPLATE.md"
CONTEST_CRITERIA_PATH = (
    ROOT / "docs/contest/2026-관광데이터-활용-공모전-공지-심사기준.md"
)

HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
OPERATION_PREFIXES = (
    "get",
    "list",
    "create",
    "update",
    "delete",
    "replace",
    "set",
    "remove",
    "reorder",
    "parse",
    "remap",
    "confirm",
    "add",
    "save",
    "unsave",
    "record",
    "ingest",
    "mark",
    "decide",
    "revert",
    "cancel",
)

EXPECTED_COMPONENTS = {
    "C01": "Card / Candidate",
    "C02": "Sheet / TripPicker",
    "C03": "Action / DecisionBar",
    "C04": "Action / TripAddButton",
    "C05": "Form / OptimizationScope",
    "C06": "Data / MetricDelta",
    "C07": "Data / StateLabel",
    "C08": "Form / LockControl",
    "C09": "Nav / Segment",
    "C10": "Form / Search",
    "C11": "Action / Bottom CTA",
    "C12": "Nav / TabBar",
    "C13": "icon/heart",
    "C14": "icon/location",
    "C15": "icon/arrow-right",
    "C16": "icon/chevron-down",
    "C17": "icon/plus",
    "C18": "icon/check",
    "C19": "icon/close",
    "C20": "icon/chevron-right",
    "C21": "icon/back",
    "C22": "icon/settings",
    "C23": "icon/bell",
    "C24": "icon/search",
    "C25": "icon/heart-like-filled",
    "C26": "icon/heart-like",
    "C27": "icon/pin-visit-filled",
    "C28": "icon/pin-visit",
    "C29": "icon/reservation",
    "C30": "icon/time-lock",
    "C31": "icon/date-lock",
    "C32": "Compare / Place",
    "C33": "Sheet / SaveCandidate",
    "C34": "Card / FeedPost",
    "C35": "Feedback / Toast",
    "C36": "icon/bookmark",
    "C37": "Map / Optimization",
    "C38": "Card / TripItem",
    "C39": "Form / Pick",
    "C40": "Data / Must Visit",
    "C41": "Map / Marker",
    "C42": "Action / 장소 추가",
    "C43": "Data / Distance",
    "C44": "Sheet / Grab",
    "C45": "Map / Base",
    "C46": "Data / Badge",
    "C47": "Data / Tag",
    "C48": "Form / Chip",
    "C49": "Nav / NavBar",
}

FIGMA_GROUP_IDS = {
    "511:3934",
    "511:3937",
    "511:3940",
    "511:3943",
    "511:3946",
    "511:3949",
    "511:3952",
    "511:3955",
    "511:3958",
}


def canonical_markdown_files() -> list[Path]:
    files = [
        ROOT / "README.md",
        ROOT / "AGENTS.md",
        ROOT / "CLAUDE.md",
        ROOT / "CONTRIBUTING.md",
        ROOT / "SECURITY.md",
        ROOT / "과제2_널널_웹앱구현_기획서_Final.md",
        ROOT / ".github/pull_request_template.md",
    ]
    for directory in (
        "docs/api",
        "docs/architecture",
        "docs/contest",
        "docs/data",
        "docs/decisions",
        "docs/design",
        "docs/engineering",
        "docs/operations",
        "docs/product",
        "docs/project",
        "docs/roles",
        "docs/security",
    ):
        files.extend((ROOT / directory).glob("*.md"))
    files.append(ROOT / "docs/README.md")
    return sorted(set(files))


def validate_local_links(problems: list[str]) -> None:
    link_pattern = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
    for markdown_path in canonical_markdown_files():
        if not markdown_path.exists():
            problems.append(f"missing canonical document: {markdown_path.relative_to(ROOT)}")
            continue
        for line_number, line in enumerate(markdown_path.read_text(encoding="utf-8").splitlines(), 1):
            for raw_target in link_pattern.findall(line):
                target = raw_target.strip()
                if target.startswith("<") and ">" in target:
                    target = target[1 : target.index(">")]
                else:
                    target = target.split(maxsplit=1)[0]
                if not target or target.startswith(("#", "http://", "https://", "mailto:")):
                    continue
                local_part = unquote(target.split("#", 1)[0])
                resolved = (markdown_path.parent / local_part).resolve()
                try:
                    resolved.relative_to(ROOT.resolve())
                except ValueError:
                    problems.append(
                        f"{markdown_path.relative_to(ROOT)}:{line_number}: local link escapes repository: {target}"
                    )
                    continue
                if not resolved.exists():
                    problems.append(
                        f"{markdown_path.relative_to(ROOT)}:{line_number}: missing local link target: {target}"
                    )


def validate_openapi(problems: list[str]) -> set[str]:
    text = OPENAPI_PATH.read_text(encoding="utf-8")
    lines = text.splitlines()
    if not re.search(r"^openapi:\s*3\.1\.\d+\s*$", text, re.MULTILINE):
        problems.append("OpenAPI document must declare a 3.1.x version")
        return set()

    operation_ids = re.findall(r"^\s+operationId:\s*([A-Za-z][A-Za-z0-9]*)\s*$", text, re.MULTILINE)
    path_methods = re.findall(
        r"^    (?:get|put|post|delete|options|head|patch|trace):\s*$", text, re.MULTILINE
    )
    if len(operation_ids) != len(path_methods):
        problems.append(
            f"OpenAPI path method/operationId count differs: {len(path_methods)} methods, {len(operation_ids)} IDs"
        )

    for operation_id, count in Counter(operation_ids).items():
        if count > 1:
            problems.append(f"duplicate OpenAPI operationId: {operation_id}")

    definitions: dict[str, set[str]] = {}
    in_components = False
    current_section: str | None = None
    for line in lines:
        if line == "components:":
            in_components = True
            continue
        if in_components and line and not line.startswith(" "):
            in_components = False
            current_section = None
        if not in_components:
            continue
        section_match = re.match(r"^  ([A-Za-z][A-Za-z0-9_-]*):\s*$", line)
        if section_match:
            current_section = section_match.group(1)
            definitions.setdefault(current_section, set())
            continue
        item_match = re.match(r"^    ([^:#][^:]*):(?:\s.*)?$", line)
        if item_match and current_section:
            definitions[current_section].add(item_match.group(1).strip().strip('"'))

    references = re.findall(r'\$ref:\s*["\']([^"\']+)["\']', text)
    for reference in references:
        match = re.fullmatch(r"#/components/([^/]+)/([^/]+)", reference)
        if not match:
            problems.append(f"external or unsupported $ref in baseline contract: {reference}")
            continue
        section, encoded_name = match.groups()
        name = encoded_name.replace("~1", "/").replace("~0", "~")
        if name not in definitions.get(section, set()):
            problems.append(f"unresolved $ref: {reference}")

    if re.search(r"^\s+allOf:\s*$", text, re.MULTILINE):
        problems.append(
            "OpenAPI baseline contains allOf; closed schema extension is forbidden—flatten or use an explicitly reviewed 2020-12 composition"
        )

    return set(operation_ids)


def validate_inventory(operation_ids: set[str], problems: list[str]) -> None:
    if not INVENTORY_PATH.exists():
        return
    text = INVENTORY_PATH.read_text(encoding="utf-8")
    references: set[str] = set()
    for line in text.splitlines():
        if not line.startswith("|") or "FR-" not in line:
            continue
        columns = line.split("|")
        if len(columns) < 7:
            continue
        api_column = columns[5]
        for token in re.findall(r"`([a-z][A-Za-z0-9]+)`", api_column):
            if token.startswith(OPERATION_PREFIXES):
                references.add(token)
    for missing in sorted(references - operation_ids):
        problems.append(f"functional inventory references missing operationId: {missing}")


def validate_figma_inventory(problems: list[str]) -> None:
    if not FIGMA_PATH.exists():
        return
    handoff = FIGMA_PATH.read_text(encoding="utf-8")
    node_ids = set(re.findall(r"`(\d+:\d+)`", handoff))
    missing_groups = sorted(FIGMA_GROUP_IDS - node_ids)
    if missing_groups:
        problems.append("Figma handoff is missing audited group nodes: " + ", ".join(missing_groups))
    screen_ids = node_ids - FIGMA_GROUP_IDS
    if len(screen_ids) != 52:
        problems.append(
            f"Figma handoff screen inventory differs from current audit: expected 52 unique nodes, found {len(screen_ids)}"
        )
    if not COMPONENT_PATH.exists():
        return
    component_text = COMPONENT_PATH.read_text(encoding="utf-8")
    rows = re.findall(r"^\|\s+(C\d{2})\s+\|\s+`([^`]+)`\s+\|", component_text, re.MULTILINE)
    row_counts = Counter(component_id for component_id, _ in rows)
    duplicate_ids = sorted(component_id for component_id, count in row_counts.items() if count > 1)
    if duplicate_ids:
        problems.append("component catalog has duplicate IDs: " + ", ".join(duplicate_ids))
    actual_components = dict(rows)
    missing_ids = sorted(set(EXPECTED_COMPONENTS) - set(actual_components))
    extra_ids = sorted(set(actual_components) - set(EXPECTED_COMPONENTS))
    if missing_ids:
        problems.append("component catalog is missing IDs: " + ", ".join(missing_ids))
    if extra_ids:
        problems.append("component catalog has unexpected IDs: " + ", ".join(extra_ids))
    for component_id, expected_name in EXPECTED_COMPONENTS.items():
        actual_name = actual_components.get(component_id)
        if actual_name is not None and actual_name != expected_name:
            problems.append(
                f"component catalog {component_id} must use exact Figma name {expected_name!r}; found {actual_name!r}"
            )

    require_fragments(
        FIGMA_CHANGE_PATH,
        tuple(f"FCR-{index:03d}" for index in range(1, 16))
        + ("상태: Open", "P0 ITEM 전용 READY"),
        problems,
    )
    require_fragments(
        PM_AUDIT_PATH,
        ("공모전 출시·제출은 NO-GO", "실행 가능한 제품 gate 0개 완료", "G5 제출 후보"),
        problems,
    )


def extract_schema_block(openapi_text: str, schema_name: str) -> str | None:
    start = re.search(rf"^    {re.escape(schema_name)}:\s*$", openapi_text, re.MULTILINE)
    if not start:
        return None
    remainder = openapi_text[start.end() :]
    next_schema = re.search(r"^    [A-Za-z][A-Za-z0-9]+:\s*$", remainder, re.MULTILINE)
    end = start.end() + (next_schema.start() if next_schema else len(remainder))
    return openapi_text[start.start() : end]


def validate_product_contract_alignment(problems: list[str]) -> None:
    """Protect audited Figma/product/OpenAPI alignments from regressing."""

    openapi_text = OPENAPI_PATH.read_text(encoding="utf-8")
    source_catalog_text = SOURCE_CATALOG_PATH.read_text(encoding="utf-8")
    create_trip = extract_schema_block(openapi_text, "CreateTripRequest")
    update_trip = extract_schema_block(openapi_text, "UpdateTripRequest")
    replace_interests = extract_schema_block(openapi_text, "ReplaceInterestsRequest")
    contract_fragments = {
        "CreateOptimizationRequest": (
            "CreateItemOptimizationRequest",
            "CreateDayOptimizationRequest",
            "CreateTripOptimizationRequest",
            "propertyName: scope",
        ),
        "CreateItemOptimizationRequest": (
            "required: [scope, targetItemId, inputTripVersion, includeCandidates]",
            "const: ITEM",
        ),
        "CreateDayOptimizationRequest": (
            "required: [scope, targetDate, inputTripVersion, includeCandidates]",
            "const: DAY",
        ),
        "CreateTripOptimizationRequest": (
            "required: [scope, inputTripVersion, includeCandidates]",
            "const: TRIP",
        ),
        "OptimizationDecision": (
            "ApplyOptimizationDecision",
            "KeepOptimizationDecision",
            "RevertOptimizationDecision",
            "propertyName: decision",
        ),
        "ApplyOptimizationDecision": (
            "const: APPLY",
            "- beforeRevisionId",
            "- afterRevisionId",
            "- revertUntil",
            "Exactly 24 hours after decidedAt",
        ),
        "KeepOptimizationDecision": ("const: KEEP",),
        "RevertOptimizationDecision": (
            "const: REVERT",
            "- revertedDecisionId",
            "- beforeRevisionId",
            "- afterRevisionId",
        ),
        "DataProvenance": (
            "officialUrl",
            "licenseUrl",
            "SEOUL_CITYDATA",
            "서울특별시 「서울시 실시간 도시데이터」",
        ),
    }

    if create_trip is None:
        problems.append("OpenAPI is missing CreateTripRequest")
    else:
        required = re.search(r"required:\s*\[([^\]]+)\]", create_trip)
        required_fields = {
            field.strip() for field in required.group(1).split(",")
        } if required else set()
        expected_required = {"startDate", "endDate", "timezone", "planningLevel", "interests"}
        if required_fields != expected_required:
            problems.append(
                "CreateTripRequest required fields must match the title-less Figma wizard: "
                + ", ".join(sorted(expected_required))
            )
        if "minItems: 0" not in create_trip:
            problems.append("CreateTripRequest.interests must allow an explicit empty array")
        if "deterministic locale-aware default" not in create_trip:
            problems.append("CreateTripRequest.title must document its deterministic default")

    if update_trip is None or not re.search(r"^        timezone:\s*$", update_trip, re.MULTILINE):
        problems.append("UpdateTripRequest must expose the timezone promised by PRODUCT_SPEC")
    if replace_interests is None or "minItems: 0" not in replace_interests:
        problems.append("ReplaceInterestsRequest.interests must allow an explicit empty array")

    for schema_name, fragments in contract_fragments.items():
        schema = extract_schema_block(openapi_text, schema_name)
        if schema is None:
            problems.append(f"OpenAPI is missing {schema_name}")
            continue
        for fragment in fragments:
            if fragment not in schema:
                problems.append(
                    f"{schema_name} is missing the reviewed Frontend contract: {fragment}"
                )

    keep_decision = extract_schema_block(openapi_text, "KeepOptimizationDecision")
    if keep_decision and any(
        field in keep_decision
        for field in ("beforeRevisionId", "afterRevisionId", "revertUntil")
    ):
        problems.append("KEEP must not expose revision or revert fields")

    if "REVERT_WINDOW_EXPIRED" not in openapi_text:
        problems.append("FCR-015 requires a distinct REVERT_WINDOW_EXPIRED Problem code")

    for fragment in (
        "code: SEOUL_CITYDATA",
        "displayName: 서울시 실시간 도시데이터",
        "licenseName: 공공누리 제1유형",
        "출처: 서울특별시 「서울시 실시간 도시데이터」",
    ):
        if fragment not in source_catalog_text:
            problems.append(f"SOURCE_CATALOG is missing the FCR-011 contract: {fragment}")


def validate_json_files(problems: list[str]) -> None:
    for path in (EVENT_SCHEMA_PATH, EVENT_EXAMPLE_PATH):
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except Exception as error:
            problems.append(f"cannot parse {path.relative_to(ROOT)}: {error}")


def require_fragments(
    path: Path, fragments: tuple[str, ...], problems: list[str]
) -> None:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        problems.append(f"cannot read {path.relative_to(ROOT)}: {error}")
        return
    for fragment in fragments:
        if fragment not in text:
            problems.append(
                f"{path.relative_to(ROOT)} is missing required delivery contract: {fragment}"
            )


def validate_delivery_contract(problems: list[str]) -> None:
    """Keep branch, integration, and contest fail-closed rules from drifting."""

    require_fragments(
        INTEGRATION_WORKFLOW_PATH,
        (
            "name: docker-integration",
            "HEAD_REPOSITORY:",
            "BASE_REPOSITORY:",
            'if [[ "${HEAD_REPOSITORY}" != "${BASE_REPOSITORY}" ]]',
            "frontend|backend|dependabot/*",
            "if-no-files-found: error",
            "retention-days: 30",
        ),
        problems,
    )
    require_fragments(
        INTEGRATION_SCRIPT_PATH,
        (
            'echo "not-completed" >"${artifact_dir}/mode.txt"',
            'echo "running" >"${artifact_dir}/status.txt"',
            '"scripts/verify_target_stack.py"',
            '"${compose[@]}" run --rm api-client-diff',
            '"${compose[@]}" run --rm security-scan',
            '"${compose[@]}" run --rm infra-plan',
            '"${compose[@]}" run --rm egress-denied',
        ),
        problems,
    )
    require_fragments(
        COMPOSE_PATH,
        (
            "api-client-diff:",
            "security-scan:",
            "infra-plan:",
            "egress-denied:",
            "integration-internal:",
            "internal: true",
        ),
        problems,
    )
    require_fragments(
        TARGET_STACK_VERIFIER_PATH,
        (
            'marker_value != "version=1"',
            "DIGEST_REFERENCE",
            '"integration-internal" not in internal_networks',
            '"egress-denied"',
        ),
        problems,
    )
    require_fragments(
        CONTEST_MATRIX_PATH,
        (
            "CMP-SUB-011",
            "CMP-AI-001",
            "서비스명·개요·부문/유형·지정과제",
            "credential 입력 확인 boolean",
            "EVIDENCE_LEDGER_TEMPLATE.md",
        ),
        problems,
    )
    require_fragments(
        EVIDENCE_LEDGER_PATH,
        (
            "CMP-SUB-011",
            "CMP-AI-001",
            "NOT_STARTED",
            "divisionExactLabel: ②-2 웹·앱 구현 부문",
            "ktoOperationAccountState:",
            "ktoCredentialEntryVerified: false",
        ),
        problems,
    )
    require_fragments(
        CONTEST_CRITERIA_PATH,
        (
            "자료 불일치 주의",
            "공식 Notion 본문은 이메일형에도 `2026openapi!`",
            "공식 제출 매뉴얼 6쪽은 이메일형을 `2026openapi`",
            "생성형 AI와 AI 코딩 도구",
            "평가의 핵심은 도구 사용량이 아니라 안정적으로 구동되는 완성 서비스",
        ),
        problems,
    )


def main() -> int:
    problems: list[str] = []
    validate_local_links(problems)
    operation_ids = validate_openapi(problems)
    validate_inventory(operation_ids, problems)
    validate_figma_inventory(problems)
    validate_product_contract_alignment(problems)
    validate_json_files(problems)
    validate_delivery_contract(problems)

    if problems:
        print("Documentation validation failed:", file=sys.stderr)
        for problem in problems:
            print(f"- {problem}", file=sys.stderr)
        return 1

    print(
        "Documentation validation passed: "
        f"{len(operation_ids)} OpenAPI operations, local links, exact Figma/component inventory, "
        "product-contract alignment, JSON syntax, delivery policy, and contest evidence contracts."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
