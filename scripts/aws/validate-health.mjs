#!/usr/bin/env node
import { readFileSync } from "node:fs";
try {
  const [file, type, contentType] = process.argv.slice(2);
  if (!/^application\/json(?:;|$)/i.test(contentType ?? "")) throw new Error();
  const body = JSON.parse(readFileSync(file, "utf8"));
  if (type === "ready") {
    if (
      !["READY", "DEGRADED"].includes(body.status) ||
      !Array.isArray(body.checks) ||
      body.checks.length === 0
    )
      throw new Error();
    if (!body.checks.some((c) => c.name === "database" && c.status === "READY"))
      throw new Error();
  } else if (body.status !== "UP") throw new Error();
  console.log("health_contract=pass");
} catch {
  console.error("health_contract=failed");
  process.exit(1);
}
