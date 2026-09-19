#!/usr/bin/env node
// Real user flows against a deployed staging (through CloudFront) or a local rehearsal stack.
//   node scripts/aws/staging-flows.mjs --url https://dxxxx.cloudfront.net [--place-query 경복궁]
//   node scripts/aws/staging-flows.mjs --url http://localhost:18080 --origin https://rehearsal.cloudfront.net --local
//   node scripts/aws/staging-flows.mjs --url https://dxxxx.cloudfront.net --survey
//   node scripts/aws/staging-flows.mjs --url https://dxxxx.cloudfront.net --optimize-item \
//     --item-day 2026-10-03 --better-day 2026-10-06 [--decision both|keep|apply] [--optimize-timeout-seconds 180]
// The verifier token comes only from NULLNULL_VERIFIER_TOKEN. Cookies, CSRF tokens and response bodies
// are never printed: each line is a check name, an HTTP status and a verdict.
//
// Both steps below are opt-in, so CD, which passes only --url, sends exactly the requests it sent before and prints
// exactly the same verdict. Each works on a trip of its own, which it deletes.
//
// --expect-edge open|closed (default closed, which is what CD expects of a fresh release) says what an anonymous
// /api/v1/health/live should get: the gate's 503 while the edge is closed, the API's 200 once it is open
// (staging_operator.py edge). Without it the flows cannot run while judging keeps the edge open.
//
// --survey prints, for the next 29 days (KST), the --place-query place's forecast (default 경복궁) and whether it
// is open, and suggests an --item-day/--better-day pair for --optimize-item: the busiest open day and the quietest.
//
// --optimize-item is INT-04 (BRANCH_AND_INTEGRATION: ITEM preview → APPLY/KEEP → revert): one stop on --item-day,
// an ITEM preview, and by default two runs - one KEPT, one APPLIED and reverted. Everything the public API can show
// is checked first: capability, catalog, place, forecast state and coverage, that the gap exceeds 25 index points,
// and that --better-day is open and the quietest open day of the trip. A missing prerequisite, or a run that ends
// DATA_INSUFFICIENT, is NOT-RUN and the verdict `incomplete` (exit 3): never a pass, and not a product FAIL. A FAIL
// is what a one-stop, lock-free trip with its prerequisites met cannot legitimately produce. It proves INT-04 on the
// verifier path only; the code-freeze check through an open edge in an anonymous window is a separate run, and the
// lock-violation clause of INT-04 is not exercised here.
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
const expectEdge = option("--expect-edge");
if (expectEdge !== undefined && !["open", "closed"].includes(expectEdge)) {
  console.error("staging_flows=failed reason=expect-edge-must-be-open-or-closed");
  process.exit(2);
}
const optimizeItem = args.includes("--optimize-item");
const survey = args.includes("--survey");
const extraStep = optimizeItem || survey;
const itemDay = option("--item-day");
const betterDay = option("--better-day");
const decisionMode = option("--decision", "both");
const optimizeTimeoutSeconds = Number(option("--optimize-timeout-seconds", "180"));
const DAY_MS = 86_400_000;
const dayNumber = (day) => Date.parse(`${day}T00:00:00Z`) / DAY_MS;
const dayAfter = (day, n = 1) => new Date((dayNumber(day) + n) * DAY_MS).toISOString().slice(0, 10);
// Today in Korea: the forecast's days are KST calendar days (ForecastDays), and so is the trip below.
const todayKst = new Date(Date.now() + 9 * 3_600_000).toISOString().slice(0, 10);
const usage = (reason) => {
  console.error(`staging_flows=failed reason=${reason}`);
  process.exit(2);
};
if (optimizeItem && survey) usage("survey-and-optimize-item-are-separate-runs");
if (optimizeItem) {
  const isDay = (day) => /^\d{4}-\d{2}-\d{2}$/.test(day ?? "") && new Date(`${day}T00:00:00Z`).toISOString().startsWith(day);
  if (!isDay(itemDay)) usage("item-day-must-be-yyyy-mm-dd");
  if (!isDay(betterDay)) usage("better-day-must-be-yyyy-mm-dd");
  const apart = Math.abs(dayNumber(itemDay) - dayNumber(betterDay));
  // The trip spans the two days and holds at most 30 (TripDetail.days maxItems).
  if (apart < 1 || apart > 29) usage("days-must-be-1-to-29-days-apart");
  if (itemDay < todayKst || betterDay < todayKst) usage("days-must-not-be-in-the-past-kst");
  if (!["both", "keep", "apply"].includes(decisionMode)) usage("decision-must-be-both-keep-or-apply");
  if (!Number.isInteger(optimizeTimeoutSeconds) || optimizeTimeoutSeconds < 1 || optimizeTimeoutSeconds > 900)
    usage("optimize-timeout-seconds-must-be-1-to-900");
}

let failures = 0;
let notRun = 0;
let checked = 0;
const results = [];
const check = (name, ok, detail = "") => {
  results.push(`${ok ? "pass" : "FAIL"} ${name}${detail ? " " + detail : ""}`);
  checked += 1;
  if (!ok) failures += 1;
  return ok;
};
// What the survey found: printed, never counted as a check.
const info = (line) => results.push(`info ${line}`);
// A check that could not be asked: its own line, counted apart, and never a pass.
const skipped = (name, reason, detail = "") => {
  results.push(`NOT-RUN ${name} reason=${reason}${detail ? " " + detail : ""}`);
  notRun += 1;
  return false;
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
    await edgeCheck();
    if (!check("edge.verifier-present", verifier.length >= 43)) throw new Error("no verifier token");
  }

  // A local rehearsal has no gate; asked explicitly, it can still check what the stack answers anonymously.
  if (local && expectEdge) await edgeCheck();
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
  if (extraStep) {
    try {
      await (survey ? surveyStep() : optimizeItemStep());
    } catch (error) {
      check(survey ? "survey.completed" : "optimization.completed", false, `error=${error?.name ?? "Error"}`);
    }
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
if (!extraStep) {
  console.log(`staging_flows=${failures === 0 ? "pass" : "failed"} checks=${results.length} failures=${failures}`);
  process.exit(failures === 0 ? 0 : 1);
}
// A failure outranks a check that did not run; a check that did not run is never a pass.
const verdict = failures > 0 ? "failed" : notRun > 0 ? "incomplete" : "pass";
console.log(`staging_flows=${verdict} checks=${checked} failures=${failures} not_run=${notRun}`);
if (verdict === "incomplete") console.log("staging_flows_counts_as_pass=false");
process.exit(verdict === "pass" ? 0 : verdict === "failed" ? 1 : 3);

/** What an anonymous request gets from the API through the edge, against --expect-edge (default closed). */
async function edgeCheck() {
  const closed = await call("GET", "/api/v1/health/live", { withVerifier: false, withCookie: false });
  if (expectEdge === "open")
    check("edge.public-api-open", closed.status === 200 && /json/.test(closed.headers.get("content-type") ?? ""),
      `status=${closed.status}`);
  else
    check("edge.public-api-closed", closed.status === 503 && /problem\+json/.test(closed.headers.get("content-type") ?? ""),
      `status=${closed.status}`);
}

function kstMidnight(day) {
  return `${day}T00:00:00+09:00`;
}

function kstDay(at) {
  return at ? new Date(Date.parse(at) + 9 * 3_600_000).toISOString().slice(0, 10) : null;
}

/** Capability, catalog, the place by exact name, and its forecast over [firstDay, lastDay] in KST days. */
async function placeAndForecast(step, firstDay, lastDay) {
  const capabilities = await call("GET", "/api/v1/demo/readiness");
  if (!check(`${step}.readiness-read`, capabilities.status === 200, `status=${capabilities.status}`)) return null;
  const capability = capabilities.json?.capabilities?.find((c) => c.name === "optimization")?.status ?? "absent";
  if (capability !== "READY") return skipped(step, "capability-off", `capability=${capability}`) && null;

  const query = placeQuery ?? "경복궁";
  const search = await call("POST", "/api/v1/places/search", { body: { query, limit: 10 } });
  if (search.status === 503) return skipped(step, "catalog-closed", `code=${search.json?.code}`) && null;
  if (!check(`${step}.place-search`, search.status === 200, `status=${search.status}`)) return null;
  // By exact name: the first hit of a text search is not necessarily the place the days were chosen for.
  const place = (search.json?.items ?? []).find((p) => p.name === query);
  if (!place) return skipped(step, "place-not-found", `items=${search.json?.items?.length ?? 0}`) && null;

  const forecast = await call("GET", `/api/v1/places/${place.id}/crowd-forecast`
    + `?from=${encodeURIComponent(kstMidnight(firstDay))}&to=${encodeURIComponent(kstMidnight(dayAfter(lastDay)))}`);
  if (!check(`${step}.forecast-read`, forecast.status === 200, `status=${forecast.status}`)) return null;
  const state = forecast.json?.state;
  if (state !== "FORECAST") return skipped(step, state === "STALE" ? "forecast-stale" : "forecast-missing", `state=${state}`) && null;
  const values = new Map((forecast.json?.points ?? [])
    .map((p) => [kstDay(p.provenance?.targetAt), p.value])
    .filter(([day, value]) => day && typeof value === "number"));
  return { place, values };
}

/** A trip of the step's own over [firstDay, lastDay], deleted whatever happens inside. */
async function withOwnTrip(step, firstDay, lastDay, body) {
  const created = await call("POST", "/api/v1/trips", { headers: mutation(),
    body: { title: `staging ${step} check`, startDate: firstDay, endDate: lastDay, timezone: "Asia/Seoul",
      planningLevel: "NOTHING", interests: [] } });
  if (!check(`${step}.trip-created`, created.status === 201 && typeof created.json?.id === "string", `status=${created.status}`)) return;
  const trip = { id: created.json.id, etag: created.headers.get("etag") };
  try {
    await body(trip);
  } finally {
    const current = await call("GET", `/api/v1/trips/${trip.id}`);
    const removed = await call("DELETE", `/api/v1/trips/${trip.id}`, {
      headers: mutation({ "if-match": current.headers.get("etag") ?? trip.etag }) });
    check(`${step}.trip-deleted`, [200, 204].includes(removed.status), `status=${removed.status}`);
  }
}

/**
 * Which of the trip's days the place is open, from getCandidateTripMatches: its slots are the evaluator's per-date
 * verdicts on the catalog's verified hours, so CLOSED and OPENING_HOURS_UNKNOWN are the run's own reasons.
 */
async function openDays(step, trip, place, days) {
  const saved = await call("POST", `/api/v1/trips/${trip.id}/candidates`, { headers: mutation(),
    body: { placeId: place.id, source: { type: "SEARCH" } } });
  const candidateId = saved.json?.candidate?.id;
  if (!check(`${step}.candidate-saved`, [200, 201].includes(saved.status) && typeof candidateId === "string", `status=${saved.status}`)) return null;
  const matches = await call("GET", `/api/v1/trips/${trip.id}/candidates/${candidateId}/matches`);
  // One slot per trip day: the evaluator answers every date of a trip this short. Fewer - in practice none, with
  // state UNKNOWN - is Spring's fallback when apps/ai did not answer, which is a service failure, not unknown hours.
  const slots = matches.json?.slots ?? [];
  if (!check(`${step}.hours-read`, matches.status === 200 && slots.length === days,
    `status=${matches.status} state=${matches.json?.state} slots=${slots.length} days=${days}`)) return null;
  const byDay = new Map(slots.map((slot) => [slot.date,
    slot.eligible ? "OPEN" : slot.reasonCode === "CLOSED" ? "CLOSED"
      : slot.reasonCode === "OPENING_HOURS_UNKNOWN" ? "UNKNOWN" : (slot.reasonCode ?? "UNKNOWN")]));
  return { candidateId, byDay };
}

/** The next 29 days (KST): forecast and hours for each, and the pair --optimize-item should be given. */
async function surveyStep() {
  const firstDay = dayAfter(todayKst);
  const lastDay = dayAfter(todayKst, 29);
  const found = await placeAndForecast("survey", firstDay, lastDay);
  if (!found) return;
  const { place, values } = found;
  await withOwnTrip("survey", firstDay, lastDay, async (trip) => {
    const hours = await openDays("survey", trip, place, dayNumber(lastDay) - dayNumber(firstDay) + 1);
    if (!hours) return;
    for (let day = firstDay; day <= lastDay; day = dayAfter(day))
      info(`survey date=${day} value=${values.get(day) ?? "none"} hours=${hours.byDay.get(day) ?? "UNKNOWN"}`);
    const open = [...values].filter(([day]) => hours.byDay.get(day) === "OPEN");
    if (open.length < 2) return skipped("survey", "fewer-than-two-open-days-with-a-forecast", `open=${open.length}`);
    // The quietest open day, earliest on a tie: what a whole-day move ranks first (policy-v1 tieBreak: score DESC,
    // changeCost ASC - saturated at 1 for any day - then date ASC).
    const quietest = [...open].sort((a, b) => a[1] - b[1] || (a[0] < b[0] ? -1 : 1))[0];
    const busiest = [...open].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1))[0];
    const delta = Math.round((busiest[1] - quietest[1]) * 10_000) / 10_000;
    if (!(delta > 25)) return skipped("survey", "no-open-pair-over-25", `delta=${delta}`);
    check("survey.pair-found", true, `item_day=${busiest[0]} better_day=${quietest[0]} delta=${delta}`);
  });
}

/** INT-04: one stop on the busier day, then KEEP, APPLY and its revert, or both runs in turn. */
async function optimizeItemStep() {
  const [firstDay, lastDay] = [itemDay, betterDay].sort();
  const found = await placeAndForecast("optimization", firstDay, lastDay);
  if (!found) return;
  const { place, values } = found;
  const [itemValue, betterValue] = [values.get(itemDay), values.get(betterDay)];
  if (typeof itemValue !== "number" || typeof betterValue !== "number")
    return skipped("optimization", "forecast-no-coverage", `item_day=${typeof itemValue} better_day=${typeof betterValue}`);
  const delta = Math.round((itemValue - betterValue) * 10_000) / 10_000;
  // A whole-day move saturates changeCost at 1 (policy-v1 itemObjective, 240-minute saturation), so the score
  // 0.8·Δ/100 − 0.2 is positive only for a gap over 25 index points.
  if (!(delta > 25)) return skipped("optimization", "forecast-delta-too-small", `delta=${delta}`);

  await withOwnTrip("optimization", firstDay, lastDay, async (trip) => {
    const hours = await openDays("optimization", trip, place, dayNumber(lastDay) - dayNumber(firstDay) + 1);
    if (!hours) return;
    const betterHours = hours.byDay.get(betterDay) ?? "UNKNOWN";
    if (betterHours !== "OPEN")
      return skipped("optimization", betterHours === "CLOSED" ? "better-day-closed" : "better-day-hours-unknown", `hours=${betterHours}`);
    // The first proposal is the quietest open day but the item's own, earliest on a tie (tieBreak above), so the
    // better day must be that day for "the better day is proposed first" to be a check rather than a ranking guess.
    const quietest = [...values].filter(([day]) => day !== itemDay && hours.byDay.get(day) === "OPEN")
      .sort((a, b) => a[1] - b[1] || (a[0] < b[0] ? -1 : 1))[0]?.[0];
    if (quietest !== betterDay) return skipped("optimization", "better-day-not-the-quietest-open-day", `quietest=${quietest}`);

    const added = await call("POST", `/api/v1/trips/${trip.id}/items`, { headers: mutation({ "if-match": trip.etag }),
      body: { placeId: place.id, candidateId: hours.candidateId, date: itemDay, position: 0 } });
    const stops = (added.json?.trip?.days ?? []).flatMap((d) => d.items ?? []);
    const itemId = added.json?.changedItemIds?.[0];
    // One stop and no lock: every other day is empty, so no route evidence is needed (the service has none) and no
    // failure other than data can come from the trip itself.
    if (!check("optimization.item-scheduled", added.status === 201 && stops.length === 1 && typeof itemId === "string",
      `status=${added.status} stops=${stops.length}`)) return;
    trip.etag = added.headers.get("etag");
    for (const mode of decisionMode === "both" ? ["keep", "apply"] : [decisionMode]) {
      if (!(await optimizationRun(trip, { place, itemId, delta, firstDay, lastDay }, mode))) return;
    }
  });
}

/** One ITEM run on the trip, decided by `mode`. Returns whether the next run may follow. */
async function optimizationRun(trip, { place, itemId, delta, firstDay, lastDay }, mode) {
  const step = `optimization.${mode}`;
  const readTrip = async () => {
    const read = await call("GET", `/api/v1/trips/${trip.id}`);
    trip.etag = read.headers.get("etag") ?? trip.etag;
    const stop = (read.json?.days ?? []).flatMap((d) => d.items ?? []).find((i) => i.id === itemId);
    return { version: read.json?.version, date: stop?.date };
  };
  const before = await readTrip();
  const queued = await call("POST", `/api/v1/trips/${trip.id}/optimizations`, { headers: mutation({ "if-match": trip.etag }),
    body: { scope: "ITEM", targetItemId: itemId, inputTripVersion: before.version, includeCandidates: false } });
  const runId = queued.json?.id;
  if (!check(`${step}.queued`, queued.status === 202 && typeof runId === "string", `status=${queued.status} code=${queued.json?.code ?? ""}`)) return false;

  // Polled at the API's own path, never through Location: the server answers Location relative to its context
  // path (/optimizations/{id}), which through CloudFront is the web app, not the API.
  const pollStarted = Date.now();
  let run = queued;
  while (["QUEUED", "RUNNING"].includes(run.json?.status) && Date.now() - pollStarted < optimizeTimeoutSeconds * 1000) {
    const wait = Math.min(10, Math.max(1, Number(run.headers.get("retry-after")) || 2));
    await new Promise((resolve) => setTimeout(resolve, wait * 1000));
    run = await call("GET", `/api/v1/optimizations/${runId}`);
  }
  const status = run.status === 200 ? run.json?.status : `http-${run.status}`;
  const code = run.json?.failure?.code ?? run.json?.code ?? "";
  const waited = Math.round((Date.now() - pollStarted) / 1000);
  if (status === "FAILED" && code === "DATA_INSUFFICIENT")
    // Every prerequisite the public API shows was met, so this is data that moved between the check and the run
    // (a forecast set turning stale, hours expiring), not a product defect.
    return skipped(`${step}.decision`, "run-failed", `code=DATA_INSUFFICIENT delta=${delta}`);
  if (!check(`${step}.ready`, status === "READY" && (run.json?.proposals?.length ?? 0) > 0,
    `status=${status} code=${code} waited=${waited}s delta=${delta} proposals=${run.json?.proposals?.length ?? 0}`)) return false;

  const proposals = [...run.json.proposals].sort((a, b) => a.rank - b.rank);
  const shapeOk = proposals.every((p) => {
    const change = p.changes?.length === 1 ? p.changes[0] : null;
    return change?.operation === "MOVE" && change.itemId === itemId && change.before?.date === itemDay
      && change.after?.date >= firstDay && change.after?.date <= lastDay && change.after?.date !== itemDay
      && change.after?.placeId === place.id && p.metrics?.comparisonEligible === true
      && typeof p.metrics?.crowdDelta === "number" && p.metrics.crowdDelta < 0
      && p.validation?.allConstraintsPreserved === true && (p.dataProvenance?.length ?? 0) > 0;
  });
  check(`${step}.proposal-shape`, shapeOk, `proposals=${proposals.length}`);
  const first = proposals[0];
  check(`${step}.better-day-first`, first.changes?.[0]?.after?.date === betterDay, `first=${first.changes?.[0]?.after?.date}`);
  // Invariant 3: a preview changes nothing until the traveller decides.
  const unchanged = await readTrip();
  if (!check(`${step}.unchanged-before-decision`, unchanged.version === before.version && unchanged.date === itemDay,
    `version=${unchanged.version} date=${unchanged.date}`)) return false;

  const decision = mode.toUpperCase();
  const decided = await call("POST", `/api/v1/optimizations/${runId}/decisions`, { headers: mutation({ "if-match": trip.etag }),
    body: { proposalId: first.id, decision } });
  if (!check(`${step}.decided`, decided.status === 200 && decided.json?.decision === decision,
    `status=${decided.status} decision=${decided.json?.decision} code=${decided.json?.code ?? ""}`)) return false;
  if (mode === "keep") {
    check(`${step}.no-new-version`, decided.json.resultingTripVersion == null
      && decided.headers.get("etag") === `"${before.version}"`, `etag=${decided.headers.get("etag")}`);
    const after = await readTrip();
    check(`${step}.left-trip`, after.version === before.version && after.date === itemDay, `version=${after.version} date=${after.date}`);
    const kept = await call("GET", `/api/v1/optimizations/${runId}`);
    return check(`${step}.run-kept`, kept.json?.status === "KEPT" && kept.json?.decisions?.length === 1, `status=${kept.json?.status}`);
  }
  const applied = decided.json;
  const afterDay = first.changes[0].after.date;
  check(`${step}.new-version`, applied.resultingTripVersion > before.version, `input=${before.version} resulting=${applied.resultingTripVersion}`);
  check(`${step}.revert-window`, Date.parse(applied.revertUntil) - Date.parse(applied.decidedAt) === DAY_MS);
  const moved = await readTrip();
  check(`${step}.moved-item`, moved.version === applied.resultingTripVersion && moved.date === afterDay,
    `version=${moved.version} date=${moved.date} expected=${afterDay}`);
  const reverted = await call("POST", `/api/v1/optimization-decisions/${applied.id}/revert`, { headers: mutation({ "if-match": trip.etag }) });
  if (!check(`${step}.reverted`, reverted.status === 200 && reverted.json?.revertedDecisionId === applied.id,
    `status=${reverted.status} code=${reverted.json?.code ?? ""}`)) return false;
  const restored = await readTrip();
  check(`${step}.restored-item`, restored.date === itemDay && restored.version === reverted.json.resultingTripVersion,
    `version=${restored.version} date=${restored.date}`);
  const after = await call("GET", `/api/v1/optimizations/${runId}`);
  return check(`${step}.run-reverted`, after.json?.status === "REVERTED" && after.json?.revertAvailability === "REVERTED"
    && after.json?.decisions?.length === 2, `status=${after.json?.status} revert=${after.json?.revertAvailability}`);
}
