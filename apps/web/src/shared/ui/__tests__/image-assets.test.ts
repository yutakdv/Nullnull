// Every image this app ships is one we are allowed to ship (CMP-ATT-003).
//
// The compliance rule is narrow and easy to break by accident: the submission
// must not imply a source it was not granted, and an image with no traceable
// licence is exactly that. Today there is nothing to find — `apps/web` ships
// two first-party logos and hardcodes no external image — so this test is
// written while the answer is zero, which is the only time a "no violations"
// assertion is cheap to establish and worth anything afterwards.
//
// What it is NOT: a check that images look right, or that the server's images
// are licensed. Server-supplied images arrive with their own credit field
// (PlaceSummary.thumbnailAttribution, post coverUrl) and the screens that
// render them carry that credit; this file covers the bytes in the repo and
// the origins the code itself names.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { extname, join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const WEB = resolve(process.cwd());
const PUBLIC = join(WEB, 'public');
const SRC = join(WEB, 'src');

const IMAGE_EXTENSIONS = new Set([
  '.png',
  '.jpg',
  '.jpeg',
  '.gif',
  '.webp',
  '.avif',
  '.svg',
  '.ico',
  '.bmp',
  '.tiff',
]);

/**
 * Every image file the build may ship, and why it is allowed to exist.
 *
 * Exact match, not a minimum: an image added without a line here fails, which
 * is the point. A new asset is a licence question, and the answer belongs
 * beside the file rather than in someone's memory.
 *
 * A-024 settled that post covers are first-party illustrations, so when those
 * five arrive they are added here and this scan starts covering them for free.
 */
const ALLOWED: Record<string, string> = {
  'public/icon.svg': 'First-party app mark, drawn for this project (PWA manifest icon).',
  'public/icon-maskable.svg':
    'The same first-party mark with the maskable safe-area padding Android asks for.',
};

/** Origins the code itself may name for an image. */
const ALLOWED_IMAGE_ORIGINS: string[] = [];

function walk(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === 'node_modules' || entry === 'dist') continue;
      out.push(...walk(full));
      continue;
    }
    out.push(full);
  }
  return out;
}

/**
 * Provider names the matrix asks us not to use on their own.
 *
 * CMP-ATT-002: `TourAPI` alone reads as the brand of the service rather than a
 * credit, so it appears only beside the provider's display name. Today the app
 * says 한국관광공사 and never says TourAPI, so this guard exists to keep it that
 * way rather than to fix anything.
 */
const BARE_PROVIDER = /TourAPI/;
const PROVIDER_DISPLAY_NAME = /한국관광공사|Korea Tourism Organization/;

describe('CMP-ATT-002 TourAPI is never the whole credit', () => {
  it('never names TourAPI without the provider beside it', () => {
    const offenders: string[] = [];
    for (const file of walk(SRC)) {
      if (!/\.(ts|tsx)$/.test(file)) continue;
      if (file.includes('__tests__')) continue;
      const source = readFileSync(file, 'utf8');
      source.split('\n').forEach((line, index) => {
        if (!BARE_PROVIDER.test(line)) return;
        // A comment explaining the rule is not a violation of it.
        if (line.trimStart().startsWith('//') || line.trimStart().startsWith('*')) return;
        if (PROVIDER_DISPLAY_NAME.test(line)) return;
        offenders.push(`${relative(WEB, file)}:${String(index + 1)} ${line.trim()}`);
      });
    }
    expect(offenders).toEqual([]);
  });
});

describe('CMP-ATT-003 the app ships only images it may ship', () => {
  it('has exactly the images on the allow list, no more and no fewer', () => {
    const found = [...walk(PUBLIC), ...walk(SRC)]
      .filter((file) => IMAGE_EXTENSIONS.has(extname(file).toLowerCase()))
      .map((file) => relative(WEB, file))
      .sort();

    // Both directions. Extra files are the compliance risk; missing ones mean
    // the list has gone stale and stopped describing the build.
    expect(found).toEqual(Object.keys(ALLOWED).sort());
  });

  it('gives every allowed image a reason, not just a name', () => {
    // A list of paths with no provenance is a list nobody can audit.
    for (const [path, reason] of Object.entries(ALLOWED)) {
      expect(reason.length, `${path} has no stated provenance`).toBeGreaterThan(20);
    }
  });

  it('names no external image origin in the code', () => {
    // Image URLs reach the screen from the server (coverUrl, thumbnailUrl),
    // never from a literal here. A hardcoded one would be an asset we ship
    // without shipping — outside the inventory above and outside review.
    const offenders: string[] = [];
    for (const file of walk(SRC)) {
      if (!/\.(ts|tsx|css)$/.test(file)) continue;
      if (file.includes('__tests__')) continue;
      const source = readFileSync(file, 'utf8');
      for (const match of source.matchAll(/https?:\/\/[^\s"'`)]+/g)) {
        const url = match[0];
        if (!/\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)(\?|#|$)/i.test(url)) continue;
        const origin = new URL(url).origin;
        if (ALLOWED_IMAGE_ORIGINS.includes(origin)) continue;
        offenders.push(`${relative(WEB, file)}: ${url}`);
      }
    }
    expect(offenders).toEqual([]);
  });
});
