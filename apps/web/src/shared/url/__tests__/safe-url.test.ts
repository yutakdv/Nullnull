// A server-supplied URL is not safe to put in the DOM until something checks
// its scheme, and nothing does today.
//
// The five places a response URL reaches an attribute with no validation at all
// (measured, not assumed — these are every `src={`/`href={` over a response
// field in the app):
//
//   PostScreen.tsx:121        detail.coverUrl      → <img src>
//   FeedPostCard.tsx:66       post.coverUrl        → <img src>
//   PlaceThumbnail.tsx:48     place.thumbnailUrl   → <img src>
//   DataAttribution.tsx:67    officialUrl          → <a href>
//   DataAttribution.tsx:76    licenseUrl           → <a href>
//
// The two anchors are the execution path: a browser will not run
// `<img src="javascript:…">`, but it does run `<a href="javascript:…">` when
// the link is clicked. Those two carry `rel="noreferrer noopener"`, which is a
// tabnabbing control and says nothing about the scheme.
//
// THE CONTRACT DOES NOT CATCH THIS. `format: uri` asks whether a string parses
// as a URI, not what scheme it names, so `javascript:alert(1)` and
// `data:text/html;…` both satisfy it; the generated client narrows the field to
// `string` and drops the format entirely. So the check has to exist in the
// client, and this file is what measures it.
//
// WRITTEN BEFORE THE GUARD, AND RED FIRST. A guard's job is to let things
// through, so a wrong one is green — this repo has already shipped a push guard
// whose regex passed 12 of 23 inputs it was written to stop, and it looked
// fine. Measuring first meant the guard had to turn a failing file green rather
// than being inspected by the person who had just written it. Recorded as
// method, not history: the same order applies to the next guard.
//
// Both directions are asserted here because each alone is satisfied by a
// useless guard: reject-everything passes the first half, and
// accept-everything passes the second.
import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { isSafeUrl } from '../safe-url.js';

const FIXTURES = resolve(process.cwd(), '../../packages/contracts/fixtures');

/** The response fields that reach `src=`/`href=` in the five places above. */
const URL_FIELDS = ['coverUrl', 'thumbnailUrl', 'officialUrl', 'licenseUrl'];

/**
 * Every URL the shipped fixtures actually carry.
 *
 * Read from the fixtures rather than typed out: a list written by hand here
 * would keep passing after someone adds a fixture with a different shape, and
 * that new URL is exactly the one nobody checked.
 */
function fixtureUrls(): string[] {
  const found = new Set<string>();
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(path);
        continue;
      }
      if (!entry.name.endsWith('.json')) continue;
      const text = readFileSync(path, 'utf8');
      for (const field of URL_FIELDS) {
        for (const match of text.matchAll(
          new RegExp(`"${field}"\\s*:\\s*"([^"]+)"`, 'g'),
        )) {
          if (match[1]) found.add(match[1]);
        }
      }
    }
  };
  walk(FIXTURES);
  return [...found];
}

describe('isSafeUrl rejects what a browser would execute', () => {
  // Each case is its own `it` rather than a table row so a half-working guard
  // names which shape it let through. A guard that stops the plain lowercase
  // spelling and nothing else is the likely first draft.
  it.each([
    ['the plain scheme', 'javascript:alert(1)'],
    // Schemes are case-insensitive to a browser, so a lowercase-only check is
    // bypassed by the shift key.
    ['a mixed-case scheme', 'JavaScript:alert(1)'],
    ['an upper-case scheme', 'JAVASCRIPT:alert(1)'],
    // Leading whitespace is stripped before the scheme is read, so this is the
    // same URL to a browser and a different string to a naive `startsWith`.
    ['leading whitespace', '  javascript:alert(1)'],
    // Control characters INSIDE the scheme are discarded by the browser's URL
    // parser: this runs. A check that compares the raw string will not see it.
    ['an embedded tab', 'java\tscript:alert(1)'],
    ['an embedded newline', 'java\nscript:alert(1)'],
    // data: is an execution path in an <a href> too — the document it names is
    // navigated to and its scripts run.
    ['a data document', 'data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=='],
    ['a data document, unencoded', 'data:text/html,<script>alert(1)</script>'],
    ['the vbscript scheme', 'vbscript:msgbox(1)'],
    // Not an execution path, but it silently retargets to another origin: the
    // page's own scheme is used, so this loads from evil.example.
    ['a protocol-relative URL', '//evil.example/a.jpg'],
    ['an empty string', ''],
    ['whitespace only', '   '],
  ])('rejects %s', (_label, url) => {
    expect(isSafeUrl(url)).toBe(false);
  });
});

describe('isSafeUrl accepts the URLs the app is actually served', () => {
  const urls = fixtureUrls();

  // THE NON-VACUITY GUARD, and the reason it is a test of its own: "every
  // fixture URL is accepted" is trivially true of an empty list, so without
  // this the suite below would stay green if the scan broke, the fields were
  // renamed, or the fixtures moved. Same shape as the zero-target case in
  // attribution-coverage.test.ts.
  it('has URLs to measure at all', () => {
    expect(
      urls.length,
      `no URL found in ${FIXTURES} for fields ${URL_FIELDS.join(', ')} — the scan found nothing`,
    ).toBeGreaterThan(0);
  });

  it('accepts every URL the shipped fixtures carry', () => {
    // A guard that rejects these is not a fix, it is an outage: every cover
    // image and every source credit link in the app is one of them. This is
    // the half that a reject-everything guard fails.
    const rejected = urls.filter((url) => !isSafeUrl(url));
    expect(rejected, 'the guard would break these real URLs').toEqual([]);
  });
});

// POLICY, AND WHERE IT CAME FROM: `https:` only — the owner's call, not an
// inference made here. This block was an `it.todo` until that answer existed,
// because asserting a guess would have handed the next reader a decision
// nobody made, dressed as a passing test.
//
// The reasoning, kept so the constraint can be judged rather than obeyed or
// ignored: every URL the fixtures carry is already https so the accept-side
// measurement does not weaken; it is the straightest reading of CLAUDE.md's
// "외부 URL은 allowlist"; and the accepted cost is that an http image from BE
// will not render, with the blank image needing to be traced back to this
// guard. If that cost ever gets paid, this is the decision to revisit — and
// the case below is the line to change.
describe('isSafeUrl and plain http', () => {
  it('rejects plain http, which is a policy choice rather than an attack', () => {
    // Unlike everything in the block above, this one is not an execution or
    // retargeting vector — it is an allowlist decision. Separated into its own
    // describe for exactly that reason: a later reader weighing whether to
    // allow http should not have to work out which of the twelve rejections
    // are security and which is policy.
    expect(isSafeUrl('http://plain.example/a.jpg')).toBe(false);
  });
});
