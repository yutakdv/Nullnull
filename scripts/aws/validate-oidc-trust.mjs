#!/usr/bin/env node
// usage: validate-oidc-trust.mjs <trust-policy.json> [deploy|publish]
// Checks a live role trust against infra/github-oidc.json, the same file the CDK role is built from.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
const fail = () => {
  console.error("oidc_trust=failed reason=trust-is-not-exact-staging-subject");
  process.exit(1);
};
try {
  const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
  const identity = JSON.parse(readFileSync(join(root, "infra", "github-oidc.json"), "utf8"));
  const [file, kind = "deploy"] = process.argv.slice(2);
  const expected = { deploy: identity.deploySubjects, publish: identity.publishSubjects }[kind];
  if (!Array.isArray(expected) || expected.length === 0) fail();
  const policy = JSON.parse(readFileSync(file, "utf8"));
  if (!policy || typeof policy !== "object") fail();
  const statements = Array.isArray(policy.Statement)
    ? policy.Statement
    : [policy.Statement];
  // Every statement is examined. Unsupported shapes are refused rather than filtered away.
  if (statements.length !== 1) fail();
  const s = statements[0];
  const one = (v) => (Array.isArray(v) && v.length === 1 ? v[0] : v);
  if (
    !s ||
    s.Effect !== "Allow" ||
    one(s.Action) !== "sts:AssumeRoleWithWebIdentity" ||
    Object.keys(s).some(
      (k) => !["Sid", "Effect", "Principal", "Action", "Condition"].includes(k),
    )
  )
    fail();
  if (!s.Principal || Object.keys(s.Principal).join() !== "Federated") fail();
  const principal = one(s.Principal.Federated);
  const account = process.env.NULLNULL_AWS_ACCOUNT_ID;
  const provider = new RegExp(
    `^arn:aws:iam::([0-9]{12}):oidc-provider/${identity.provider.replaceAll(".", "\\.")}$`,
  );
  const match = typeof principal === "string" && principal.match(provider);
  if (!match || (account && match[1] !== account)) fail();
  if (!s.Condition || Object.keys(s.Condition).join() !== "StringEquals")
    fail();
  const c = s.Condition.StringEquals;
  const subjects = [c?.[`${identity.provider}:sub`]].flat();
  if (
    !c ||
    Object.keys(c).length !== 2 ||
    one(c[`${identity.provider}:aud`]) !== identity.audience ||
    subjects.some((v) => typeof v !== "string" || /[*?]/.test(v)) ||
    JSON.stringify([...subjects].sort()) !== JSON.stringify([...expected].sort())
  )
    fail();
  console.log(`oidc_trust=pass kind=${kind} subjects=${subjects.length}`);
} catch {
  fail();
}
