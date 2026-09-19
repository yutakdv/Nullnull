import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";

// dist/test -> infra
const infra = join(__dirname, "..", "..");
const committed = readFileSync(join(infra, "bootstrap", "nnstg-bootstrap.yaml"), "utf8");
const customize = join(infra, "bootstrap", "customize.mjs");

function upstream(): string {
  // The template the pinned CLI (infra/package-lock.json) ships; offline, no AWS environment.
  return execFileSync(join(infra, "node_modules", ".bin", "cdk"), ["bootstrap", "--show-template", "--no-notices"], {
    cwd: infra,
    env: { PATH: process.env.PATH ?? "", HOME: process.env.HOME ?? "" },
    encoding: "utf8",
    maxBuffer: 8 * 1024 * 1024,
  });
}

test("the committed bootstrap template is exactly the pinned CLI's template plus our edits", () => {
  const derived = execFileSync("node", [customize], { input: upstream(), encoding: "utf8" });
  assert.equal(derived, committed, "regenerate: cdk bootstrap --show-template | node bootstrap/customize.mjs");
});

test("the deploy role GitHub reaches is limited to Nullnull stacks and cannot import", () => {
  const own = "stack/NullnullStg*/*";
  const deployRole = committed.slice(committed.indexOf("  DeploymentActionRole:"), committed.indexOf("  CloudFormationExecutionRole:"));
  assert(deployRole.length > 0);
  // Statements still on every resource: cross-account pipeline artifacts (other accounts only), the caller
  // identity, and the import deny. Everything that writes a stack is scoped.
  const wildcard = deployRole.split("- Sid: ").slice(1).filter((s) => s.includes('Resource: "*"')).map((s) => s.split("\n")[0]);
  assert.deepEqual(wildcard, ["PipelineCrossAccountArtifactsBucket", "PipelineCrossAccountArtifactsKey", "CliIdentity", "NoResourceImport"]);
  for (const sid of ["DeployPermissions", "CliPermissions", "Refactor"]) {
    const statement = deployRole.slice(deployRole.indexOf(`Sid: ${sid}`));
    const next = statement.indexOf("- Sid:", 1);
    assert.match(next > 0 ? statement.slice(0, next) : statement, new RegExp(own.replace(/[*/]/g, "\\$&")), sid);
  }
  assert.match(deployRole, /Sid: NoResourceImport\n\s+Effect: Deny\n\s+Action: cloudformation:\*/);
  assert.match(deployRole, /cloudformation:ImportResourceTypes:\n\s+- "\*"/);
  assert.match(deployRole, /Sid: NeverTheToolkitStackItself\n\s+Effect: Deny/);
  assert.match(committed, /Default: "Nullnull: deploy role limited to NullnullStg stacks, no resource import"/);
  // A resource must read the variant, or CloudFormation drops the parameter change and the stack keeps the
  // stock variant (so a stock `cdk bootstrap` would overwrite this role without a warning).
  const version = committed.slice(committed.indexOf("  CdkBootstrapVersion:"), committed.indexOf("\nOutputs:"));
  assert.match(version, /Description:\n\s+Fn::Sub: [^\n]*\$\{BootstrapVariant\}/);
});

test("a changed upstream template fails the customization instead of half-applying it", () => {
  const moved = upstream().replace("                  - cloudformation:ContinueUpdateRollback\n", "");
  const result = spawnSync("node", [customize], { input: moved, encoding: "utf8" });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /bootstrap_customize=failed reason=anchor-found-0-times/);
});
