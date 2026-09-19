import * as cdk from "aws-cdk-lib";
import { readFileSync } from "node:fs";
import { createStacks } from "./staging";
const app = new cdk.App();
const account = app.node.tryGetContext("account");
if (typeof account !== "string" || !/^\d{12}$/.test(account)) throw new Error("Explicit account required");
if (app.node.tryGetContext("phase") === "foundation") {
  // No image, web artifact or application resource participates in first bootstrap.
  createStacks(app, { account, bootstrapOnly: true, webDirectory: "",
    release: {releaseVersion: "bootstrap", apiImageDigest: "", aiImageDigest: "", aiCatalogVersion: ""} });
} else {
  const manifest = app.node.tryGetContext("releaseManifest"), webDirectory = app.node.tryGetContext("webDirectory");
  if (!manifest || !webDirectory) throw new Error("Explicit releaseManifest and webDirectory required");
  const release = JSON.parse(readFileSync(manifest, "utf8"));
  for (const d of [release.apiImageDigest, release.aiImageDigest])
    if (typeof d !== "string" || !/^sha256:[a-f0-9]{64}$/.test(d)) throw new Error("Immutable image digest required");
  // Same shape as the API's CatalogVersion.current(): "<catalog source code>:<registry revision>".
  if (typeof release.aiCatalogVersion !== "string" || !/^KTO_KOR_SERVICE_2:[1-9][0-9]*$/.test(release.aiCatalogVersion))
    throw new Error("aiCatalogVersion must be KTO_KOR_SERVICE_2:<revision>");
  createStacks(app, {account, release, webDirectory});
}
