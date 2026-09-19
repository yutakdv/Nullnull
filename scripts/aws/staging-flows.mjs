#!/usr/bin/env node
// Real user flows against a deployed staging (through CloudFront) or a local rehearsal stack.
//   node scripts/aws/staging-flows.mjs --url https://dxxxx.cloudfront.net [--place-query 경복궁]
//   node scripts/aws/staging-flows.mjs --url http://localhost:18080 --origin https://rehearsal.cloudfront.net --local
// The verifier token comes only from NULLNULL_VERIFIER_TOKEN. Cookies, CSRF tokens and response bodies
// are never printed: each line is a check name, an HTTP status and a verdict.
import { randomUUID } from "node:crypto";

const args = process.argv.slice(2);
const option = (name, fallback = undefined) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const base = (option("--url") ?? "").replace(/\/$/, "");
const origin = option("--origin", base);
const local = args.includes("--local");
const placeQuery = option("--place-query");
const verifier = process.env.NULLNULL_VERIFIER_TOKEN ?? "";
if (!/^https?:\/\/[^/]+$/.test(base) || (!local && !base.startsWith("https://"))) {
  console.error("staging_flows=failed reason=url-must-be-an-https-origin");
  process.exit(2);
}

let failures = 0;
const results = [];
const check = (name, ok, detail = "") => {
  results.push(`${ok ? "pass" : "FAIL"} ${name}${detail ? " " + detail : ""}`);
  if (!ok) failures += 1;
  return ok;
};
let cookie = "";
let csrf = "";

async function call(method, path, { body, headers = {}, withVerifier = true, withCookie = true, withOrigin = true } = {}) {
  const h = { accept: "application/json", ...headers };
  if (withVerifier && verifier) h["x-nullnull-verifier"] = verifier;
  if (withCookie && cookie) h.cookie = cookie;
  if (withOrigin && method !== "GET") h.origin = origin;
  // updateTrip is JSON Merge Patch in the contract (application/merge-patch+json); everything else is JSON.
  if (body !== undefined) h["content-type"] = method === "PATCH" ? "application/merge-patch+json" : "application/json";
  const response = await fetch(base + path, {
    method,
    headers: h,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: "manual",
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  return { status: response.status, headers: response.headers, json, text };
}

const mutation = (extra = {}) => ({ "x-csrf-token": csrf, "idempotency-key": randomUUID(), ...extra });

try {
  if (!local) {
    const page = await call("GET", "/", { withVerifier: false, withCookie: false });
    check("web.index", page.status === 200 && /text\/html/.test(page.headers.get("content-type") ?? ""), `status=${page.status}`);
    check("web.hsts", /max-age=/.test(page.headers.get("strict-transport-security") ?? ""));
    const route = await call("GET", "/trips", { withVerifier: false, withCookie: false });
    check("web.spa-route", route.status === 200, `status=${route.status}`);
    const closed = await call("GET", "/api/v1/health/live", { withVerifier: false, withCookie: false });
    check("edge.public-api-closed", closed.status === 503 && /problem\+json/.test(closed.headers.get("content-type") ?? ""),
      `status=${closed.status}`);
    if (!check("edge.verifier-present", verifier.length >= 43)) throw new Error("no verifier token");
  }

  const ready = await call("GET", "/api/v1/health/ready", { withCookie: false });
  const database = ready.json?.checks?.find((c) => c.name === "database")?.status;
  check("api.ready", ready.status === 200 && ["READY", "DEGRADED"].includes(ready.json?.status) && database === "READY",
    `status=${ready.status} state=${ready.json?.status} database=${database}`);
  const recommendation = ready.json?.checks?.find((c) => c.name === "recommendation")?.status;
  check("api.ai-reachable", recommendation === "READY", `recommendation=${recommendation}`);

  const foreign = await call("POST", "/api/v1/demo/sessions", { body: {}, withCookie: false,
    headers: { origin: "https://attacker.invalid" }, withOrigin: false });
  check("session.foreign-origin-refused", foreign.status === 403, `status=${foreign.status}`);

  const session = await call("POST", "/api/v1/demo/sessions", { body: {}, withCookie: false });
  const setCookie = session.headers.get("set-cookie") ?? "";
  const pair = setCookie.split(";")[0];
  cookie = pair.startsWith("__Host-nullnull_session=") ? pair : "";
  csrf = session.json?.csrfToken ?? "";
  check("session.created", session.status === 201 && cookie !== "" && csrf.length >= 32, `status=${session.status}`);
  check("session.cookie-attributes", /;\s*Secure/i.test(setCookie) && /;\s*HttpOnly/i.test(setCookie)
    && /;\s*Path=\//i.test(setCookie) && !/;\s*Domain=/i.test(setCookie));
  check("session.no-store", /no-store/.test(session.headers.get("cache-control") ?? ""));
  check("session.no-cors-grant", session.headers.get("access-control-allow-origin") === null);

  const me = await call("GET", "/api/v1/me");
  check("session.me", me.status === 200 && me.json?.kind === "ANONYMOUS", `status=${me.status}`);
  const readiness = await call("GET", "/api/v1/demo/readiness");
  check("demo.readiness", readiness.status === 200, `status=${readiness.status}`);

  const noCsrf = await call("POST", "/api/v1/trips", { headers: { "idempotency-key": randomUUID() },
    body: { startDate: "2026-10-10", endDate: "2026-10-11", timezone: "Asia/Seoul", planningLevel: "NOTHING", interests: [] } });
  check("csrf.required", noCsrf.status === 403, `status=${noCsrf.status} code=${noCsrf.json?.code}`);

  const created = await call("POST", "/api/v1/trips", { headers: mutation(),
    body: { title: "staging flow check", startDate: "2026-10-10", endDate: "2026-10-11", timezone: "Asia/Seoul",
      planningLevel: "NOTHING", interests: [] } });
  const tripId = created.json?.id;
  let etag = created.headers.get("etag");
  check("trip.created", created.status === 201 && typeof tripId === "string" && !!etag, `status=${created.status}`);

  if (tripId) {
    const fetched = await call("GET", `/api/v1/trips/${tripId}`);
    check("trip.read", fetched.status === 200, `status=${fetched.status}`);
    etag = fetched.headers.get("etag") ?? etag;
    const stale = await call("PATCH", `/api/v1/trips/${tripId}`, { headers: mutation({ "if-match": '"999"' }),
      body: { title: "stale write" } });
    // The contract answers a stale If-Match with 409 Conflict (version conflict), not 412.
    check("trip.stale-if-match-refused", stale.status === 409, `status=${stale.status}`);
    const renamed = await call("PATCH", `/api/v1/trips/${tripId}`, { headers: mutation({ "if-match": etag }),
      body: { title: "staging flow check 2" } });
    check("trip.update", renamed.status === 200 && renamed.json?.title === "staging flow check 2", `status=${renamed.status}`);
    etag = renamed.headers.get("etag") ?? etag;

    const search = await call("POST", "/api/v1/places/search", { body: { query: placeQuery ?? "경복궁", limit: 5 } });
    if (search.status === 503) {
      check("catalog.closed-is-explicit", search.json?.code === "SOURCE_UNAVAILABLE", `status=503 code=${search.json?.code}`);
    } else {
      const place = search.json?.items?.[0];
      check("catalog.search", search.status === 200 && Array.isArray(search.json?.items), `status=${search.status} items=${search.json?.items?.length}`);
      if (place) {
        const attribution = place.sourceAttribution;
        check("catalog.provenance", typeof attribution?.source === "string" && attribution.source.length > 0
          && typeof attribution.attribution === "string" && attribution.attribution.length > 0
          && Number.isInteger(attribution.sourceRegistryVersion) && attribution.sourceRegistryVersion >= 1,
          `sourceAttribution=${attribution ? "present" : "missing"}`);
        const before = (await call("GET", `/api/v1/trips/${tripId}`)).json?.version;
        const candidate = await call("POST", `/api/v1/trips/${tripId}/candidates`, { headers: mutation(),
          body: { placeId: place.id, source: { type: "SEARCH" } } });
        check("candidate.saved", [200, 201].includes(candidate.status) && candidate.json?.tripScheduleChanged === false,
          `status=${candidate.status}`);
        const after = await call("GET", `/api/v1/trips/${tripId}`);
        check("candidate.no-schedule-version-bump", after.json?.version === before, `before=${before} after=${after.json?.version}`);
        etag = after.headers.get("etag") ?? etag;
        const item = await call("POST", `/api/v1/trips/${tripId}/items`, { headers: mutation({ "if-match": etag }),
          body: { placeId: place.id, candidateId: candidate.json?.candidate?.id ?? null, date: "2026-10-10", position: 0 } });
        check("item.scheduled", [200, 201].includes(item.status), `status=${item.status} code=${item.json?.code ?? ""}`);
        etag = item.headers.get("etag") ?? (await call("GET", `/api/v1/trips/${tripId}`)).headers.get("etag");
      }
    }
    const removed = await call("DELETE", `/api/v1/trips/${tripId}`, { headers: mutation({ "if-match": etag }) });
    check("trip.deleted", [200, 204].includes(removed.status), `status=${removed.status}`);
  }
  // Leave nothing behind: the anonymous owner is erased through the product's own deletion path.
  const erased = await call("DELETE", "/api/v1/session", { headers: mutation() });
  check("session.deleted", erased.status === 202, `status=${erased.status}`);
  const afterErase = await call("GET", "/api/v1/me");
  check("session.revoked", afterErase.status === 401, `status=${afterErase.status}`);
} catch (error) {
  check("flows.completed", false, `error=${error?.name ?? "Error"}`);
}
for (const line of results) console.log(line);
console.log(`staging_flows=${failures === 0 ? "pass" : "failed"} checks=${results.length} failures=${failures}`);
process.exit(failures === 0 ? 0 : 1);
