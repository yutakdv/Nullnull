// FE-004 offline shell guards.
//
// The service worker is plain JS served from public/, so it is not part of the
// bundle and no unit test imports it. These tests read the shipped file and the
// built output instead, which is what actually reaches a browser.
//
// The assertion that matters is the API boundary. A worker that served a cached
// /api/v1 response would show stale data as live, with the screen unable to tell
// — precisely what CLAUDE.md forbids. That rule lives in one `if` in sw.js, so
// it needs a test that fails when someone deletes it.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const worker = readFileSync('public/sw.js', 'utf8');
const manifest = JSON.parse(
  readFileSync('public/manifest.webmanifest', 'utf8'),
) as Record<string, unknown> & {
  icons: { src: string; purpose: string }[];
  start_url: string;
  scope: string;
};

describe('the offline shell never caches API data', () => {
  // The worker is evaluated in a fake ServiceWorkerGlobalScope and its real
  // fetch handler is driven, because reading the source for the right `if`
  // proves nothing: commenting that line out still leaves the text present.
  interface FetchEvent {
    request: { method: string; url: string; mode?: string };
    respondWith: (response: unknown) => void;
  }

  function runFetchHandler(request: FetchEvent['request']): { responded: boolean } {
    const listeners: Record<string, (event: unknown) => void> = {};
    const scope = {
      addEventListener: (type: string, handler: (event: unknown) => void) => {
        listeners[type] = handler;
      },
      location: { origin: 'https://nullnull.test' },
      skipWaiting: () => undefined,
      clients: { claim: () => undefined },
      caches: {
        open: () =>
          Promise.resolve({ add: () => Promise.resolve(), put: () => undefined }),
        keys: () => Promise.resolve([]),
        match: () => Promise.resolve(undefined),
        delete: () => Promise.resolve(true),
      },
      fetch: () => Promise.resolve(new Response('')),
      Response,
      URL,
      Promise,
    };
    new Function('self', 'caches', 'fetch', 'Response', 'URL', worker)(
      scope,
      scope.caches,
      scope.fetch,
      Response,
      URL,
    );

    let responded = false;
    listeners.fetch?.({
      request,
      respondWith: () => {
        responded = true;
      },
    });
    return { responded };
  }

  it('does not answer API requests, so a cached body can never be served', () => {
    const { responded } = runFetchHandler({
      method: 'GET',
      url: 'https://nullnull.test/api/v1/trips',
      mode: 'cors',
    });
    expect(responded).toBe(false);
  });

  it('does answer same-origin asset requests', () => {
    const { responded } = runFetchHandler({
      method: 'GET',
      url: 'https://nullnull.test/assets/index-abc123.js',
      mode: 'cors',
    });
    expect(responded).toBe(true);
  });

  it('leaves mutations to the network', () => {
    const { responded } = runFetchHandler({
      method: 'POST',
      url: 'https://nullnull.test/assets/index-abc123.js',
      mode: 'cors',
    });
    expect(responded).toBe(false);
  });

  it('ignores cross-origin requests', () => {
    const { responded } = runFetchHandler({
      method: 'GET',
      url: 'https://elsewhere.test/asset.js',
      mode: 'cors',
    });
    expect(responded).toBe(false);
  });

  it('precaches no API path', () => {
    const shellUrls = /const SHELL_URLS = \[(.*?)\]/s.exec(worker)?.[1] ?? '';
    expect(shellUrls).not.toMatch(/\/api\//);
  });
});

describe('the web manifest is installable', () => {
  it('declares the fields a browser needs to install the app', () => {
    for (const key of ['name', 'short_name', 'start_url', 'display', 'icons']) {
      expect(manifest[key]).toBeTruthy();
    }
    expect(manifest.display).toBe('standalone');
  });

  it('stays inside its own scope', () => {
    expect(manifest.start_url.startsWith(manifest.scope)).toBe(true);
  });

  it('ships a maskable icon as well as a plain one', () => {
    const purposes = manifest.icons.map((icon) => icon.purpose);
    expect(purposes).toContain('any');
    expect(purposes).toContain('maskable');
  });

  it('uses the brand theme colour from the design tokens', () => {
    const tokens = readFileSync('src/design/tokens.css', 'utf8');
    const brand = /--brand-blue:\s*(#[0-9a-f]{6})/i.exec(tokens)?.[1];
    expect(manifest.theme_color).toBe(brand);
  });
});

describe('the built app ships the shell', () => {
  let dist: string;

  beforeAll(() => {
    // Build into a throwaway directory so the assertion never depends on a
    // dist/ left over from an earlier run — the skip that bit FE-003 in CI.
    dist = mkdtempSync(join(tmpdir(), 'nullnull-pwa-'));
    // Vitest runs with NODE_ENV=test, which Vite honours over --mode and would
    // leave import.meta.env.DEV true — the bundle would then be missing the
    // registration and this test would "pass" by asserting the wrong build.
    execFileSync(
      'npx',
      ['vite', 'build', '--mode', 'production', '--outDir', dist, '--emptyOutDir'],
      { stdio: 'pipe', env: { ...process.env, NODE_ENV: 'production' } },
    );
  }, 120_000);

  afterAll(() => {
    rmSync(dist, { recursive: true, force: true });
  });

  it('copies the manifest, worker and icons into the build', () => {
    for (const file of [
      'manifest.webmanifest',
      'sw.js',
      'icon.svg',
      'icon-maskable.svg',
    ]) {
      expect(() => readFileSync(join(dist, file))).not.toThrow();
    }
  });

  it('links the manifest from the entry document', () => {
    const html = readFileSync(join(dist, 'index.html'), 'utf8');
    expect(html).toMatch(/rel="manifest"/);
    expect(html).toMatch(/name="theme-color"/);
  });

  it('registers the worker only outside dev', () => {
    const html = readFileSync(join(dist, 'index.html'), 'utf8');
    // Vite emits `<script type="module" crossorigin src="...">`, so the
    // attribute order cannot be assumed.
    const entry = /<script[^>]*\ssrc="(\/assets\/index-[^"]+\.js)"/.exec(html)?.[1];
    expect(entry).toBeDefined();
    const bundle = readFileSync(join(dist, entry ?? ''), 'utf8');
    // Asserted as booleans: a failed toMatch would print the whole bundle.
    // import.meta.env.DEV is statically false in a production build, so the
    // guard is resolved away and the registration call survives.
    expect(bundle.includes('serviceWorker')).toBe(true);
    expect(bundle.includes('sw.js')).toBe(true);
  });
});
