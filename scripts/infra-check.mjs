#!/usr/bin/env node
import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const infra = fileURLToPath(new URL("../infra/", import.meta.url));
if (!existsSync(infra)) {
  console.log("infra_check=blocked reason=infra-not-scaffolded owner=BA-006");
} else if (!existsSync(`${infra}/package-lock.json`)) {
  console.error("infra_check=failed reason=infra-lockfile-missing");
  process.exitCode = 1;
} else {
  const result = spawnSync("npm", ["run", "check"], {
    cwd: infra,
    stdio: "inherit",
  });
  if (result.status !== 0) {
    console.error("infra_check=failed reason=synth-or-assertions");
    process.exitCode = 1;
  } else
    console.log(
      "infra_check=pass scope=offline-synth-and-assertions live_aws=not-run",
    );
}
