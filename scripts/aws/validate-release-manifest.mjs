#!/usr/bin/env node

import { readFileSync } from "node:fs";

const [manifestPath] = process.argv.slice(2);
if (!manifestPath) {
  console.error("release_manifest=failed reason=missing-path");
  process.exit(1);
}

let manifest;
try {
  manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
} catch {
  console.error("release_manifest=failed reason=unreadable-or-invalid-json");
  process.exit(1);
}

const errors = [];
if (!manifest || typeof manifest !== "object" || Array.isArray(manifest)) {
  console.error("release_manifest=failed reason=invalid-object");
  process.exit(1);
}
const allowed = new Set([
  "releaseVersion",
  "gitSha",
  "apiImageDigest",
  "aiImageDigest",
  "webArtifactSha256",
  "openApiSha256",
  "eventSchemaSha256",
  "cdkAssemblySha256",
  "aiCatalogVersion",
  "flywayChecksums",
  "buildRunId",
  "approvedByRoles",
  "sourceState",
  "sourceOverlaySha256",
  "sourceOverlayPaths",
]);
if (Object.keys(manifest).some((k) => !allowed.has(k)))
  errors.push("unknown-field");

const sha = /^sha256:[a-f0-9]{64}$/;
const requiredShaFields = [
  "apiImageDigest",
  "aiImageDigest",
  "webArtifactSha256",
  "openApiSha256",
  "eventSchemaSha256",
];

if (
  typeof manifest.releaseVersion !== "string" ||
  !/^v0\.[0-9]+\.[0-9]+(?:-rc\.[0-9]+)?$/.test(manifest.releaseVersion ?? "")
) {
  errors.push("invalid-release-version");
}
if (
  typeof manifest.gitSha !== "string" ||
  !/^[a-f0-9]{40}$/.test(manifest.gitSha ?? "")
) {
  errors.push("invalid-git-sha");
}
for (const field of requiredShaFields) {
  if (typeof manifest[field] !== "string" || !sha.test(manifest[field]))
    errors.push(`invalid-${field}`);
}
if (
  typeof manifest.aiCatalogVersion !== "string" ||
  manifest.aiCatalogVersion.length === 0
) {
  errors.push("missing-ai-catalog-version");
}
if (
  !Array.isArray(manifest.flywayChecksums) ||
  manifest.flywayChecksums.length === 0 ||
  manifest.flywayChecksums.some(
    (v) =>
      typeof v !== "string" ||
      !/^V[0-9]+(?:__[A-Za-z0-9_]+\.sql)?:[a-f0-9]{64}$/.test(v),
  ) ||
  new Set(manifest.flywayChecksums.map((v) => String(v).split(":")[0])).size !==
    manifest.flywayChecksums.length
) {
  errors.push("missing-flyway-checksums");
}
if (
  typeof manifest.buildRunId !== "string" ||
  manifest.buildRunId.length === 0
) {
  errors.push("missing-build-run-id");
}
if (
  !Array.isArray(manifest.approvedByRoles) ||
  !manifest.approvedByRoles.includes("BE_AI_DRI") ||
  !manifest.approvedByRoles.includes("FE_DRI") ||
  manifest.approvedByRoles.length !== 2
) {
  errors.push("missing-role-approval");
}

// Provenance of the built source. "clean" = the tree was exactly gitSha; "overlay" = gitSha plus
// uncommitted files, whose content hash is recorded. An overlay build can never pass as a commit.
if (manifest.sourceState === "clean") {
  if ("sourceOverlaySha256" in manifest || "sourceOverlayPaths" in manifest)
    errors.push("clean-source-has-overlay-fields");
} else if (manifest.sourceState === "overlay") {
  if (typeof manifest.sourceOverlaySha256 !== "string" || !sha.test(manifest.sourceOverlaySha256))
    errors.push("invalid-sourceOverlaySha256");
  const paths = manifest.sourceOverlayPaths;
  if (!Array.isArray(paths) || paths.length === 0 || paths.some(
    (v) => typeof v !== "string" || v.startsWith("/") || v.split("/").includes("..") || v.length > 300))
    errors.push("invalid-sourceOverlayPaths");
} else {
  errors.push("invalid-source-state");
}
if (
  typeof manifest.aiCatalogVersion === "string" &&
  !/^KTO_KOR_SERVICE_2:[1-9][0-9]*$/.test(manifest.aiCatalogVersion)
)
  errors.push("invalid-ai-catalog-version");

if (
  "cdkAssemblySha256" in manifest &&
  (typeof manifest.cdkAssemblySha256 !== "string" ||
    !sha.test(manifest.cdkAssemblySha256))
)
  errors.push("invalid-cdkAssemblySha256");

const serialized = JSON.stringify(manifest);
if (/\blatest\b/i.test(serialized)) errors.push("floating-latest-forbidden");
if (/(AKIA|ASIA)[A-Z0-9]{16}/.test(serialized))
  errors.push("aws-access-key-like-value");
if (
  /serviceKey=|KTO_SERVICE_KEY|password|privateKey|clientSecret/i.test(
    serialized,
  )
) {
  errors.push("secret-like-field-or-value");
}

const expectedGitSha = process.env.NULLNULL_EXPECTED_GIT_SHA;
if (expectedGitSha && manifest.gitSha !== expectedGitSha) {
  errors.push("git-sha-mismatch");
}

if (errors.length > 0) {
  for (const error of errors)
    console.error(`release_manifest=failed reason=${error}`);
  process.exit(1);
}

console.log("release_manifest=pass");
