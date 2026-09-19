#!/usr/bin/env node
// Derives the Nullnull CDK bootstrap template from the one the pinned CDK CLI ships:
//   node_modules/.bin/cdk bootstrap --show-template | node bootstrap/customize.mjs > bootstrap/nnstg-bootstrap.yaml
// The stock deploy role may create, update, delete, import into and refactor ANY stack in the account,
// and CloudFormation acts on an existing stack with that stack's own saved role. GitHub's staging role
// assumes this deploy role, so the changes below keep it on NullnullStg* stacks, away from resource
// import (the one way a template reaches an existing resource of another project) and off the toolkit
// stack itself. Every edit is anchored: if the CLI ships a different template, this fails instead of
// producing a half-customized one.
import { readFileSync } from "node:fs";

const upstream = readFileSync(process.argv[2] ?? 0, "utf8");
const own = "arn:${AWS::Partition}:cloudformation:${AWS::Region}:${AWS::AccountId}:stack/NullnullStg*/*";
const edits = [
  [
    '    Default: "AWS CDK: Default Resources"\n',
    '    Default: "Nullnull: deploy role limited to NullnullStg stacks, no resource import"\n',
  ],
  [
    "                  - cloudformation:ContinueUpdateRollback\n                Resource: \"*\"\n",
    `                  - cloudformation:ContinueUpdateRollback\n                Resource:\n                  Fn::Sub: ${own}\n`,
  ],
  [
    [
      "              - Sid: CliPermissions",
      "                Action:",
      "                  - cloudformation:DeleteStack",
      "                  - cloudformation:UpdateTerminationProtection",
      "                  - sts:GetCallerIdentity",
      '                Resource: "*"',
      "                Effect: Allow",
      "",
    ].join("\n"),
    [
      "              - Sid: CliPermissions",
      "                Action:",
      "                  - cloudformation:DeleteStack",
      "                  - cloudformation:UpdateTerminationProtection",
      "                Resource:",
      `                  Fn::Sub: ${own}`,
      "                Effect: Allow",
      "              - Sid: CliIdentity",
      "                Action: sts:GetCallerIdentity",
      '                Resource: "*"',
      "                Effect: Allow",
      "",
    ].join("\n"),
  ],
  [
    [
      "                  - cloudformation:ExecuteStackRefactor",
      '                Resource: "*"',
      "",
    ].join("\n"),
    [
      "                  - cloudformation:ExecuteStackRefactor",
      "                Resource:",
      `                  Fn::Sub: ${own}`,
      // AWS's documented deny-import form (cloudformation:ImportResourceTypes is set only by imports).
      "              - Sid: NoResourceImport",
      "                Effect: Deny",
      "                Action: cloudformation:*",
      '                Resource: "*"',
      "                Condition:",
      "                  ForAnyValue:StringLike:",
      "                    cloudformation:ImportResourceTypes:",
      '                      - "*"',
      "              - Sid: NeverTheToolkitStackItself",
      "                Effect: Deny",
      "                Action:",
      "                  - cloudformation:CreateChangeSet",
      "                  - cloudformation:DeleteChangeSet",
      "                  - cloudformation:ExecuteChangeSet",
      "                  - cloudformation:CreateStack",
      "                  - cloudformation:UpdateStack",
      "                  - cloudformation:DeleteStack",
      "                  - cloudformation:RollbackStack",
      "                  - cloudformation:ContinueUpdateRollback",
      "                  - cloudformation:UpdateTerminationProtection",
      "                  - cloudformation:CreateStackRefactor",
      "                  - cloudformation:ExecuteStackRefactor",
      "                Resource:",
      "                  Fn::Sub: arn:${AWS::Partition}:cloudformation:${AWS::Region}:${AWS::AccountId}:stack/${AWS::StackName}/*",
      "",
    ].join("\n"),
  ],
];
// The CLI refuses to overwrite a stack whose BootstrapVariant parameter differs from the template's, but
// CloudFormation treats a parameter-only change as "no changes", so the variant never reaches the stack
// unless a resource reads it (measured 2026-09-19: two runs left "AWS CDK: Default Resources" in place).
edits.push([
  "      Name:\n        Fn::Sub: /cdk-bootstrap/${Qualifier}/version\n",
  "      Name:\n        Fn::Sub: /cdk-bootstrap/${Qualifier}/version\n      Description:\n        Fn::Sub: CDK bootstrap version, template variant ${BootstrapVariant}\n",
]);
let text = upstream;
for (const [anchor, replacement] of edits) {
  const count = text.split(anchor).length - 1;
  if (count !== 1) {
    console.error(`bootstrap_customize=failed reason=anchor-found-${count}-times anchor=${JSON.stringify(anchor.slice(0, 60))}`);
    process.exit(1);
  }
  text = text.replace(anchor, () => replacement);
}
process.stdout.write(text);
